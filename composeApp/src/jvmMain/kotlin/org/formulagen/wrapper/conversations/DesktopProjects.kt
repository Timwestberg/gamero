package org.formulagen.wrapper.conversations

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.formulagen.wrapper.bridge.FolderRole
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
 * interrupted, so a choice made after the bridge call timed out is still kept.
 */
internal class DesktopProjects(
    private val store: ProjectFolderStore,
    private val account: () -> String?,
    /** The runtimes this Mac lets read context folders: those whose `ContextFolderSupport` is readable. */
    private val readableBy: () -> List<RuntimeId>,
    private val changed: (accountId: String, projectId: String) -> Unit,
) : ProjectCapability {
    private val picking = AtomicBoolean()

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
        check(!store.isForeign(accountId, scope.projectId)) { "This project runs on another computer" }
        status(store.folders(accountId, scope.projectId))
    }

    private fun registered() = checkNotNull(account()) { "Connect this Mac to Hovel first" }

    private fun status(folders: List<LocalFolder>) = ProjectFoldersStatus(folders.map { folder ->
        ProjectFolderStatus(folder.exists, folder.id, folder.name, if (folder.role == FolderRole.CONTEXT) readableBy() else emptyList(), folder.role)
    })
}

/**
 * Records each project's folders in the account (`PUT /projects/:id/folders`), against the revision
 * this Mac last saw. The native routes have no project read, so a revision comes from a claim or an
 * earlier save; until one is known, or after a conflict, the folders wait and [publishPending] tries
 * again. A refusal other than a conflict (403, 404) means the project runs elsewhere.
 */
internal class ProjectFolderPublisher(
    private val store: ProjectFolderStore,
    private val api: ConversationApi,
    private val accountId: String,
    private val readableBy: () -> List<RuntimeId>,
) {
    /** Publish [projectId]'s folders now, if its revision is known. Failures leave them pending. */
    @Synchronized fun publish(projectId: String) {
        val revision = store.unpublished(accountId)[projectId] ?: return
        val folders = wireFolders(store.folders(accountId, projectId))
        try {
            val saved = api.saveFolders(projectId, ProjectFoldersSave(revision, folders))
            store.published(accountId, projectId, folders, saved?.revision ?: (revision + 1))
        } catch (_: HovelRejectedException) {
            // A stale revision: the next claim of this project brings the current one.
        } catch (error: HovelStatusException) {
            if (error.status == 403 || error.status == 404) store.foreign(accountId, projectId)
        } catch (_: Exception) {
            // Offline or uncertain: the save is idempotent, so it is simply tried again.
        }
    }

    /** Try every project whose folders the account does not have yet. */
    fun publishPending() = store.unpublished(accountId).keys.forEach(::publish)

    /** [folders] as the account records them: display data only. */
    fun wireFolders(folders: List<LocalFolder>) = folders.map { folder ->
        WireProjectFolder(folder.id, folder.name, if (folder.role == FolderRole.CONTEXT) readableBy() else emptyList(), folder.role)
    }
}
