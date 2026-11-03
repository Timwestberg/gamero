package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A project's workspace window on its Mac: terminal tabs and Hovel's tools in the project's working folder. A page
 * may only ask to open it, or the changes view for one of the project's threads, and names them only by record
 * ids; the Mac resolves every path, file list and command itself. Its own capability, so a host leaves it out of
 * `host.capabilities` until it is built. Desktop only.
 */
interface ProjectWorkspaceCapability {
    /** Open or bring forward the project's workspace window. */
    suspend fun open(scope: ProjectScope): ProjectWorkspaceResult
    /** Open the workspace on a thread's changes, from the Mac's own edit record. */
    suspend fun viewChanges(args: ProjectWorkspaceViewChangesArgs): ProjectWorkspaceResult
}

/** The thread whose changes to show, in its project. No file list: that is the Mac's own record. */
@Serializable
data class ProjectWorkspaceViewChangesArgs(val projectId: String, val threadId: String)

/** The Mac's answer to an open or a changes view. */
@Serializable
data class ProjectWorkspaceResult(val status: ProjectWorkspaceStatus)

/**
 * [OPENED] when the window is showing, [MISSING_FOLDER] when the working folder is gone on this Mac, and [UNAVAILABLE]
 * when this is not the project's Mac, the project is talk-only, or the feature is off.
 */
@Serializable
enum class ProjectWorkspaceStatus {
    @SerialName("opened") OPENED,
    @SerialName("missing-folder") MISSING_FOLDER,
    @SerialName("unavailable") UNAVAILABLE,
}

/** Mirrors event-contracts/host-bridge/project-workspace.ts. */
object ProjectWorkspaceMethods {
    val OPEN = BridgeMethod("projectWorkspace.open", ProjectScope.serializer(), ProjectWorkspaceResult.serializer()) {
        SharedRules.isRecordId(it.projectId)
    }
    val VIEW_CHANGES = BridgeMethod(
        "projectWorkspace.viewChanges", ProjectWorkspaceViewChangesArgs.serializer(), ProjectWorkspaceResult.serializer(),
    ) { SharedRules.isRecordId(it.projectId) && SharedRules.isRecordId(it.threadId) }

    fun pack(workspace: Capability<ProjectWorkspaceCapability>): CapabilityPack = binder.pack(workspace)

    internal val binder = CapabilityBinder("projectWorkspace", listOf<CapabilityHandler<ProjectWorkspaceCapability>>(
        OPEN.handle { workspace, scope -> workspace.open(scope) },
        VIEW_CHANGES.handle { workspace, args -> workspace.viewChanges(args) },
    ))
}
