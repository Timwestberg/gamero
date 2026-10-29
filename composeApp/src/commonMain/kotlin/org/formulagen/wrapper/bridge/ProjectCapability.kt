package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes

/**
 * A companion project's folders on the project's own Mac. Native code owns the paths: a page never
 * supplies one and never sees one. Every method answers the folders' status, and native code records
 * the change in the account. Desktop only; the conversation runner brings the implementation.
 */
interface ProjectCapability {
    /** Opens the Mac's folder picker natively; the chosen folder joins the status, which is unchanged when cancelled. */
    suspend fun chooseFolder(args: ProjectChooseFolderArgs): ProjectFoldersStatus
    /** Forgets the folder. It does not touch the folder or its files. */
    suspend fun forgetFolder(args: ProjectForgetFolderArgs): ProjectFoldersStatus
    suspend fun folderStatus(scope: ProjectScope): ProjectFoldersStatus
}

/** The companion contract's bounds that native code mirrors so far. */
object CompanionLimits { // mirrors COMPANION_LIMITS (limits.json)
    const val CONTEXT_FOLDERS = 4
    const val FOLDER_NAME = 80
}

/** The one folder agents edit in, or a folder they may only read. */
@Serializable
enum class FolderRole {
    @SerialName("working") WORKING,
    @SerialName("context") CONTEXT,
}

/** A project this Mac runs. */
@Serializable
data class ProjectScope(val projectId: String)

@Serializable
data class ProjectChooseFolderArgs(val projectId: String, val role: FolderRole)

@Serializable
data class ProjectForgetFolderArgs(val projectId: String, val folderId: String)

/**
 * One of the project's folders as this Mac has it: its display [name], never its path, its [role],
 * the runtimes that enforce it read-only ([readableBy], none for the working folder), and whether it
 * still [exists].
 */
@Serializable
data class ProjectFolderStatus(
    val exists: Boolean,
    val id: String,
    val name: String,
    val readableBy: List<RuntimeId>,
    val role: FolderRole,
) {
    init {
        require(name.length in 1..CompanionLimits.FOLDER_NAME)
        require(role == FolderRole.CONTEXT || readableBy.isEmpty())
    }
}

/** The project's folders on this Mac: none for a talk-only project, else one working and up to four context folders. */
@Serializable
data class ProjectFoldersStatus(val folders: List<ProjectFolderStatus>) {
    init {
        require(folders.count { it.role == FolderRole.WORKING } <= 1)
        require(folders.count { it.role == FolderRole.CONTEXT } <= CompanionLimits.CONTEXT_FOLDERS)
    }
}

/** Mirrors event-contracts/host-bridge/project.ts. */
object ProjectMethods {
    private fun uuid(value: String) = BridgeProtocol.UUID_PATTERN.matches(value)

    /** The person may browse for a while before choosing. */
    val CHOOSE_FOLDER = BridgeMethod(
        "project.chooseFolder", ProjectChooseFolderArgs.serializer(), ProjectFoldersStatus.serializer(), timeout = 10.minutes,
    ) { uuid(it.projectId) }
    val FORGET_FOLDER = BridgeMethod("project.forgetFolder", ProjectForgetFolderArgs.serializer(), ProjectFoldersStatus.serializer()) {
        uuid(it.projectId) && uuid(it.folderId)
    }
    val FOLDER_STATUS = BridgeMethod("project.folderStatus", ProjectScope.serializer(), ProjectFoldersStatus.serializer()) {
        uuid(it.projectId)
    }

    fun pack(project: Capability<ProjectCapability>): CapabilityPack = binder.pack(project)

    internal val binder = CapabilityBinder("project", listOf<CapabilityHandler<ProjectCapability>>(
        CHOOSE_FOLDER.handle { project, args -> project.chooseFolder(args) },
        FORGET_FOLDER.handle { project, args -> project.forgetFolder(args) },
        FOLDER_STATUS.handle { project, scope -> project.folderStatus(scope) },
    ))
}
