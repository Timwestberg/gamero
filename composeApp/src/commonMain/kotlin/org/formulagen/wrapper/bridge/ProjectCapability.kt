package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.util.takeWhole
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
    const val ACTIVITY_SUMMARY = 40
    const val APPROVAL_LINE = 4096
    const val APPROVAL_LINES = 64
    const val APPROVAL_SUMMARY = 512
    const val CONTEXT_FOLDERS = 4
    const val DELTA = 2048
    const val FOLDER_NAME = 80
    const val MESSAGE = 16_000
    const val QUEUED_MESSAGE_MS = 86_400_000L
    const val THREAD_OUTPUTS = 30
    const val THREAD_TITLE = 120
}

/** The one folder agents edit in, or a folder they may only read. */
@Serializable
enum class FolderRole {
    @SerialName("working") WORKING,
    @SerialName("context") CONTEXT,
}

/** A project this Mac runs, by its lower-case id. */
@Serializable
data class ProjectScope(val projectId: String)

@Serializable
data class ProjectChooseFolderArgs(val projectId: String, val role: FolderRole)

@Serializable
data class ProjectForgetFolderArgs(val projectId: String, val folderId: String)

/**
 * A folder's display name, never its path: one line of at most [CompanionLimits.FOLDER_NAME]
 * characters, with no whitespace at either end, no `/` and no leading `~`. Mirrors the contract's
 * folder name rule, and makes such a name from a folder's last path component.
 */
object ProjectFolderNames {
    /** The name a page sees for a folder. */
    fun isValid(name: String): Boolean = name.length in 1..CompanionLimits.FOLDER_NAME &&
        name.none { it.code < 0x20 || it.code == 0x7F } && name == name.trimEcma() && '/' !in name && !name.startsWith('~')

    /**
     * A valid display name for a folder called [basename], distinct, whatever the case, from every name
     * in [taken]: controls and `/` become spaces and `-`, a leading `~` goes, a long name is cut, and a
     * second "docs" becomes "docs (2)".
     */
    fun display(basename: String, taken: Collection<String> = emptyList()): String {
        var cleaned = basename.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else if (it == '/') '-' else it }
            .joinToString("").trimEcma()
        while (cleaned.startsWith('~')) cleaned = cleaned.drop(1).trimEcma()
        if (cleaned.isEmpty()) cleaned = FALLBACK
        val used = taken.mapTo(HashSet()) { it.lowercase() }
        var copy = 1
        while (true) {
            val suffix = if (copy == 1) "" else " ($copy)"
            val name = cut(cleaned, CompanionLimits.FOLDER_NAME - suffix.length) + suffix
            if (name.lowercase() !in used) return name
            copy += 1
        }
    }

    /** [text] in at most [limit] characters, never ending inside a surrogate pair or on whitespace. */
    private fun cut(text: String, limit: Int): String {
        if (text.length <= limit) return text
        return text.takeWhole(limit).trimEcma().ifEmpty { FALLBACK }
    }

    private const val FALLBACK = "folder"
}

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
        require(SharedRules.isRecordId(id))
        require(ProjectFolderNames.isValid(name))
        require(readableBy.toSet().size == readableBy.size)
        require(role == FolderRole.CONTEXT || readableBy.isEmpty())
    }
}

/**
 * The project's folders on this Mac: none for a talk-only project, else at most one working folder
 * and four context folders, with distinct ids and names that differ in more than case.
 */
@Serializable
data class ProjectFoldersStatus(val folders: List<ProjectFolderStatus>) {
    init {
        require(folders.count { it.role == FolderRole.WORKING } <= 1)
        require(folders.count { it.role == FolderRole.CONTEXT } <= CompanionLimits.CONTEXT_FOLDERS)
        require(folders.map { it.id }.toSet().size == folders.size)
        require(folders.map { it.name.lowercase() }.toSet().size == folders.size)
    }
}

/** Mirrors event-contracts/host-bridge/project.ts. */
object ProjectMethods {
    /** The person may browse for a while before choosing. */
    val CHOOSE_FOLDER = BridgeMethod(
        "project.chooseFolder", ProjectChooseFolderArgs.serializer(), ProjectFoldersStatus.serializer(), timeout = 10.minutes,
    ) { SharedRules.isRecordId(it.projectId) }
    val FORGET_FOLDER = BridgeMethod("project.forgetFolder", ProjectForgetFolderArgs.serializer(), ProjectFoldersStatus.serializer()) {
        SharedRules.isRecordId(it.projectId) && SharedRules.isRecordId(it.folderId)
    }
    val FOLDER_STATUS = BridgeMethod("project.folderStatus", ProjectScope.serializer(), ProjectFoldersStatus.serializer()) {
        SharedRules.isRecordId(it.projectId)
    }

    fun pack(project: Capability<ProjectCapability>): CapabilityPack = binder.pack(project)

    internal val binder = CapabilityBinder("project", listOf<CapabilityHandler<ProjectCapability>>(
        CHOOSE_FOLDER.handle { project, args -> project.chooseFolder(args) },
        FORGET_FOLDER.handle { project, args -> project.forgetFolder(args) },
        FOLDER_STATUS.handle { project, scope -> project.folderStatus(scope) },
    ))
}
