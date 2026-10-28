package org.formulagen.wrapper.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.formulagen.wrapper.bridge.WorkspaceCapability
import org.formulagen.wrapper.bridge.WorkspaceFolder
import org.formulagen.wrapper.bridge.WorkspaceScope
import org.formulagen.wrapper.bridge.WorkspaceStatus

/**
 * `workspace.*` on the desktop, backed by [store]. The router has validated the scope; the store
 * checks it again before building any path. One picker at a time, off the bridge thread.
 */
internal class DesktopWorkspace(private val store: WorkspaceStore) : WorkspaceCapability {
    private val picker = Mutex()

    override suspend fun choose(scope: WorkspaceScope): WorkspaceStatus = picker.withLock {
        runInterruptible(Dispatchers.IO) { workspaceStatus(store.choose(scope.profile, scope.companionId)) }
    }

    override suspend fun clear(scope: WorkspaceScope): WorkspaceStatus =
        runInterruptible(Dispatchers.IO) { workspaceStatus(store.clear(scope.profile, scope.companionId)) }

    override suspend fun status(scope: WorkspaceScope): WorkspaceStatus =
        runInterruptible(Dispatchers.IO) { workspaceStatus(store.folder(scope.profile, scope.companionId)) }
}

/** What the page may see of [folder]: its name and whether it exists, never its path. */
internal fun workspaceStatus(folder: ProjectFolder?) = WorkspaceStatus(folder?.let { WorkspaceFolder(it.exists, it.name) })
