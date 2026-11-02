package org.formulagen.wrapper.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.formulagen.wrapper.bridge.ApprovalCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceActivated
import org.formulagen.wrapper.bridge.ApprovalDeviceCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceForgotten
import org.formulagen.wrapper.bridge.ApprovalDevicePairOutcome
import org.formulagen.wrapper.bridge.ApprovalDevicePaired
import org.formulagen.wrapper.bridge.ApprovalDeviceRef
import org.formulagen.wrapper.bridge.ApprovalDeviceRegistration
import org.formulagen.wrapper.bridge.ApprovalPresentOutcome
import org.formulagen.wrapper.bridge.ApprovalPresentResult
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.BridgeDeclinedException
import org.formulagen.wrapper.bridge.PAIRING_LIMIT
import org.formulagen.wrapper.bridge.PRESENT_DIALOG_LIMIT
import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalState
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The phone as an approval device: registration and pairing ([ApprovalDeviceCapability]) and approvals
 * ([ApprovalCapability]), for every phone platform. The platform brings its HTTP client, Keychain or Keystore
 * ([vault]), cryptography and dialogs; everything else is here, so `commonTest` drives it against a fake server.
 *
 * A registered phone starts pending and answers nothing until a Mac confirms it: [pair] fetches a code with the
 * device's own token and shows it natively, and the person types it into that Mac's "Pair a phone…" dialog. Each Mac
 * that confirms the phone lets it answer that Mac's approvals only.
 *
 * The page only names an approval. This reads it with the device's own token, shows the exact lines natively, posts
 * the answer itself, and tells the page only how the dialog ended. The first answer wins: a late answer is told it
 * lost and shown the winner, and an open dialog closes when the approval ends elsewhere, or its Mac stops trusting
 * this phone (checked every [pollInterval]). Nothing it shows for one approval, the notice after it included, stays up
 * past [PRESENT_DIALOG_LIMIT]. One dialog shows at a time; asking for the one showing waits for it, and asking for
 * another answers busy.
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

    /**
     * Keeps the prepared device once the server knows it: its status, the one read a pending device has, must succeed
     * with its token first.
     */
    override suspend fun activate(ownerId: String, deviceId: String): ApprovalDeviceActivated = singleFlight {
        val prepared = state.withLock { pending }
        check(prepared != null && prepared.id == deviceId && prepared.ownerId == ownerId.lowercase()) {
            "Prepare this phone for that account first"
        }
        val known = try {
            api.status(prepared)
        } catch (_: DeviceUnauthorizedException) {
            throw IllegalStateException("The account has not registered this phone")
        }
        check(known.id == prepared.id) { "The server answered for another device" }
        state.withLock {
            vault.write(DeviceCredential.write(prepared))
            pending = null
        }
        ApprovalDeviceActivated(prepared.id)
    }

    /**
     * Drops a prepared device not yet activated, so a page attached later cannot finish a registration an earlier page
     * began. The platform calls it whenever it attaches the bridge to a page.
     */
    fun invalidatePending() {
        // undispatched: clears at once unless a registration holds the lock, and then right after it
        scope.launch(start = CoroutineStart.UNDISPATCHED) { state.withLock { pending = null } }
    }

    /**
     * Shows a new pairing code, fetched with the device's own token, until a Mac confirms this phone (its `pairedWith`
     * grows), the person closes the screen, or the code runs out. Closing it right after typing the code on the Mac
     * still answers `paired`: one last status read decides.
     */
    override suspend fun pair(): ApprovalDevicePaired = singleFlight {
        val device = active() ?: return@singleFlight paired(ApprovalDevicePairOutcome.UNREGISTERED)
        val outcome = try {
            val before = api.status(device).pairedWith.map { it.computerId }.toSet()
            val code = api.pairingCode(device)
            val deadline = minOf(epochOf(code.expiresAt), clock() + PAIRING_LIMIT.inWholeMilliseconds)
            when (val closed = whileWatching({ waitUntilPaired(device, before, deadline) }) { dialogs.tell(PhoneApprovalCopy.pairing(code)) }) {
                is Closed.ByWatch -> closed.outcome
                is Closed.ByPerson -> if (pairedSince(device, before)) ApprovalDevicePairOutcome.PAIRED else ApprovalDevicePairOutcome.DISMISSED
            }
        } catch (_: DeviceUnauthorizedException) {
            forgetHere(device)
            ApprovalDevicePairOutcome.UNREGISTERED
        }
        paired(outcome)
    }

    /**
     * Revokes this phone's device with its own token, then clears it from the Keychain or Keystore and closes any dialog
     * it shows. A device the server already refuses is cleared alike. When the revoke gets no answer, the phone is
     * cleared all the same, so a signed-out phone keeps no token; the account's device list still shows the device to
     * revoke. It runs to the end even when the page that asked goes away, as it does on sign-out.
     */
    override suspend fun forget(): ApprovalDeviceForgotten = withContext(NonCancellable) {
        // a sign-out navigates away at once: the page leaving never stops the wipe half done
        state.withLock { pending = null }
        val device = active() ?: return@withContext ApprovalDeviceForgotten(false)
        try {
            api.revoke(device)
        } catch (_: Exception) {
            // refused (already revoked or expired) or unanswered: clear it regardless
        }
        forgetHere(device)
        state.withLock { showing?.second?.cancel() }
        ApprovalDeviceForgotten(true)
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
        val cap = clock() + PRESENT_DIALOG_LIMIT.inWholeMilliseconds
        val shown = try {
            api.read(device, approvalId)
        } catch (_: DeviceUnauthorizedException) {
            return refused(device)
        } catch (_: DeviceNotFoundException) {
            return ApprovalPresentOutcome.ENDED
        }
        val approval = shown.approval
        val deadline = minOf(epochOf(approval.expiresAt), cap)
        if (approval.state != ApprovalState.OPEN || clock() >= deadline) return ApprovalPresentOutcome.ENDED
        if (!shown.paired) return notice(device, approvalId, deadline, PhoneApprovalCopy.unpaired(shown), ApprovalPresentOutcome.UNPAIRED)
        val lines = PhoneApprovalCopy.linesOf(approval.presentation)
            ?: return notice(device, approvalId, deadline, PhoneApprovalCopy.macOnly(shown), ApprovalPresentOutcome.MAC_ONLY)
        val closed = whileWatching({ waitUntilEnded(device, approvalId, deadline, needsPairing = true) }) {
            dialogs.ask(PhoneApprovalCopy.prompt(shown, lines))
        }
        val option = when (closed) {
            is Closed.ByWatch -> return closed.outcome
            is Closed.ByPerson -> closed.value ?: return ApprovalPresentOutcome.DISMISSED
        }
        // the person answered: send it even if the page that asked has gone
        return withContext(NonCancellable) { send(device, shown, option, cap) }
    }

    /** Shows [notice] until the person closes it ([outcome]), or the approval ends or [deadline] passes. */
    private suspend fun notice(
        device: DeviceCredential,
        approvalId: String,
        deadline: Long,
        notice: ApprovalNotice,
        outcome: ApprovalPresentOutcome,
    ): ApprovalPresentOutcome = when (val closed = whileWatching({ waitUntilEnded(device, approvalId, deadline, needsPairing = false) }) { dialogs.tell(notice) }) {
        is Closed.ByWatch -> closed.outcome
        is Closed.ByPerson -> outcome
    }

    /** Posts [option] at the revision the dialog showed; a lost answer says who won, until [cap]. */
    private suspend fun send(device: DeviceCredential, shown: DeviceApproval, option: ApprovalOption, cap: Long): ApprovalPresentOutcome {
        val id = shown.approval.id
        val ended: AgentApproval = try {
            val result = api.answer(device, id, ApprovalAnswer(shown.approval.revision, option))
            if (result.status == ApprovalAnswerStatus.ANSWERED) return ApprovalPresentOutcome.ANSWERED
            result.approval
        } catch (_: DeviceUnauthorizedException) {
            tellUntil(cap, PhoneApprovalCopy.NOT_USED)
            return refused(device)
        } catch (_: DeviceNotPairedException) {
            // the approval's Mac stopped trusting this phone while its dialog showed
            tellUntil(cap, PhoneApprovalCopy.NOT_USED)
            return ApprovalPresentOutcome.UNPAIRED
        } catch (_: DeviceNotFoundException) {
            return ApprovalPresentOutcome.ENDED
        } catch (refused: DeviceRefusedException) {
            // only an approval that changed under the dialog is refused; read how it ended
            runCatching { api.read(device, id).approval }.getOrNull()?.takeIf { it.state != ApprovalState.OPEN }
                ?: return unsent(refused, cap)
        } catch (failure: Exception) {
            // no answer from the server: the answer may or may not have landed
            return unsent(failure, cap)
        }
        tellUntil(cap, PhoneApprovalCopy.lost(ended, shown, device.id))
        return ApprovalPresentOutcome.LOST
    }

    /** Tells the person, until [cap], that their answer was not confirmed, then fails the call. */
    private suspend fun unsent(failure: Exception, cap: Long): Nothing {
        tellUntil(cap, PhoneApprovalCopy.UNSENT)
        throw failure
    }

    /** Shows [notice] until the person closes it or [cap] passes. */
    private suspend fun tellUntil(cap: Long, notice: ApprovalNotice) {
        withTimeoutOrNull((cap - clock()).coerceAtLeast(0).milliseconds) { dialogs.tell(notice) }
    }

    /**
     * A device route refused this device's token. It is either live with no Mac confirming it ([ApprovalPresentOutcome.UNPAIRED];
     * its own status still reads) or gone: revoked, or a pending registration that ran out. A gone one is forgotten.
     */
    private suspend fun refused(device: DeviceCredential): ApprovalPresentOutcome = try {
        api.status(device)
        ApprovalPresentOutcome.UNPAIRED
    } catch (_: DeviceUnauthorizedException) {
        forgetHere(device)
        ApprovalPresentOutcome.UNREGISTERED
    }

    /** Runs [dialog] until it returns, or until [watch] returns first, which closes it. */
    private suspend fun <T, W> whileWatching(watch: suspend () -> W, dialog: suspend () -> T): Closed<T, W> = coroutineScope {
        val closed = async { Closed.ByPerson(dialog()) }
        val watched = async { Closed.ByWatch(watch()) }
        select<Closed<T, W>> { closed.onAwait { it }; watched.onAwait { it } }.also {
            closed.cancel()
            watched.cancel()
        }
    }

    /**
     * How an approval's dialog closes by itself: [ApprovalPresentOutcome.ENDED] once the approval is no longer open,
     * is gone, or [deadline] passes; [ApprovalPresentOutcome.UNPAIRED] once its Mac stops trusting this phone (when
     * [needsPairing]); and as [refused] says once the device's token is refused. A failed read keeps waiting.
     */
    private suspend fun waitUntilEnded(device: DeviceCredential, approvalId: String, deadline: Long, needsPairing: Boolean): ApprovalPresentOutcome {
        while (true) {
            delay(minOf(pollInterval.inWholeMilliseconds, (deadline - clock()).coerceAtLeast(0)))
            if (clock() >= deadline) return ApprovalPresentOutcome.ENDED
            try {
                val read = api.read(device, approvalId)
                if (read.approval.state != ApprovalState.OPEN) return ApprovalPresentOutcome.ENDED
                if (needsPairing && !read.paired) return ApprovalPresentOutcome.UNPAIRED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeviceUnauthorizedException) {
                // unless the status read gets no answer either: then the dialog stays
                runCatching { refused(device) }.getOrNull()?.let { return it }
            } catch (_: DeviceNotFoundException) {
                return ApprovalPresentOutcome.ENDED
            } catch (_: Exception) {
                // the server is unreachable for now; the dialog stays
            }
        }
    }

    /** How the pairing screen closes by itself: a Mac confirmed the phone, the code ran out, or the device is gone. */
    private suspend fun waitUntilPaired(device: DeviceCredential, before: Set<String>, deadline: Long): ApprovalDevicePairOutcome {
        while (true) {
            delay(minOf(pollInterval.inWholeMilliseconds, (deadline - clock()).coerceAtLeast(0)))
            if (clock() >= deadline) return ApprovalDevicePairOutcome.EXPIRED
            try {
                if (pairedSince(device, before)) return ApprovalDevicePairOutcome.PAIRED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: DeviceUnauthorizedException) {
                forgetHere(device)
                return ApprovalDevicePairOutcome.UNREGISTERED
            } catch (_: Exception) {
                // the server is unreachable for now; the screen stays
            }
        }
    }

    /** Whether a Mac not in [before] confirmed this phone. */
    private suspend fun pairedSince(device: DeviceCredential, before: Set<String>): Boolean =
        api.status(device).pairedWith.any { it.computerId !in before }

    /** The registered device, or null; a damaged item is cleared. */
    private suspend fun active(): DeviceCredential? = state.withLock {
        val stored = vault.read() ?: return@withLock null
        DeviceCredential.read(stored) ?: null.also { vault.clear() }
    }

    /** Clears [device] from the Keychain or Keystore, unless another registration replaced it meanwhile. */
    private suspend fun forgetHere(device: DeviceCredential) {
        state.withLock { if (DeviceCredential.read(vault.read())?.id == device.id) vault.clear() }
    }

    private suspend fun <T> singleFlight(block: suspend () -> T): T {
        if (!registering.tryLock()) throw BridgeBusyException()
        try {
            return block()
        } finally {
            registering.unlock()
        }
    }

    private fun paired(outcome: ApprovalDevicePairOutcome) = ApprovalDevicePaired(outcome)

    /** How a dialog closed: the person closed it ([ByPerson], with what they chose), or [ByWatch] closed it first. */
    private sealed interface Closed<out T, out W> {
        class ByPerson<T>(val value: T) : Closed<T, Nothing>
        class ByWatch<W>(val outcome: W) : Closed<Nothing, W>
    }

    @OptIn(ExperimentalTime::class)
    private fun epochOf(time: String): Long = Instant.parse(time).toEpochMilliseconds()

    companion object {
        /** How often an open dialog checks whether its approval ended elsewhere, and the pairing screen its pairing. */
        val POLL_INTERVAL: Duration = 3.seconds
    }
}
