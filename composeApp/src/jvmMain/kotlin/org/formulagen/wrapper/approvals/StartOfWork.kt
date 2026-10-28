package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.formulagen.wrapper.bridge.TeamMember

/**
 * The native confirmation before a companion's first working message in a conversation:
 * "<companion> wants to start working in <folder> with <member>." Start working is remembered for
 * the conversation, keyed by (chat lease, companion) and the folder it named, so a changed folder
 * asks again; Not now, closing the dialog, a timeout or a cancel is not remembered, and the next
 * working message asks again. [forget] ends a lease's confirmations when its chat closes.
 */
internal class StartOfWork(private val dialogs: NativeDialogs) {
    private val confirmed = ConcurrentHashMap<Pair<String, String>, Path>()

    /** True once the player chose Start working for this lease, companion and folder. Blocks while the dialog is up. */
    fun confirm(lease: String, companionId: String, companion: String, folder: Path, member: TeamMember, cancel: CancelSignal): Boolean {
        if (isConfirmed(lease, companionId, folder)) return true
        val started = dialogs.ask(startOfWorkDialog(companion, folder, member), cancel) == 0
        if (started) confirmed[lease to companionId] = folder
        return started
    }

    /** Whether this conversation already confirmed working in [folder], without asking. */
    fun isConfirmed(lease: String, companionId: String, folder: Path): Boolean = confirmed[lease to companionId] == folder

    /** The chat lease closed: its conversation's confirmations end with it. */
    fun forget(lease: String) { confirmed.keys.removeIf { it.first == lease } }
}

/** What the page may show while the start-of-work dialog is up, at most [WAITING_SUMMARY_LIMIT] characters. */
internal fun startOfWorkSummary(companion: String, folder: Path, member: TeamMember): String =
    "$companion wants to start working in ${folder.fileName ?: folder} with ${memberLabel(member)}.".shortened(WAITING_SUMMARY_LIMIT)

internal fun startOfWorkDialog(companion: String, folder: Path, member: TeamMember): NativeDialog = NativeDialog(
    title = "$companion wants to start working in ${folder.fileName ?: folder} with ${memberLabel(member)}.",
    lines = listOf(
        "Project folder: ${displayPath(folder)}",
        "${memberLabel(member)} works with your own setup and asks before anything your settings do not allow.",
    ),
    buttons = listOf("Start working", "Not now"),
    dismissIndex = 1,
)
