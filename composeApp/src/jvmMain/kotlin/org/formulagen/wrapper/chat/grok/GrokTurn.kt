package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grok.GROK_TEARDOWN
import org.formulagen.wrapper.providers.grok.GrokAccount
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.GrokCleanupUnconfirmed

/** How long Stop leaves ACP cancellation to end a prompt before the turn ends the process itself. */
internal val GROK_STOP_GRACE: Duration = 5.seconds

/**
 * How long a new turn waits for the previous one on the same owner to release it: a stopped turn's
 * cancellation grace, then its forced teardown, with a second of scheduling slack.
 */
internal val GROK_TURN_HANDOFF: Duration = GROK_STOP_GRACE + GROK_TEARDOWN + 1.seconds

/** A turn's checked starting point, or the failure that ends it before any Grok process starts. */
internal sealed interface GrokTurnStart<out F> {
    /** [folder] is a work turn's canonical project folder; a talk turn has none. [previous] is the binding it resumes. */
    data class Ready<out F>(val account: GrokAccount, val executable: Path, val model: String, val folder: F,
        val scope: GrokSessionScope, val previous: GrokSessionBinding?) : GrokTurnStart<F>

    data class Failed(val failure: ChatFailure) : GrokTurnStart<Nothing>
}

/** [resolveGrokTurn] for a talk request, which has no project folder. */
internal fun resolveGrokTalkTurn(request: TurnRequest, connection: GrokConnection, bindings: GrokSessions,
    beforeLookup: () -> Unit): GrokTurnStart<Nothing?> {
    require(request.work == null)
    return resolveGrokTurn(request, connection, bindings, beforeLookup, folder = { null }, workspace = { null })
}

/** [resolveGrokTurn] for a work request; its folder is canonicalised in its place among the checks. */
internal fun resolveGrokWorkTurn(request: TurnRequest, connection: GrokConnection, bindings: GrokSessions,
    beforeLookup: () -> Unit): GrokTurnStart<Path> {
    val work = requireNotNull(request.work)
    return resolveGrokTurn(request, connection, bindings, beforeLookup, folder = { work.folder.toRealPath() },
        workspace = { HovelCompanion.sha256(it.toString()) })
}

/**
 * The start shared by talk and work turns, whose mode follows [TurnRequest.work]: the saved conversation's persona,
 * a fresh account check, an available model, the turn's ownership scope and the saved binding it resumes. Only a
 * missing or model-changed binding loses the conversation, and the role's next turn starts fresh; a binding of another scope is never resumed, and is logged. [beforeLookup] runs once the scope is valid:
 * it retains the bindings in use, then prunes orphans.
 */
private inline fun <F> resolveGrokTurn(request: TurnRequest, connection: GrokConnection, bindings: GrokSessions,
    beforeLookup: () -> Unit, folder: () -> F, workspace: (F) -> String?): GrokTurnStart<F> {
    val mode = if (request.work == null) GrokMode.TALK else GrokMode.WORK
    val saved = request.conversation
    if (saved != null && saved.personaHash != request.persona.hash) return GrokTurnStart.Failed(ChatFailure.ConversationLost)
    val access = connection.check(fresh = true)
    val account = access.account ?: return GrokTurnStart.Failed(
        if (access.auth == GrokAuthAvailability.SIGNED_OUT) ChatFailure.SignedOut else ChatFailure.Temporary)
    val executable = access.executable ?: return GrokTurnStart.Failed(ChatFailure.Temporary)
    val model = request.model?.takeUnless { it == SharedRules.RUNTIME_DEFAULT_MODEL } ?: account.defaultModel
    if (account.models.none { it.modelId == model }) return GrokTurnStart.Failed(ChatFailure.ModelUnavailable)
    val canonical = folder()
    val scope = GrokSessionScope(request.accountId, request.companionId, mode, model, account.fingerprint, workspace(canonical))
    scope.validate()
    beforeLookup()
    val previous = saved?.let {
        when (val lookup = bindings.find(it.id, scope)) {
            is GrokSessionLookup.Found -> lookup.binding
            GrokSessionLookup.Missing, GrokSessionLookup.ModelChanged -> return GrokTurnStart.Failed(ChatFailure.ConversationLost)
            else -> {
                log(LogLevel.Warn, "grok", "Grok ${mode.name.lowercase()} binding could not be resumed (${lookup::class.simpleName})")
                return GrokTurnStart.Failed(ChatFailure.Unexpected)
            }
        }
    }
    return GrokTurnStart.Ready(account, executable, model, canonical, scope, previous)
}

/**
 * Open a resolved turn's session with [open]. When Grok refuses the saved session before any prompt, its binding
 * is forgotten so the handle cannot repeat the failing load, and the result is null: the turn has lost its
 * conversation. The binding is forgotten as well when that refusal's cleanup was not confirmed, but the
 * [GrokCleanupUnconfirmed] still propagates.
 */
internal inline fun <S> GrokSessions.openForgettingLost(previous: GrokSessionBinding?, open: () -> S): S? =
    try { open() }
    catch (_: GrokSessionLost) { previous?.let { forget(it.handle) }; null }
    catch (cleanup: GrokCleanupUnconfirmed) {
        if (cleanup.suppressed.any { it is GrokSessionLost }) previous?.let { forget(it.handle) }
        throw cleanup
    }
