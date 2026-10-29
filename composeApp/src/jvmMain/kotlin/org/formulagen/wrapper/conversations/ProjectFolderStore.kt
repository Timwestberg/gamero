package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.bridge.ProjectFolderNames
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.chat.ProjectFolders
import org.formulagen.wrapper.workspace.FolderPicker

/** One project folder on this Mac: its account [id], display [name], [role], and the [path] that never leaves the Mac. */
internal data class LocalFolder(val id: String, val name: String, val path: Path, val role: FolderRole) {
    val exists: Boolean get() = Files.isDirectory(path)
}

/**
 * Each project's folders on this Mac, per account, at `<root>/<accountId>/project-folders.json`
 * (private, replaced atomically, validated on every read; a corrupt file is never overwritten). It
 * maps `(projectId, folderId)` to a path, and replaces `WorkspaceStore` once team chat goes. Only
 * the Mac's folder picker ([picker]) adds a folder: a page can ask for the picker, never name a path.
 *
 * It also remembers what the account has of each project: the revision this Mac last saw, and
 * whether its folders still need publishing (`PUT /projects/:id/folders`).
 */
internal class ProjectFolderStore(private val root: Path, private val picker: FolderPicker) {
    /** [projectId]'s folders, working first. */
    @Synchronized fun folders(accountId: String, projectId: String): List<LocalFolder> =
        read(accountId).projects[key(projectId)]?.folders.orEmpty().map { it.local() }.sortedBy { it.role != FolderRole.WORKING }

    /** The folders a run works with: the working folder and the context folders, those that still exist. */
    fun projectFolders(accountId: String, projectId: String): ProjectFolders {
        val folders = folders(accountId, projectId).filter { it.exists }
        val working = folders.firstOrNull { it.role == FolderRole.WORKING }?.path ?: return ProjectFolders(null)
        return ProjectFolders(working, folders.filter { it.role == FolderRole.CONTEXT }.map { it.path })
    }

    /**
     * Open the Mac's folder picker for [role] and keep the folder the person chooses: a new working
     * folder replaces the one before, a context folder joins the others. Cancelling, or choosing a
     * folder the project already has, changes nothing. The store is not held while the picker is open.
     */
    fun choose(accountId: String, projectId: String, role: FolderRole): List<LocalFolder> {
        val id = key(projectId)
        val current = synchronized(this) { read(accountId).projects[id] ?: StoredProject() }
        check(!current.foreign) { "This project runs on another computer" }
        if (role == FolderRole.CONTEXT) {
            check(current.folders.count { it.role == FolderRole.CONTEXT } < CompanionLimits.CONTEXT_FOLDERS) { "Too many context folders" }
        }
        val title = if (role == FolderRole.WORKING) "Choose the project's working folder" else "Choose a context folder agents may only read"
        val picked = picker.choose(title, current.folders.firstOrNull { it.role == role }?.path?.let(Path::of))
            ?: return folders(accountId, projectId)
        val chosen = picked.toAbsolutePath().normalize().toRealPath()
        require(Files.isDirectory(chosen)) { "A project folder must be a folder" }
        synchronized(this) {
            val record = read(accountId)
            val project = record.projects[id] ?: StoredProject()
            if (project.folders.any { it.path == chosen.toString() }) return folders(accountId, projectId)
            val kept = if (role == FolderRole.WORKING) project.folders.filter { it.role != FolderRole.WORKING } else project.folders
            check(kept.count { it.role == FolderRole.CONTEXT } < CompanionLimits.CONTEXT_FOLDERS || role == FolderRole.WORKING) { "Too many context folders" }
            val name = ProjectFolderNames.display(chosen.fileName?.toString() ?: chosen.toString(), kept.map { it.name })
            val added = StoredFolder(UUID.randomUUID().toString(), name, chosen.toString(), role)
            write(accountId, record.with(id, project.copy(folders = kept + added, published = false)))
        }
        return folders(accountId, projectId)
    }

    /** Forget one folder. The folder itself and its files are untouched. */
    @Synchronized fun forget(accountId: String, projectId: String, folderId: String): List<LocalFolder> {
        val id = key(projectId)
        require(SharedRules.isRecordId(folderId)) { "Invalid folder" }
        val record = read(accountId)
        val project = record.projects[id] ?: return emptyList()
        check(!project.foreign) { "This project runs on another computer" }
        if (project.folders.any { it.id == folderId }) {
            write(accountId, record.with(id, project.copy(folders = project.folders.filter { it.id != folderId }, published = false)))
        }
        return folders(accountId, projectId)
    }

    /** What the account last said about [projectId]: its revision. A project this Mac claimed runs here. */
    @Synchronized fun learned(accountId: String, projectId: String, revision: Long) {
        val id = key(projectId)
        val record = read(accountId)
        val project = record.projects[id] ?: StoredProject()
        if (project.revision == revision && !project.foreign) return
        write(accountId, record.with(id, project.copy(revision = maxOf(revision, project.revision ?: 0), foreign = false)))
    }

    /** The account recorded [folders] at [revision]: nothing is left to publish unless they changed since. */
    @Synchronized fun published(accountId: String, projectId: String, folders: List<WireProjectFolder>, revision: Long) {
        val id = key(projectId)
        val record = read(accountId)
        val project = record.projects[id] ?: return
        val unchanged = project.folders.map { it.id } == folders.map { it.id }
        write(accountId, record.with(id, project.copy(revision = revision, published = unchanged)))
    }

    /** The account refused [projectId] to this Mac: its folders here answer `failed` until a claim says otherwise. */
    @Synchronized fun foreign(accountId: String, projectId: String) {
        val id = key(projectId)
        val record = read(accountId)
        write(accountId, record.with(id, (record.projects[id] ?: StoredProject()).copy(foreign = true)))
    }

    /** Whether [projectId] runs on another computer, as far as this Mac knows. */
    @Synchronized fun isForeign(accountId: String, projectId: String) = read(accountId).projects[key(projectId)]?.foreign == true

    /** Projects whose folders the account has not recorded yet, with the revision to save against when known. */
    @Synchronized fun unpublished(accountId: String): Map<String, Long?> =
        read(accountId).projects.filterValues { !it.published && !it.foreign }.mapValues { it.value.revision }

    private fun file(accountId: String): Path {
        require(SharedRules.isRecordId(accountId)) { "Project folders are kept per account" }
        return root.resolve(accountId).resolve("project-folders.json")
    }

    private fun read(accountId: String): FolderRecord {
        val file = file(accountId)
        if (!Files.exists(file)) return FolderRecord()
        return json.decodeFromString<FolderRecord>(readBounded(file, MAX_RECORD_BYTES).decodeToString()).validated()
    }

    private fun write(accountId: String, record: FolderRecord) = writeDurably(file(accountId), json.encodeToString(record.validated()).toByteArray())

    private fun key(projectId: String) = projectId.also { require(SharedRules.isRecordId(it)) { "Invalid project" } }

    private fun StoredFolder.local() = LocalFolder(id, name, Path.of(path), role)
}

/** One project's folders and what the account has of them. */
@Serializable
private data class StoredProject(
    val folders: List<StoredFolder> = emptyList(),
    /** The project's revision as this Mac last saw it; a folder save is checked against it. */
    val revision: Long? = null,
    /** Whether the account has these folders. */
    val published: Boolean = true,
    /** The account refused this project to this Mac. */
    val foreign: Boolean = false,
)

@Serializable
private data class StoredFolder(val id: String, val name: String, val path: String, val role: FolderRole)

/** One account's projects (version 1). */
@Serializable
private data class FolderRecord(val version: Int = 1, val projects: Map<String, StoredProject> = emptyMap()) {
    fun with(projectId: String, project: StoredProject) = copy(projects = projects + (projectId to project))
}

private const val MAX_RECORD_BYTES = 2_000_000L
private const val MAX_PROJECTS = 2_000
private const val MAX_PATH = 4_096
private val json = Json { encodeDefaults = true }

private fun FolderRecord.validated(): FolderRecord = also { record ->
    require(record.version == 1 && record.projects.size <= MAX_PROJECTS)
    record.projects.forEach { (id, project) ->
        require(SharedRules.isRecordId(id))
        require(project.folders.count { it.role == FolderRole.WORKING } <= 1)
        require(project.folders.count { it.role == FolderRole.CONTEXT } <= CompanionLimits.CONTEXT_FOLDERS)
        require(project.folders.map { it.id }.toSet().size == project.folders.size)
        require(project.folders.map { it.name.lowercase() }.toSet().size == project.folders.size)
        project.folders.forEach { folder ->
            require(SharedRules.isRecordId(folder.id) && ProjectFolderNames.isValid(folder.name))
            require(folder.path.length <= MAX_PATH && '\u0000' !in folder.path)
            val parsed = Path.of(folder.path)
            require(parsed.isAbsolute && parsed.normalize() == parsed)
        }
    }
}
