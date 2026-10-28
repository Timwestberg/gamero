package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.providers.CLAUDE_MODELS
import org.formulagen.wrapper.providers.copy

/** What a provider asks to do, as the approval dialog names it. */
internal enum class ApprovalKind { EDIT, COMMAND, CONNECTOR, OTHER }

/**
 * Whatever a working member's provider would ask the player in a terminal, for the native dialog.
 * [companion] is the companion's name, [folder] its project folder. [title] is what is asked (the
 * command, the file, or the connector action) and [detail] what more the provider said, both with
 * paths relative to [folder]. [sessionLabel] is the "for this conversation" button exactly as the
 * provider means the grant ("Allow all edits in game for this conversation"), or null when the
 * provider offers no such grant, and then the dialog has no such button.
 */
internal data class ApprovalRequest(
    val companion: String,
    val member: TeamMember,
    val folder: Path,
    val kind: ApprovalKind,
    val title: String,
    val detail: String,
    val sessionLabel: String?,
) {
    /** What the page may show while the dialog is up ("Ember wants to run `npm test`."), at most [WAITING_SUMMARY_LIMIT] characters. */
    fun summary(): String = "$companion wants to ${verb(kind)}: $title".shortened(WAITING_SUMMARY_LIMIT)
}

/** The person's answer. Anything but an explicit allow (a closed dialog, a timeout, a stopped reply) is [DENY]. */
internal enum class ApprovalDecision { ALLOW_ONCE, ALLOW_FOR_CONVERSATION, DENY }

/**
 * The seam between a working member's runtime and the native dialog. Blocks until the person
 * answers, [cancel] fires (the reply was stopped or the chat closed), or the dialog times out; the
 * last two are [ApprovalDecision.DENY]. Only native code can answer: the page never sees the dialog.
 */
internal fun interface ApprovalBroker {
    fun ask(request: ApprovalRequest, cancel: CancelSignal): ApprovalDecision
}

/** Fires once, from any thread: a stopped reply, a closed chat or a provider withdrawing its request. */
internal class CancelSignal {
    private val fired = AtomicBoolean()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val isCancelled: Boolean get() = fired.get()

    fun cancel() { if (fired.compareAndSet(false, true)) listeners.forEach { it() } }

    /** Run [action] when this fires, or at once when it already has. Closing the result stops listening. */
    fun onCancel(action: () -> Unit): AutoCloseable {
        listeners += action
        if (fired.get() && listeners.remove(action)) action()
        return AutoCloseable { listeners.remove(action) }
    }
}

/** The broker behind the native dialog: one approval at a time, in the order asked, denied after the dialogs' timeout. */
internal class DialogApprovalBroker(private val dialogs: NativeDialogs) : ApprovalBroker {
    override fun ask(request: ApprovalRequest, cancel: CancelSignal): ApprovalDecision {
        val dialog = approvalDialog(request)
        return when (dialogs.ask(dialog, cancel)) {
            0 -> ApprovalDecision.ALLOW_ONCE
            1 -> if (request.sessionLabel != null) ApprovalDecision.ALLOW_FOR_CONVERSATION else ApprovalDecision.DENY
            else -> ApprovalDecision.DENY
        }
    }
}

/** The dialog for [request]: what is asked, by which member of which companion, in which folder; Deny is also what closing it means. */
internal fun approvalDialog(request: ApprovalRequest): NativeDialog {
    val buttons = listOfNotNull("Allow once", request.sessionLabel, "Deny")
    return NativeDialog(
        title = "${request.companion} wants to ${verb(request.kind)}",
        lines = listOfNotNull(
            request.title,
            request.detail.takeIf { it.isNotBlank() },
            "Member: ${memberLabel(request.member)}",
            "Project folder: ${displayPath(request.folder)}",
        ),
        buttons = buttons,
        dismissIndex = buttons.lastIndex,
    )
}

/** "Claude · Opus", "Codex · gpt-6-luna", "Codex · default model": how a member is named to the player. */
internal fun memberLabel(member: TeamMember): String {
    val model = when (member.modelId) {
        RUNTIME_DEFAULT_MODEL -> "default model"
        else -> CLAUDE_MODELS.firstOrNull { it.modelId == member.modelId }?.displayName ?: member.modelId
    }
    return "${member.runtimeId.copy.product} · $model"
}

/** A native-only path for the player to read, with their home folder as `~`. */
internal fun displayPath(path: Path): String {
    val home = System.getProperty("user.home")?.let(Path::of)
    return if (home != null && path.startsWith(home) && path != home) "~/" + home.relativize(path) else path.toString()
}

private fun verb(kind: ApprovalKind) = when (kind) {
    ApprovalKind.EDIT -> "edit files"
    ApprovalKind.COMMAND -> "run a command"
    ApprovalKind.CONNECTOR -> "use a connector"
    ApprovalKind.OTHER -> "use a tool"
}

/** At most [limit] characters, an ellipsis marking a cut. */
internal fun String.shortened(limit: Int): String = if (length <= limit) this else take(limit - 1).trimEnd() + "…"

/** Longest "waiting for your approval" summary the page is shown (the contract's `waiting.summary`). */
internal const val WAITING_SUMMARY_LIMIT = 300
