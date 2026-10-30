package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.formulagen.wrapper.conversation.TeamMember

/**
 * The native confirmation before a role's first working message in a conversation:
 * "<companion> wants to start working in <folder> with <member>." Start working is remembered for
 * exactly what the dialog named, (conversation, project, folder, member), so a changed folder or
 * another member asks again. Not now, closing the dialog, a timeout or a cancel is never remembered.
 */
internal class StartOfWork {
    private data class Confirmed(val lease: String, val companionId: String, val folder: Path, val member: TeamMember)
    private val confirmed = ConcurrentHashMap.newKeySet<Confirmed>()

    /** Whether this conversation already confirmed [member] working in [folder], without asking. */
    fun isConfirmed(lease: String, companionId: String, folder: Path, member: TeamMember): Boolean =
        Confirmed(lease, companionId, folder, member) in confirmed

    fun remember(lease: String, companionId: String, folder: Path, member: TeamMember) { confirmed += Confirmed(lease, companionId, folder, member) }
}

/** Not now is the default: Escape, Enter and closing the dialog choose it. */
internal fun startOfWorkDialog(companionName: String, folder: Path, member: TeamMember): NativeDialog<Boolean> = NativeDialog(
    title = "$companionName wants to start working in ${folder.fileName ?: folder} with ${memberLabel(member)}.",
    lines = listOf(
        "Project folder: ${displayPath(folder)}",
        "${memberLabel(member)} works with your own setup and asks before anything your settings do not allow.",
    ),
    buttons = listOf("Start working" to true, "Not now" to false),
    dismiss = false,
)
