package org.formulagen.wrapper.conversations

import java.nio.file.Path
import org.formulagen.wrapper.approvals.ApprovalKind as RequestKind
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.bridge.Activity
import org.formulagen.wrapper.bridge.ActivityLimits
import org.formulagen.wrapper.bridge.ActivityStep
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.chat.wholePath
import org.formulagen.wrapper.util.shortened

/**
 * Text that may leave the Mac, as event-contracts `shareableText` checks it: no C0, C1 or
 * bidirectional controls, and no absolute path (`/` or `~` at the start or after whitespace, a quote
 * or `=`, `$HOME`, or a `file://` URL). Whitespace here is JavaScript's `\s` and more, so the Mac is
 * never laxer than the server.
 */
internal object ShareableText {
    private val CONTROL_OR_BIDI = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
    private val ABSOLUTE_PATH = Regex(
        "(?:^|[\\t\\n\\u000b\\f\\r \\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff\"'`=])[/~]|\\$\\{?HOME\\b|file://",
        RegexOption.IGNORE_CASE,
    )

    /** Whether [text] may leave the Mac within [max] characters (and at least [min]). */
    fun isShareable(text: String, max: Int, min: Int = 1): Boolean =
        text.length in min..max && !CONTROL_OR_BIDI.containsMatchIn(text) && !ABSOLUTE_PATH.containsMatchIn(text)
}

/**
 * A project's folders as the phone names them: a path inside the working folder becomes `./…` (the
 * folder itself `.`), one inside a context folder `[name]/…`, and anything else stays unnamed.
 * Each folder is matched as given and as the file system resolves it (`/tmp` is `/private/tmp`), and
 * in free text also in the `~/…` form runtimes write a home path in.
 */
internal class FolderLabels(folders: List<LocalFolder>, home: Path? = System.getProperty("user.home")?.let(Path::of)) {
    private val bases: List<Pair<Path, String>> = folders.flatMap { folder ->
        val label = if (folder.role == FolderRole.WORKING) "." else "[${folder.name}]"
        listOfNotNull(folder.path.toAbsolutePath().normalize(), runCatching { folder.path.toRealPath() }.getOrNull()).distinct().map { it to label }
    }.sortedByDescending { it.first.nameCount }

    /** Every written form of each folder, longest first. */
    private val forms: List<Pair<String, String>> = bases.flatMap { (base, label) ->
        val tilde = home?.takeIf { base.startsWith(it) && base != it }?.let { "~/" + it.relativize(base) }
        listOfNotNull(base.toString() to label, tilde?.let { it to label })
    }.sortedByDescending { it.first.length }

    /** [path] relative to the folder it is in, or null when it is in none. */
    fun label(path: Path): String? {
        val absolute = path.toAbsolutePath().normalize()
        for ((base, label) in bases) if (absolute.startsWith(base)) {
            val rest = base.relativize(absolute).toString()
            return if (rest.isEmpty()) label else "$label/$rest"
        }
        return null
    }

    /** Free text with every folder path written as its label, longest first and only where a whole path ends. */
    fun scrub(text: String): String = forms.fold(text) { result, (form, label) -> wholePath(form).replace(result, Regex.escapeReplacement(label)) }
}

/**
 * What a working agent's permission request shows away from the Mac: the exact action with its paths
 * relative to the project's [folders] (`./src/app.ts`, `[design-docs]/plan.md`), or `mac-only` when
 * it names anything outside them or cannot be shown as shareable text. An edit lists its files, not
 * its diff; a command shows the command, where it runs and what it needs.
 */
internal fun presentApproval(request: ApprovalRequest, folders: List<LocalFolder>): ApprovalPresentation {
    val labels = FolderLabels(folders)
    val macOnly = macOnly(request.companionName, verb(request.kind))
    fun inside(path: Path) = labels.label(path)
    val lines = when (val subject = request.subject) {
        is ApprovalSubject.Edit -> listOf("${subject.verb} " + subject.files.map { inside(it) ?: return macOnly }.joinToString(", "))
        is ApprovalSubject.Command -> buildList {
            addAll(labels.scrub(subject.command).lines())
            subject.cwd?.let { add("In ${inside(it) ?: return macOnly}") }
            subject.needs?.let { add("Needs access to ${inside(it) ?: return macOnly}") }
            subject.reason?.let { addAll(labels.scrub(it).lines()) }
        }
        is ApprovalSubject.Connector -> listOf("${subject.server} · ${subject.tool}") + labels.scrub(subject.input).lines()
        is ApprovalSubject.Other -> listOf(subject.name) + labels.scrub(subject.description).lines()
    }
    return anywhere(lines) ?: macOnly
}

/** A native dialog of the engine's own (start of work, a stage, the resume review) as the phone shows it. */
internal fun presentDialog(dialog: NativeDialog<*>, folders: List<LocalFolder>, companionName: String): ApprovalPresentation {
    val labels = FolderLabels(folders)
    return anywhere((listOf(dialog.title) + dialog.lines).flatMap { labels.scrub(it).lines() })
        ?: macOnly(companionName, "continue its work")
}

/** [lines] when every one may leave the Mac and they fit the contract; null otherwise. */
private fun anywhere(lines: List<String>): ApprovalPresentation.Anywhere? {
    val kept = lines.map { it.trimEnd('\r') }.filter { it.isNotBlank() }
    if (kept.isEmpty() || kept.size > CompanionLimits.APPROVAL_LINES) return null
    if (!kept.all { ShareableText.isShareable(it, CompanionLimits.APPROVAL_LINE) }) return null
    return ApprovalPresentation.Anywhere(kept)
}

/** "Ember wants to run a command", or without the name when the name itself could not leave the Mac. */
private fun macOnly(companionName: String, verb: String): ApprovalPresentation.MacOnly {
    val named = "$companionName wants to $verb. Answer it on the Mac.".shortened(CompanionLimits.APPROVAL_SUMMARY)
    return ApprovalPresentation.MacOnly(if (ShareableText.isShareable(named, CompanionLimits.APPROVAL_SUMMARY)) named
        else "An agent wants to $verb. Answer it on the Mac.")
}

private fun verb(kind: RequestKind) = when (kind) {
    RequestKind.EDIT -> "edit files"
    RequestKind.COMMAND -> "run a command"
    RequestKind.CONNECTOR -> "use a connector"
    RequestKind.OTHER -> "use a tool"
}

/**
 * A thread's activity as it may leave the Mac: the latest `COMPANION_LIMITS.activitySummary` steps,
 * each label and detail written relative to the project's [folders]; a label that still is not
 * shareable is replaced, and such a detail dropped.
 */
internal fun shareableActivity(activity: Activity?, folders: List<LocalFolder>): List<ActivityStep> {
    val labels = FolderLabels(folders)
    return activity?.steps.orEmpty().takeLast(CompanionLimits.ACTIVITY_SUMMARY).map { step ->
        val label = labels.scrub(step.label).takeIf { ShareableText.isShareable(it, ActivityLimits.LABEL, 0) } ?: OUTSIDE_FOLDERS
        val detail = step.detail?.let(labels::scrub)?.takeIf { ShareableText.isShareable(it, ActivityLimits.DETAIL) }
        step.copy(label = label, detail = detail)
    }
}

/** What an activity line says when it names something outside the project's folders. */
internal const val OUTSIDE_FOLDERS = "Outside the project folders"
