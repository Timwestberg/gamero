package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.util.takeWhole
import kotlin.time.Duration.Companion.minutes

/**
 * A companion project's folders and image reads on its own Mac. Native code owns absolute paths;
 * folder selection stays native. Image requests accept only project-relative paths and return
 * bounded bytes without persisting a copy. Desktop only; the conversation runner implements this.
 */
interface ProjectCapability {
    /** Open a locally recorded provider session, never a command supplied by the page. */
    suspend fun openOutput(args: ProjectOpenOutputArgs): ProjectOpenOutputResult = ProjectOpenOutputResult("unavailable")
    /** Read an explicitly requested image on this project's Mac, without copying or uploading it. */
    suspend fun image(args: ProjectImageArgs): ProjectImageResult = throw UnsupportedOperationException("Local image preview is unavailable")
    /** Opens the Mac's folder picker natively; the chosen folder joins the status, which is unchanged when cancelled. */
    suspend fun chooseFolder(args: ProjectChooseFolderArgs): ProjectFoldersStatus
    /** Forgets the folder. It does not touch the folder or its files. */
    suspend fun forgetFolder(args: ProjectForgetFolderArgs): ProjectFoldersStatus
    suspend fun folderStatus(scope: ProjectScope): ProjectFoldersStatus
    /** Whether the conversation has a folder of outputs on this Mac, and whether it holds them all. Never a path. */
    suspend fun outputsFolder(args: ProjectConversationArgs): ProjectOutputsFolder = ProjectOutputsFolder(ProjectOutputsFolder.NONE)
    /** Shows the conversation's folder of outputs in the Mac's file browser. */
    suspend fun showOutputs(args: ProjectConversationArgs): ProjectShowOutputsResult = ProjectShowOutputsResult("unavailable")
    /** The approvals this Mac keeps for the project, so it does not ask again. */
    suspend fun approvals(scope: ProjectScope): ProjectApprovals = ProjectApprovals(emptyList())
    /** Forgets one approval and answers those left: the next time, the Mac asks. */
    suspend fun withdrawApproval(args: ProjectWithdrawApprovalArgs): ProjectApprovals = ProjectApprovals(emptyList())
    /** What a companion's folder takes on this Mac, against the person's limit for it. Sizes, never a path. */
    suspend fun storage(scope: CompanionScope): ProjectStorage = ProjectStorage.NONE
    /** Sets the companion's limit on this Mac and answers its storage. */
    suspend fun setStorageLimit(args: ProjectSetStorageLimitArgs): ProjectStorage = ProjectStorage.NONE
    /** Empties scratch and moves the chosen conversations' folders to the Trash, and answers what is left. */
    suspend fun cleanStorage(args: ProjectCleanStorageArgs): ProjectStorage = ProjectStorage.NONE
}

/** A companion, for what this Mac keeps for it. */
@Serializable
data class CompanionScope(val companionId: String)

/** `STORAGE_LIMITS`: bytes are a thousand to the kilobyte. */
object ProjectStorageLimits {
    const val CONVERSATIONS = 200
    const val DEFAULT_BYTES = 1_000_000_000L
    const val MAX_BYTES = 1_000_000_000_000L
    const val MIN_BYTES = 100_000_000L
}

/** One conversation's folder: what it takes, and when a file in it last changed (an ISO-8601 instant). */
@Serializable
data class ProjectStorageConversation(val changedAt: String, val conversationId: String, val usedBytes: Long) {
    init { require(SharedRules.isRecordId(conversationId) && usedBytes >= 0) }
}

/**
 * What a companion's folder takes on this Mac. `none`: it has no folder here, and every size is 0. [partial]: the
 * folder was too large to count to the end, so the sizes are at least these.
 */
@Serializable
data class ProjectStorage(
    val conversations: List<ProjectStorageConversation>,
    val limitBytes: Long,
    val partial: Boolean,
    val scratchBytes: Long,
    val state: String,
    val usedBytes: Long,
) {
    init {
        require(state == "none" || state == "ready")
        require(conversations.size <= ProjectStorageLimits.CONVERSATIONS && usedBytes >= 0 && scratchBytes >= 0)
        require(limitBytes in ProjectStorageLimits.MIN_BYTES..ProjectStorageLimits.MAX_BYTES)
    }
    companion object {
        /** A companion with no folder on this Mac. */
        val NONE = ProjectStorage(emptyList(), ProjectStorageLimits.DEFAULT_BYTES, false, 0, "none", 0)
    }
}

@Serializable
data class ProjectSetStorageLimitArgs(val companionId: String, val limitBytes: Long)

@Serializable
data class ProjectCleanStorageArgs(val companionId: String, val conversationIds: List<String>, val scratch: Boolean)

/** A conversation of a project this Mac runs. */
@Serializable
data class ProjectConversationArgs(val projectId: String, val conversationId: String)

/** `none` until the conversation has an output, `behind` while one could not be written there, `ready` otherwise. */
@Serializable
data class ProjectOutputsFolder(val state: String) {
    init { require(state in STATES) }
    companion object {
        const val NONE = "none"
        const val READY = "ready"
        const val BEHIND = "behind"
        val STATES = setOf(NONE, READY, BEHIND)
    }
}

/** `shown`, or `unavailable` when the conversation has no folder yet. */
@Serializable
data class ProjectShowOutputsResult(val status: String)

/**
 * A yes this Mac keeps: [member] may `work` in the project's folder, or the coordinator as [member] may be `read` its
 * files, in one conversation. [id] names it for a withdrawal and says nothing of the folder.
 */
@Serializable
data class ProjectApproval(val conversationId: String, val id: String, val member: TeamMember, val purpose: String) {
    init { require(SharedRules.isRecordId(conversationId) && APPROVAL_ID.matches(id) && purpose in PURPOSES) }
    companion object {
        val PURPOSES = setOf("work", "read")
        internal val APPROVAL_ID = Regex("^[0-9a-f]{64}$")
        /** `PROJECT_APPROVALS_LIMIT`. */
        const val LIMIT = 200
    }
}

/** The approvals kept for a project, oldest first. */
@Serializable
data class ProjectApprovals(val approvals: List<ProjectApproval>) {
    init { require(approvals.size <= ProjectApproval.LIMIT) }
}

@Serializable
data class ProjectWithdrawApprovalArgs(val projectId: String, val approvalId: String)

@Serializable
data class ProjectOpenOutputArgs(val projectId: String, val conversationId: String, val artifactId: String)
@Serializable
data class ProjectOpenOutputResult(val status: String)

@Serializable
data class ProjectImageArgs(val projectId: String, val path: String)
@Serializable
data class ProjectImageResult(val mediaType: String, val base64: String)

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
    val OPEN_OUTPUT = BridgeMethod("project.openOutput", ProjectOpenOutputArgs.serializer(), ProjectOpenOutputResult.serializer()) {
        SharedRules.isRecordId(it.projectId) && SharedRules.isRecordId(it.conversationId) && SharedRules.isRecordId(it.artifactId)
    }
    val IMAGE = BridgeMethod("project.image", ProjectImageArgs.serializer(), ProjectImageResult.serializer()) {
        SharedRules.isRecordId(it.projectId) && it.path.length in 1..4096
    }
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

    val OUTPUTS_FOLDER = BridgeMethod("project.outputsFolder", ProjectConversationArgs.serializer(), ProjectOutputsFolder.serializer()) {
        SharedRules.isRecordId(it.projectId) && SharedRules.isRecordId(it.conversationId)
    }
    val SHOW_OUTPUTS = BridgeMethod("project.showOutputs", ProjectConversationArgs.serializer(), ProjectShowOutputsResult.serializer()) {
        SharedRules.isRecordId(it.projectId) && SharedRules.isRecordId(it.conversationId)
    }
    val APPROVALS = BridgeMethod("project.approvals", ProjectScope.serializer(), ProjectApprovals.serializer()) {
        SharedRules.isRecordId(it.projectId)
    }
    val WITHDRAW_APPROVAL = BridgeMethod("project.withdrawApproval", ProjectWithdrawApprovalArgs.serializer(), ProjectApprovals.serializer()) {
        SharedRules.isRecordId(it.projectId) && ProjectApproval.APPROVAL_ID.matches(it.approvalId)
    }

    val STORAGE = BridgeMethod("project.storage", CompanionScope.serializer(), ProjectStorage.serializer()) {
        SharedRules.isRecordId(it.companionId)
    }
    val SET_STORAGE_LIMIT = BridgeMethod("project.setStorageLimit", ProjectSetStorageLimitArgs.serializer(), ProjectStorage.serializer()) {
        SharedRules.isRecordId(it.companionId) && it.limitBytes in ProjectStorageLimits.MIN_BYTES..ProjectStorageLimits.MAX_BYTES
    }
    val CLEAN_STORAGE = BridgeMethod("project.cleanStorage", ProjectCleanStorageArgs.serializer(), ProjectStorage.serializer()) {
        SharedRules.isRecordId(it.companionId) && it.conversationIds.size <= ProjectStorageLimits.CONVERSATIONS &&
            it.conversationIds.all(SharedRules::isRecordId)
    }

    fun pack(project: Capability<ProjectCapability>): CapabilityPack = binder.pack(project)

    internal val binder = CapabilityBinder("project", listOf<CapabilityHandler<ProjectCapability>>(
        OPEN_OUTPUT.handle { project, args -> project.openOutput(args) },
        IMAGE.handle { project, args -> project.image(args) },
        CHOOSE_FOLDER.handle { project, args -> project.chooseFolder(args) },
        FORGET_FOLDER.handle { project, args -> project.forgetFolder(args) },
        FOLDER_STATUS.handle { project, scope -> project.folderStatus(scope) },
        OUTPUTS_FOLDER.handle { project, args -> project.outputsFolder(args) },
        SHOW_OUTPUTS.handle { project, args -> project.showOutputs(args) },
        APPROVALS.handle { project, scope -> project.approvals(scope) },
        WITHDRAW_APPROVAL.handle { project, args -> project.withdrawApproval(args) },
        STORAGE.handle { project, scope -> project.storage(scope) },
        SET_STORAGE_LIMIT.handle { project, args -> project.setStorageLimit(args) },
        CLEAN_STORAGE.handle { project, args -> project.cleanStorage(args) },
    ))
}
