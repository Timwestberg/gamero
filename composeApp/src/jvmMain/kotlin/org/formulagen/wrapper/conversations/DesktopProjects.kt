package org.formulagen.wrapper.conversations

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.formulagen.wrapper.approvals.ApprovedFor
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.ProjectApproval
import org.formulagen.wrapper.bridge.ProjectApprovals
import org.formulagen.wrapper.bridge.ProjectConversationArgs
import org.formulagen.wrapper.bridge.ProjectOutputsFolder
import org.formulagen.wrapper.bridge.ProjectShowOutputsResult
import org.formulagen.wrapper.bridge.ProjectWithdrawApprovalArgs
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.bridge.ProjectOpenOutputArgs
import org.formulagen.wrapper.bridge.ProjectOpenOutputResult
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.bridge.ProjectImageArgs
import org.formulagen.wrapper.bridge.ProjectImageResult
import org.formulagen.wrapper.bridge.ProjectCapability
import org.formulagen.wrapper.bridge.ProjectChooseFolderArgs
import org.formulagen.wrapper.bridge.ProjectFolderStatus
import org.formulagen.wrapper.bridge.ProjectFoldersStatus
import org.formulagen.wrapper.bridge.ProjectForgetFolderArgs
import org.formulagen.wrapper.bridge.ProjectScope
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException

/**
 * `project.*` on the desktop: a project's folders on this Mac, chosen with the native picker for the
 * registered account ([account]; none before this Mac is connected, and then every call fails). The
 * page sees each folder's display name, role, whether it exists and which runtimes may read it,
 * never a path. Each change is published to the account ([changed]). One picker at a time: a second
 * Choose while one is open answers the current status. The picker runs on its own thread and is never
 * interrupted, so a choice made after the bridge call timed out is still kept. [folderStatus] answers only
 * for a project of this Mac's ([owns]), which is how a page knows it runs on the project's own Mac.
 */
internal class DesktopProjects(
    private val store: ProjectFolderStore,
    private val account: () -> String?,
    /** The runtimes this Mac lets read context folders: those whose `ContextFolderSupport` is readable. */
    private val readableBy: () -> List<RuntimeId>,
    private val changed: (accountId: String, projectId: String) -> Unit,
    /** Whether the account says the project runs on this Mac; null when it cannot be asked now. */
    private val owns: (accountId: String, projectId: String) -> Boolean? = { _, _ -> null },
    private val output: (String, ProjectOpenOutputArgs) -> ProjectOpenOutputResult = { _, _ -> ProjectOpenOutputResult("unavailable") },
    /** The folders the person opens in Finder. */
    private val visible: CompanionFolders = CompanionFolders.NONE,
    /** The approvals the connected account's runner keeps; null while this Mac is not connected. */
    private val kept: (accountId: String) -> StartOfWork? = { null },
) : ProjectCapability {
    override suspend fun outputsFolder(args: ProjectConversationArgs): ProjectOutputsFolder = runInterruptible(Dispatchers.IO) {
        val accountId = ours(args.projectId)
        ProjectOutputsFolder(when (visible.state(accountId, args.conversationId)) {
            FolderState.NONE -> ProjectOutputsFolder.NONE
            FolderState.READY -> ProjectOutputsFolder.READY
            FolderState.BEHIND -> ProjectOutputsFolder.BEHIND
        })
    }

    override suspend fun showOutputs(args: ProjectConversationArgs): ProjectShowOutputsResult = runInterruptible(Dispatchers.IO) {
        val accountId = ours(args.projectId)
        ProjectShowOutputsResult(if (visible.show(accountId, args.conversationId)) "shown" else "unavailable")
    }

    override suspend fun approvals(scope: ProjectScope): ProjectApprovals = runInterruptible(Dispatchers.IO) {
        approvalsOf(ours(scope.projectId), scope.projectId)
    }

    override suspend fun withdrawApproval(args: ProjectWithdrawApprovalArgs): ProjectApprovals = runInterruptible(Dispatchers.IO) {
        val accountId = ours(args.projectId)
        kept(accountId)?.withdraw(args.projectId, args.approvalId)
        approvalsOf(accountId, args.projectId)
    }

    /** The approvals kept for [projectId], oldest first, as the page sees them: no folder, the newest when there are too many. */
    private fun approvalsOf(accountId: String, projectId: String) = ProjectApprovals(kept(accountId)?.kept(projectId).orEmpty()
        .takeLast(ProjectApproval.LIMIT).map { approved ->
            ProjectApproval(approved.conversationId, approved.id, TeamMember(approved.runtimeId, approved.modelId),
                if (approved.purpose == ApprovedFor.READ) "read" else "work")
        })

    /** The connected account, once it says [projectId] runs on this Mac. */
    private fun ours(projectId: String): String = registered().also { accountId ->
        check(owns(accountId, projectId) == true) { "Connect to verify this project belongs to this Mac" }
    }

    override suspend fun openOutput(args: ProjectOpenOutputArgs): ProjectOpenOutputResult = runInterruptible(Dispatchers.IO) {
        val accountId = registered()
        check(owns(accountId, args.projectId) == true) { "Connect to verify this project belongs to this Mac" }
        output(accountId, args)
    }

    private val picking = AtomicBoolean()

    override suspend fun image(args: ProjectImageArgs): ProjectImageResult = runInterruptible(Dispatchers.IO) {
        val accountId = registered()
        check(owns(accountId, args.projectId) == true) { "Connect to verify this project belongs to this Mac" }
        val root = store.folders(accountId, args.projectId).singleOrNull { it.role == FolderRole.WORKING }?.path
            ?: error("This project has no working folder")
        readProjectImage(root, args.path)
    }


    override suspend fun chooseFolder(args: ProjectChooseFolderArgs): ProjectFoldersStatus {
        val accountId = registered()
        if (!picking.compareAndSet(false, true)) return folderStatus(ProjectScope(args.projectId))
        val chosen = CompletableDeferred<ProjectFoldersStatus>()
        Thread.ofPlatform().daemon().name("hovel-project-picker").start {
            try {
                val before = store.folders(accountId, args.projectId).map { it.id }
                val after = store.choose(accountId, args.projectId, args.role)
                if (after.map { it.id } != before) changed(accountId, args.projectId)
                chosen.complete(status(after))
            } catch (error: Throwable) { chosen.completeExceptionally(error) }
            finally { picking.set(false) }
        }
        return chosen.await()
    }

    override suspend fun forgetFolder(args: ProjectForgetFolderArgs): ProjectFoldersStatus = runInterruptible(Dispatchers.IO) {
        val accountId = registered()
        val before = store.folders(accountId, args.projectId).map { it.id }
        val after = store.forget(accountId, args.projectId, args.folderId)
        if (after.map { it.id } != before) changed(accountId, args.projectId)
        status(after)
    }

    override suspend fun folderStatus(scope: ProjectScope): ProjectFoldersStatus = runInterruptible(Dispatchers.IO) {
        val accountId = registered()
        // The account's word, else what this Mac last learned.
        val ours = owns(accountId, scope.projectId) ?: !store.isForeign(accountId, scope.projectId)
        check(ours) { "This project runs on another computer" }
        status(store.folders(accountId, scope.projectId))
    }

    private fun registered() = checkNotNull(account()) { "Connect this Mac to Hovel first" }

    private fun status(folders: List<LocalFolder>) = ProjectFoldersStatus(folders.map { folder ->
        ProjectFolderStatus(folder.exists, folder.id, folder.name, if (folder.role == FolderRole.CONTEXT) readableBy() else emptyList(), folder.role)
    })
}

/**
 * Records each project's folders in the account (`PUT /projects/:id/folders`), against the project's revision:
 * the one this Mac last saw (from a claim, a read or its own save), else read now (`GET /projects/:id`), so a
 * project that never had a message still gets its folders. After a conflict the project is read again. A project
 * whose computer is another, or that the server refuses for good (403, 404), runs elsewhere.
 */
internal class ProjectFolderPublisher(
    private val store: ProjectFolderStore,
    private val api: ConversationApi,
    private val accountId: String,
    private val hostId: String,
    private val readableBy: () -> List<RuntimeId>,
) {
    /** Publish [projectId]'s folders now, when they changed. Failures leave them pending. */
    @Synchronized fun publish(projectId: String) {
        if (projectId !in store.unpublished(accountId)) return
        try {
            val revision = store.unpublished(accountId)[projectId] ?: read(projectId)?.revision ?: return
            val folders = wireFolders(store.folders(accountId, projectId))
            val saved = try { api.saveFolders(projectId, ProjectFoldersSave(revision, folders)) } catch (_: HovelRejectedException) {
                // A stale revision: read the project, and the next round saves against its revision.
                read(projectId)
                return
            }
            store.published(accountId, projectId, folders, saved?.revision ?: (revision + 1))
        } catch (error: HovelStatusException) {
            if (error.final) store.foreign(accountId, projectId)
        } catch (_: Exception) {
            // Offline or uncertain: the save is idempotent, so it is simply tried again.
        }
    }

    /** `project.changed`: read the project, and publish its folders when the account's differ from this Mac's. */
    @Synchronized fun refresh(projectId: String) {
        try {
            read(projectId) ?: return
            publish(projectId)
        } catch (_: Exception) {
        }
    }

    /** Try every project whose folders the account does not have yet. */
    fun publishPending() = store.unpublished(accountId).keys.forEach(::publish)

    /** [folders] as the account records them: display data only. */
    fun wireFolders(folders: List<LocalFolder>) = folders.map { folder ->
        WireProjectFolder(folder.id, folder.name, if (folder.role == FolderRole.CONTEXT) readableBy() else emptyList(), folder.role)
    }

    /** The project as the account has it, remembered unless a newer answer was; null when it runs on another computer. */
    private fun read(projectId: String): CompanionProject? {
        val request = store.requestSequence(accountId, projectId)
        return try {
            val project = api.project(projectId).takeIf { it.computerId == hostId }
            store.answered(accountId, projectId, project, request)
            project
        } catch (error: HovelStatusException) {
            if (error.final) store.answered(accountId, projectId, null, request)
            null
        }
    }
}
