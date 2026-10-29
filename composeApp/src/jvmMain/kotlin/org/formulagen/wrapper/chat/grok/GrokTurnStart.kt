package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.TurnRequest
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grok.GrokAccount
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability

/** A turn's checked starting point, or the failure that ends it before any Grok process starts. */
internal sealed interface GrokTurnStart {
    /** [folder] is the canonical project folder of a work turn, null for talk; [previous] is the binding it resumes. */
    data class Ready(val account: GrokAccount, val executable: Path, val model: String, val folder: Path?,
        val scope: GrokSessionScope, val previous: GrokSessionBinding?) : GrokTurnStart

    data class Failed(val failure: ChatFailure) : GrokTurnStart
}

/**
 * The start shared by talk and work turns: the saved conversation's persona, a fresh account check, an
 * available model, the turn's ownership scope and the saved binding it resumes. Only a missing or
 * model-changed binding loses the conversation, which allows DesktopChat's one context-restoration retry;
 * a binding of another scope is never resumed. [prune] removes orphan bindings once the scope is valid;
 * [unresumable] sees a binding that exists but cannot be resumed.
 */
internal fun resolveGrokTurn(
    request: TurnRequest,
    mode: GrokMode,
    connection: GrokConnection,
    bindings: GrokSessions,
    prune: () -> Unit,
    unresumable: (GrokSessionLookup) -> Unit = {},
): GrokTurnStart {
    val saved = request.conversation
    if (saved != null && saved.personaHash != request.persona.hash) return GrokTurnStart.Failed(ChatFailure.ConversationLost)
    val access = connection.check(fresh = true)
    val account = access.account ?: return GrokTurnStart.Failed(
        if (access.auth == GrokAuthAvailability.SIGNED_OUT) ChatFailure.SignedOut else ChatFailure.Temporary)
    val executable = access.executable ?: return GrokTurnStart.Failed(ChatFailure.Temporary)
    val model = request.model?.takeUnless { it == SharedRules.RUNTIME_DEFAULT_MODEL } ?: account.defaultModel
    if (account.models.none { it.modelId == model }) return GrokTurnStart.Failed(ChatFailure.ModelUnavailable)
    val folder = if (mode == GrokMode.WORK) checkNotNull(request.work).folder.toRealPath() else null
    val scope = GrokSessionScope(request.profile, request.companionId, mode, model, account.fingerprint,
        folder?.let { HovelCompanion.sha256(it.toString()) })
    scope.validate()
    prune()
    val previous = saved?.let {
        when (val lookup = bindings.find(it.id, scope)) {
            is GrokSessionLookup.Found -> lookup.binding
            GrokSessionLookup.Missing, GrokSessionLookup.ModelChanged -> return GrokTurnStart.Failed(ChatFailure.ConversationLost)
            else -> { unresumable(lookup); return GrokTurnStart.Failed(ChatFailure.Unexpected) }
        }
    }
    return GrokTurnStart.Ready(account, executable, model, folder, scope, previous)
}
