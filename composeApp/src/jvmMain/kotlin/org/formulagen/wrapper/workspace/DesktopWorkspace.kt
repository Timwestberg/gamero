package org.formulagen.wrapper.workspace

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.formulagen.wrapper.bridge.WorkspaceCapability
import org.formulagen.wrapper.bridge.WorkspaceFolder
import org.formulagen.wrapper.bridge.WorkspaceScope
import org.formulagen.wrapper.bridge.WorkspaceStatus

/**
 * `workspace.*` on the desktop, backed by [store]. The router has validated the scope; the store
 * checks it again before building any path. One folder picker at a time: a second Choose while one
 * is open answers the current status. The picker runs on its own thread and is never interrupted:
 * if the bridge call times out meanwhile, whatever the person chooses is still kept when it closes.
 */
internal class DesktopWorkspace(private val store: WorkspaceStore) : WorkspaceCapability {
    private val picking = AtomicBoolean()

    override suspend fun choose(scope: WorkspaceScope): WorkspaceStatus {
        if (!picking.compareAndSet(false, true)) return status(scope)
        val chosen = CompletableDeferred<WorkspaceStatus>()
        Thread.ofPlatform().daemon().name("hovel-folder-picker").start {
            try { chosen.complete(workspaceStatus(store.choose(scope.profile, scope.companionId))) }
            catch (error: Throwable) { chosen.completeExceptionally(error) }
            finally { picking.set(false) }
        }
        return chosen.await()
    }

    override suspend fun clear(scope: WorkspaceScope): WorkspaceStatus =
        runInterruptible(Dispatchers.IO) { workspaceStatus(store.clear(scope.profile, scope.companionId)) }

    override suspend fun status(scope: WorkspaceScope): WorkspaceStatus =
        runInterruptible(Dispatchers.IO) { workspaceStatus(store.folder(scope.profile, scope.companionId)) }
}

/** What the page may see of [folder]: its name and whether it exists, never its path. */
internal fun workspaceStatus(folder: ProjectFolder?) = WorkspaceStatus(folder?.let { WorkspaceFolder(it.exists, it.name) })
