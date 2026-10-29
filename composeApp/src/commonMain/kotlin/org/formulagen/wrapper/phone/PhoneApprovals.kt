package org.formulagen.wrapper.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.bridge.ApprovalCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceActivated
import org.formulagen.wrapper.bridge.ApprovalDeviceCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceRef
import org.formulagen.wrapper.bridge.ApprovalDeviceRegistration
import org.formulagen.wrapper.bridge.ApprovalPresentOutcome
import org.formulagen.wrapper.bridge.ApprovalPresentResult
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.BridgeDeclinedException
import org.formulagen.wrapper.bridge.PRESENT_DIALOG_LIMIT
import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalPresentation
import org.formulagen.wrapper.conversations.ApprovalState
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The phone as an approval device: registration ([ApprovalDeviceCapability]) and approvals ([ApprovalCapability]),
 * for every phone platform. The platform brings its HTTP client, Keychain or Keystore ([vault]), cryptography and
 * dialogs; everything else is here, so `commonTest` drives it against a fake server.
 *
 * The page only names an approval. This reads it with the device's own token, shows the exact lines natively, posts
 * the answer itself, and tells the page only how the dialog ended. The first answer wins: a late answer is told it
 * lost and shown the winner, and an open dialog closes when the approval ends elsewhere (checked every
 * [pollInterval]). One dialog shows at a time; asking for the one showing waits for it, and asking for another
 * answers busy.
 */
internal class PhoneApprovals(
    private val platform: String,
    private val name: String,
    private val api: DeviceApprovalApi,
    private val vault: DeviceVault,
    private val crypto: DeviceCrypto,
    private val dialogs: ApprovalDialogs,
    /** Epoch milliseconds. */
    private val clock: () -> Long,
    private val pollInterval: Duration = POLL_INTERVAL,
    context: CoroutineContext = Dispatchers.Default,
) : ApprovalCapability, ApprovalDeviceCapability, AutoCloseable {
    /** Dialogs outlive the page call that asked for them, so a navigation never leaves one half answered. */
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val registering = Mutex()
    private val state = Mutex()
    private var pending: DeviceCredential? = null
    private var showing: Pair<String, Deferred<ApprovalPresentOutcome>>? = null

    override suspend fun prepare(ownerId: String): ApprovalDeviceRegistration = singleFlight {
        if (!dialogs.confirm(PhoneApprovalCopy.REGISTER)) throw BridgeDeclinedException("Approval device declined")
        val credential = DeviceCredential(crypto.newId(), ownerId.lowercase(), name, platform, crypto.newToken())
        state.withLock { pending = credential }
        ApprovalDeviceRegistration(credential.id, credential.name, credential.platform, crypto.tokenHash(credential.token))
    }

    /** Keeps the prepared device once the server knows it: a read with its token must succeed first. */
    override suspend fun activate(ownerId: String, deviceId: String): ApprovalDeviceActivated = singleFlight {
        val prepared = state.withLock { pending }
        check(prepared != null && prepared.id == deviceId && prepared.ownerId == ownerId.lowercase()) {
            "Prepare this phone for that account first"
        }
        try {
            api.list(prepared)
        } catch (_: DeviceUnauthorizedException) {
            throw IllegalStateException("The account has not registered this phone")
        }
        state.withLock {
            vault.write(DeviceCredential.write(prepared))
            pending = null
        }
        ApprovalDeviceActivated(prepared.id)
    }

    override suspend fun status(): ApprovalDeviceRef? = active()?.let { ApprovalDeviceRef(it.id, it.ownerId) }

    override suspend fun present(approvalId: String): ApprovalPresentResult {
        val device = active() ?: return ApprovalPresentResult(ApprovalPresentOutcome.UNREGISTERED)
        val shown = state.withLock {
            val current = showing
            when {
                current != null && current.first == approvalId && current.second.isActive -> current.second
                current != null && current.second.isActive -> throw BridgeBusyException()
                else -> scope.async { show(device, approvalId) }.also { showing = approvalId to it }
            }
        }
        return ApprovalPresentResult(shown.await())
    }

    override fun close() = scope.cancel()

    /** Reads the approval with the device's token, shows it, and posts the answer the person picked. */
    private suspend fun show(device: DeviceCredential, approvalId: String): ApprovalPresentOutcome {
        val shown = try {
            api.read(device, approvalId)
        } catch (_: DeviceUnauthorizedException) {
            return forget(device)
        } catch (_: DeviceNotFoundException) {
            return ApprovalPresentOutcome.ENDED
        }
        val approval = shown.approval
        val deadline = minOf(expiryOf(approval), clock() + PRESENT_DIALOG_LIMIT.inWholeMilliseconds)
        if (approval.state != ApprovalState.OPEN || clock() >= deadline) return ApprovalPresentOutcome.ENDED
        val lines = PhoneApprovalCopy.linesOf(approval.presentation)
        if (lines == null) {
            val summary = (approval.presentation as ApprovalPresentation.MacOnly).summary
            val told = untilEnded(device, approvalId, deadline) { dialogs.tell(PhoneApprovalCopy.macOnly(shown, summary)) }
            return if (told == null) ApprovalPresentOutcome.ENDED else ApprovalPresentOutcome.MAC_ONLY
        }
        val picked = untilEnded(device, approvalId, deadline) { dialogs.ask(PhoneApprovalCopy.prompt(shown, lines)) }
            ?: return ApprovalPresentOutcome.ENDED
        val option = picked.value ?: return ApprovalPresentOutcome.DISMISSED
        // the person answered: send it even if the page that asked has gone
        return withContext(NonCancellable) { send(device, shown, option) }
    }

    /** Posts [option] at the revision the dialog showed; a lost answer says who won. */
    private suspend fun send(device: DeviceCredential, shown: DeviceApproval, option: ApprovalOption): ApprovalPresentOutcome {
        val id = shown.approval.id
        val ended: AgentApproval = try {
            val result = api.answer(device, id, ApprovalAnswer(shown.approval.revision, option))
            if (result.status == ApprovalAnswerStatus.ANSWERED) return ApprovalPresentOutcome.ANSWERED
            result.approval
        } catch (_: DeviceUnauthorizedException) {
            return forget(device)
        } catch (_: DeviceNotFoundException) {
            return ApprovalPresentOutcome.ENDED
        } catch (refused: DeviceRefusedException) {
            // only an approval that changed under the dialog is refused; read how it ended
            runCatching { api.read(device, id).approval }.getOrNull()?.takeIf { it.state != ApprovalState.OPEN }
                ?: return unsent(refused)
        } catch (failure: Exception) {
            // no answer from the server: the answer may or may not have landed
            return unsent(failure)
        }
        dialogs.tell(PhoneApprovalCopy.lost(ended, shown.computerName, device.id))
        return ApprovalPresentOutcome.LOST
    }

    /** Tells the person their answer was not confirmed, then fails the call. */
    private suspend fun unsent(failure: Exception): Nothing {
        dialogs.tell(PhoneApprovalCopy.UNSENT)
        throw failure
    }

    /**
     * Runs [dialog] until it returns, or until the approval ends elsewhere or [deadline] passes, which closes it.
     * Null when it was closed that way.
     */
    private suspend fun <T> untilEnded(device: DeviceCredential, approvalId: String, deadline: Long, dialog: suspend () -> T): Shown<T>? =
        coroutineScope {
            val answered = async { Shown(dialog()) }
            val ended = async { waitUntilEnded(device, approvalId, deadline); null }
            select { answered.onAwait { it }; ended.onAwait { it } }.also {
                answered.cancel()
                ended.cancel()
            }
        }

    /** Returns once the approval is no longer open, it is gone, or [deadline] passes. A failed read keeps waiting. */
    private suspend fun waitUntilEnded(device: DeviceCredential, approvalId: String, deadline: Long) {
        while (true) {
            delay(minOf(pollInterval.inWholeMilliseconds, (deadline - clock()).coerceAtLeast(0)))
            if (clock() >= deadline) return
            try {
                if (api.read(device, approvalId).approval.state != ApprovalState.OPEN) return
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeviceUnauthorizedException) {
                return
            } catch (_: DeviceNotFoundException) {
                return
            } catch (_: Exception) {
                // the server is unreachable for now; the dialog stays
            }
        }
    }

    /** The registered device, or null; a damaged item is cleared. */
    private suspend fun active(): DeviceCredential? = state.withLock {
        val stored = vault.read() ?: return@withLock null
        DeviceCredential.read(stored) ?: null.also { vault.clear() }
    }

    /** The server no longer knows this device: forget it, so the page offers registration again. */
    private suspend fun forget(device: DeviceCredential): ApprovalPresentOutcome {
        state.withLock { if (DeviceCredential.read(vault.read())?.id == device.id) vault.clear() }
        return ApprovalPresentOutcome.UNREGISTERED
    }

    private suspend fun <T> singleFlight(block: suspend () -> T): T {
        if (!registering.tryLock()) throw BridgeBusyException()
        try {
            return block()
        } finally {
            registering.unlock()
        }
    }

    /** A dialog's result, so a dismissed one (null) differs from one closed because the approval ended. */
    private class Shown<T>(val value: T)

    @OptIn(ExperimentalTime::class)
    private fun expiryOf(approval: AgentApproval): Long = Instant.parse(approval.expiresAt).toEpochMilliseconds()

    companion object {
        /** How often an open dialog checks whether its approval ended elsewhere. */
        val POLL_INTERVAL: Duration = 3.seconds
    }
}
