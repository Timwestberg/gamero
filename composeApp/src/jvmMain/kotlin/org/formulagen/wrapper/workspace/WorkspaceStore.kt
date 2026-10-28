package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.bridge.WorkspaceLimits

/**
 * A companion's project folder: where its working members work, and whose `CLAUDE.md` and
 * `AGENTS.md` are its project instructions. [path] never leaves native code; the page sees only
 * [name] and whether the folder still [exists].
 */
internal data class ProjectFolder(val path: Path, val exists: Boolean) {
    /** The folder's own name, as the page shows it. */
    val name: String get() = (path.fileName?.toString() ?: path.toString()).take(WorkspaceLimits.FOLDER_NAME)
}


/**
 * Each companion's project folder, per Hovel profile, at `<providers root>/<profile>/workspaces.json`
 * (0600, replaced atomically, validated on every read; a corrupt file is never silently overwritten).
 * Only the Mac's folder picker ([picker]) chooses a folder: the page can ask for the picker, never
 * name a path. One call at a time.
 */
internal class WorkspaceStore(private val root: Path, private val picker: FolderPicker) {
    /** The companion's folder, or null when none was chosen. A folder moved or deleted since reads as not [ProjectFolder.exists]. */
    @Synchronized fun folder(profile: String, companionId: String): ProjectFolder? =
        read(profile).folders[key(companionId)]?.let { folder(Path.of(it)) }

    /**
     * Open the Mac's folder picker and keep the folder the person chooses for the companion. Cancelling
     * changes nothing and answers the folder it already had. [companionName] titles the picker. The
     * store is not held while the picker is open, so status and clear still answer meanwhile.
     */
    fun choose(profile: String, companionId: String, companionName: String? = null): ProjectFolder? {
        val id = key(companionId)
        val current = synchronized(this) { read(profile).folders[id]?.let(Path::of) }
        val title = companionName?.let { "Choose a project folder for $it" } ?: "Choose a project folder"
        val picked = picker.choose(title, current) ?: return folder(profile, companionId)
        val chosen = picked.toAbsolutePath().normalize().toRealPath()
        require(Files.isDirectory(chosen)) { "A project folder must be a folder" }
        synchronized(this) {
            val record = read(profile)
            write(profile, record.copy(folders = record.folders + (id to chosen.toString())).validated())
        }
        return folder(chosen)
    }

    /** Forget the companion's folder. The folder itself is untouched. */
    @Synchronized fun clear(profile: String, companionId: String): ProjectFolder? {
        val id = key(companionId)
        val record = read(profile)
        if (id in record.folders) write(profile, record.copy(folders = record.folders - id))
        return null
    }

    private fun folder(path: Path) = ProjectFolder(path, Files.isDirectory(path))

    private fun file(profile: String): Path {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        return root.resolve(profile).resolve("workspaces.json")
    }

    private fun read(profile: String): WorkspaceRecord {
        val file = file(profile)
        if (!Files.exists(file)) return WorkspaceRecord()
        require(Files.size(file) <= MAX_RECORD_BYTES) { "Workspace record too large" }
        return json.decodeFromString<WorkspaceRecord>(Files.readString(file)).validated()
    }

    /** Replace only after the complete new record has been written privately. */
    private fun write(profile: String, record: WorkspaceRecord) {
        val file = file(profile)
        Files.createDirectories(file.parent)
        Files.setPosixFilePermissions(file.parent, PosixFilePermissions.fromString("rwx------"))
        val temp = Files.createTempFile(file.parent, ".workspaces-", ".tmp",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, json.encodeToString(record))
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    /** The canonical lower-case id, so `ABC…` and `abc…` are one companion; anything else is not a companion id. */
    private fun key(companionId: String): String {
        require(companionId.length == 36) { "Invalid companion" }
        return UUID.fromString(companionId).toString().also { require(it.equals(companionId, ignoreCase = true)) { "Invalid companion" } }
    }
}

/** One profile's project folders by companion id (version 1). */
@Serializable
private data class WorkspaceRecord(val version: Int = 1, val folders: Map<String, String> = emptyMap())

private const val MAX_RECORD_BYTES = 1_000_000
private const val MAX_FOLDERS = 1_000
private const val MAX_PATH = 4_096
private val json = Json { encodeDefaults = true }

private fun WorkspaceRecord.validated(): WorkspaceRecord = also { record ->
    require(record.version == 1 && record.folders.size <= MAX_FOLDERS)
    record.folders.forEach { (id, path) ->
        require(UUID.fromString(id).toString() == id)
        require(path.length <= MAX_PATH && '\u0000' !in path)
        val parsed = Path.of(path)
        require(parsed.isAbsolute && parsed.normalize() == parsed)
    }
}
