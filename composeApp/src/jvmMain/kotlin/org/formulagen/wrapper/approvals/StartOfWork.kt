package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.util.shortened

/**
 * The native confirmation before a member's first working message in a conversation:
 * "<companion> wants to start working in <folder> with <member>." Start working is remembered for
 * exactly what the dialog named, (chat lease, companion, folder, member), so a changed folder or
 * another member asks again. The chat [remember]s it only while its view is still current; Not
 * now, closing the dialog, a timeout or a cancel is never remembered. [forget] ends a lease's
 * confirmations when its chat closes.
 */
internal class StartOfWork(private val dialogs: NativeDialogs) {
    private data class Confirmed(val lease: String, val companionId: String, val folder: Path, val member: TeamMember)
    private val confirmed = ConcurrentHashMap.newKeySet<Confirmed>()

    /** Whether this conversation already confirmed [member] working in [folder], without asking. */
    fun isConfirmed(lease: String, companionId: String, folder: Path, member: TeamMember): Boolean =
        Confirmed(lease, companionId, folder, member) in confirmed

    /** Ask the player; true for Start working. Blocks while the dialog is up. Remembers nothing. */
    fun ask(companionName: String, folder: Path, member: TeamMember, cancel: CancelSignal): Boolean =
        dialogs.ask(startOfWorkDialog(companionName, folder, member), cancel) == true

    fun remember(lease: String, companionId: String, folder: Path, member: TeamMember) { confirmed += Confirmed(lease, companionId, folder, member) }

    /** The chat lease closed or was replaced: its conversation's confirmations end with it. */
    fun forget(lease: String) { confirmed.removeIf { it.lease == lease } }
}

/** What the page may show while the start-of-work dialog is up, bounded like a notice. */
internal fun startOfWorkSummary(companionName: String, folder: Path, member: TeamMember): String =
    "$companionName wants to start working in ${folder.fileName ?: folder} with ${memberLabel(member)}.".shortened(ApprovalLimits.WAITING_SUMMARY)

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
