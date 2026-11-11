package org.formulagen.wrapper.approvals

import java.nio.file.Path
import java.util.Collections
import org.formulagen.wrapper.conversation.TeamMember

/**
 * The native confirmation before a role's first working message in a conversation:
 * "<companion> wants to start working in <folder> with <member>." Start working is remembered for
 * exactly what the dialog named, (conversation, project, folder, member), so a changed folder or
 * another member asks again. Not now, closing the dialog, a timeout or a cancel is never remembered.
 *
 * The same holds, as its own approval ([ApprovedFor.READ]), before the coordinator is first read files of the folder
 * ([readFilesDialog]): a yes to one is never a yes to the other.
 *
 * With a [memory] the answer outlives the app: a restart does not ask again. Without one it lasts until the app
 * quits, which is what a test wants.
 */
internal class StartOfWork(private val memory: ApprovalMemory? = null) {
    /** In the order given, so a list of them is oldest first. */
    private val confirmed = Collections.synchronizedSet(LinkedHashSet<Approved>())

    /** Whether this conversation already confirmed [member] in [folder] for [purpose], without asking. */
    fun isConfirmed(conversationId: String, projectId: String, folder: Path, member: TeamMember, purpose: ApprovedFor = ApprovedFor.WORK): Boolean {
        val asked = approved(conversationId, projectId, folder, member, purpose)
        return asked in confirmed || memory?.has(asked) == true
    }

    /** The person said yes: this conversation does not ask again for exactly this. */
    fun remember(conversationId: String, projectId: String, folder: Path, member: TeamMember, purpose: ApprovedFor = ApprovedFor.WORK) {
        val given = approved(conversationId, projectId, folder, member, purpose)
        confirmed += given
        memory?.remember(given)
    }

    /** Every approval kept for [projectId], oldest first. */
    fun kept(projectId: String): List<Approved> =
        (memory?.all().orEmpty() + synchronized(confirmed) { confirmed.toList() }).distinct().filter { it.projectId == projectId }

    /** Takes back the approval of [projectId] that [id] names ([Approved.id]): the next time, the person is asked. */
    fun withdraw(projectId: String, id: String) {
        kept(projectId).filter { it.id == id }.forEach { approved ->
            confirmed -= approved
            memory?.withdraw(approved)
        }
    }

    private fun approved(conversationId: String, projectId: String, folder: Path, member: TeamMember, purpose: ApprovedFor) =
        Approved(conversationId, projectId, folder.toString(), member.runtimeId, member.modelId, purpose)
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

/**
 * Before the coordinator is first read files of the project's folder in a conversation. It says what leaves the Mac:
 * the text of the files it asks for goes to [member]'s provider. Not now is the default.
 */
internal fun readFilesDialog(companionName: String, folder: Path, member: TeamMember): NativeDialog<Boolean> = NativeDialog(
    title = "$companionName wants to read files in ${folder.fileName ?: folder} with ${memberLabel(member)}.",
    lines = listOf(
        "Project folder: ${displayPath(folder)}",
        "Hovel reads the files it asks for and sends their text to ${memberLabel(member)}. It changes no file.",
        "Keys, environment files and build folders are never read.",
    ),
    buttons = listOf("Read files" to true, "Not now" to false),
    dismiss = false,
)
