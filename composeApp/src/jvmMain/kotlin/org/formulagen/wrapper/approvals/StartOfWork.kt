package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.formulagen.wrapper.conversation.TeamMember

/**
 * The native confirmation before a role's first working message in a conversation:
 * "<companion> wants to start working in <folder> with <member>." Start working is remembered for
 * exactly what the dialog named, (conversation, project, folder, member), so a changed folder or
 * another member asks again. Not now, closing the dialog, a timeout or a cancel is never remembered.
 *
 * With a [memory] the answer outlives the app: a restart does not ask again. Without one it lasts until the app
 * quits, which is what a test wants.
 */
internal class StartOfWork(private val memory: ApprovalMemory? = null) {
    private val confirmed = ConcurrentHashMap.newKeySet<Approved>()

    /** Whether this conversation already confirmed [member] working in [folder], without asking. */
    fun isConfirmed(conversationId: String, projectId: String, folder: Path, member: TeamMember): Boolean {
        val asked = approved(conversationId, projectId, folder, member)
        return asked in confirmed || memory?.has(asked) == true
    }

    /** The person chose Start working: this conversation does not ask again for exactly this. */
    fun remember(conversationId: String, projectId: String, folder: Path, member: TeamMember) {
        val given = approved(conversationId, projectId, folder, member)
        confirmed += given
        memory?.remember(given)
    }

    private fun approved(conversationId: String, projectId: String, folder: Path, member: TeamMember) =
        Approved(conversationId, projectId, folder.toString(), member.runtimeId, member.modelId, ApprovedFor.WORK)
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
