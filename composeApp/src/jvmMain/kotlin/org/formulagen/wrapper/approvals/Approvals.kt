package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.formulagen.wrapper.bridge.ActivityLimits
import org.formulagen.wrapper.bridge.ChatLimits
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.chat.WorkPaths
import org.formulagen.wrapper.providers.copy
import org.formulagen.wrapper.util.shortened

/** What a provider asks to do, as the approval dialog names it. */
internal enum class ApprovalKind { EDIT, COMMAND, CONNECTOR, OTHER }

/**
 * What exactly a provider asks to do, with full native values: the dialog shows them as they are
 * (only the home folder written `~`), because the player approves exactly this. The page only ever
 * sees [ApprovalRequest.summary], with paths relative to the project folder.
 */
internal sealed interface ApprovalSubject {
    val kind: ApprovalKind

    /** Change [files]; [preview] is the change as the provider sent it (old and new text, or the new content's size). */
    data class Edit(val verb: String, val files: List<Path>, val preview: String) : ApprovalSubject {
        override val kind = ApprovalKind.EDIT
    }

    /** Run [command] verbatim, in [cwd] when the provider says; [needs] is a path it must reach beyond its folder. */
    data class Command(val command: String, val cwd: Path?, val needs: Path?, val reason: String?) : ApprovalSubject {
        override val kind = ApprovalKind.COMMAND
    }

    /** Call [tool] of the person's MCP server or connector [server] with [input]. */
    data class Connector(val server: String, val tool: String, val input: String) : ApprovalSubject {
        override val kind = ApprovalKind.CONNECTOR
    }

    /** Anything else the provider asks, by its own [name] and [description]. */
    data class Other(val name: String, val description: String) : ApprovalSubject {
        override val kind = ApprovalKind.OTHER
    }
}

/**
 * Whatever a working member's provider would ask the player in a terminal, for the native dialog.
 * [companionName] is the companion's name, [folder] its project folder. [sessionLabel] is the "for
 * this conversation" button exactly as the provider means the grant ("Allow all edits in game for
 * this conversation"), or null when the provider offers no such grant, and then the dialog has no
 * such button.
 */
internal data class ApprovalRequest(
    val companionName: String,
    val member: TeamMember,
    val folder: Path,
    val subject: ApprovalSubject,
    val sessionLabel: String?,
) {
    val kind: ApprovalKind get() = subject.kind

    /** What is asked, exactly, for the dialog: the command verbatim, the files in full. */
    val title: String get() = when (val it = subject) {
        is ApprovalSubject.Edit -> "${it.verb} ${it.files.joinToString(", ", transform = ::displayPath)}"
        is ApprovalSubject.Command -> it.command
        is ApprovalSubject.Connector -> "${it.server} · ${it.tool}"
        is ApprovalSubject.Other -> it.name
    }

    /** What more the provider said, for the dialog. */
    val detail: String get() = when (val it = subject) {
        is ApprovalSubject.Edit -> it.preview
        is ApprovalSubject.Command -> listOfNotNull(it.reason, it.cwd?.let { cwd -> "In ${displayPath(cwd)}" },
            it.needs?.let { path -> "Needs access to ${displayPath(path)}" }).joinToString("\n")
        is ApprovalSubject.Connector -> it.input
        is ApprovalSubject.Other -> it.description
    }.shortened(ApprovalLimits.PREVIEW)

    /**
     * What the page may show while the dialog is up ("Ember wants to run a command: npm test"), with
     * paths relative to the project folder and the home folder as `~`, bounded like a notice.
     */
    fun summary(): String {
        val paths = WorkPaths(folder)
        val what = when (val it = subject) {
            is ApprovalSubject.Edit -> "${it.verb} ${it.files.joinToString(", ") { file -> paths.label(file.toString()) }}"
            is ApprovalSubject.Command -> paths.scrub(it.command)
            is ApprovalSubject.Connector -> "${it.server} · ${it.tool}"
            is ApprovalSubject.Other -> it.name
        }
        return "$companionName wants to ${verb(kind)}: $what".shortened(ApprovalLimits.WAITING_SUMMARY)
    }
}

/** The person's answer. Anything but an explicit allow (a closed dialog, a timeout, a stopped reply) is [DENY]. */
internal enum class ApprovalDecision { ALLOW_ONCE, ALLOW_FOR_CONVERSATION, DENY }

/**
 * The seam between a working member's runtime and the native dialog. Blocks until the person
 * answers, [cancel] fires (the reply was stopped or the chat closed), or the dialog times out; the
 * last two are [ApprovalDecision.DENY]. [shown] runs when the dialog is on screen (a request may wait
 * behind another dialog first). Only native code can answer: the page never sees the dialog.
 */
internal fun interface ApprovalBroker {
    fun ask(request: ApprovalRequest, cancel: CancelSignal, shown: () -> Unit): ApprovalDecision
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
    override fun ask(request: ApprovalRequest, cancel: CancelSignal, shown: () -> Unit): ApprovalDecision =
        dialogs.ask(approvalDialog(request), cancel, shown) ?: ApprovalDecision.DENY
}

/**
 * The dialog for [request]: what is asked, exactly, by which member of which companion, in which
 * folder. Deny is the default button and what closing the dialog means. Every provider's text goes
 * through [visibleText], so a format character cannot reorder or hide what the player approves.
 */
internal fun approvalDialog(request: ApprovalRequest): NativeDialog<ApprovalDecision> = NativeDialog(
    title = visibleText("${request.companionName} wants to ${verb(request.kind)}"),
    lines = listOfNotNull(
        request.title,
        request.detail.takeIf { it.isNotBlank() },
        "Member: ${memberLabel(request.member)}",
        "Project folder: ${displayPath(request.folder)}",
    ).map(::visibleText),
    buttons = listOfNotNull("Allow once" to ApprovalDecision.ALLOW_ONCE,
        request.sessionLabel?.let { visibleText(it) to ApprovalDecision.ALLOW_FOR_CONVERSATION }, "Deny" to ApprovalDecision.DENY),
    dismiss = ApprovalDecision.DENY,
)

/**
 * [text] with every Unicode format character (category Cf: bidi overrides and isolates, zero-width
 * characters, the byte-order mark) written as its code point, such as `‮`. Shown as is, such a
 * character can reorder or hide the text around it in a native dialog.
 */
internal fun visibleText(text: String): String {
    if (text.codePoints().noneMatch(::isFormatCharacter)) return text
    return buildString {
        text.codePoints().forEach { if (isFormatCharacter(it)) append("\\u%04X".format(it)) else appendCodePoint(it) }
    }
}

/** Whether [codePoint] is a Unicode format character (general category Cf). */
internal fun isFormatCharacter(codePoint: Int): Boolean = Character.getType(codePoint) == Character.FORMAT.toInt()

/** "Claude · Opus", "Codex · gpt-6-luna", "Codex · default model": how a member is named to the player. */
internal fun memberLabel(member: TeamMember): String = "${member.runtimeId.copy.product} · ${member.runtimeId.copy.modelName(member.modelId)}"

/** A native-only path for the player to read, in full, with their home folder as `~`. */
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

/** Bounds of what approvals show: the page's from the contract, the dialog's own. */
internal object ApprovalLimits {
    /** The page's "waiting for your approval" summary is bounded like a notice (the contract's `waiting.summary`). */
    const val WAITING_SUMMARY = ChatLimits.NOTICE
    /** How much of a change or a provider's description the native dialog shows. Native only. */
    const val PREVIEW = 4_000
    /** A label the page sees in activity (the contract's `ACTIVITY_LIMITS.label`). */
    const val LABEL = ActivityLimits.LABEL
}
