package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.storage.readBounded
import org.formulagen.wrapper.storage.writeDurably

/*
 * The folders the player has said to open a workspace in (docs/desktop.md, "Folder trust"). Opening
 * one starts their login shell there, and so whatever the folder's own settings run, so the first
 * open of each folder asks first, as VS Code's Workspace Trust does.
 */

private const val TAG = "workspace-trust"

/** The folders the player trusts, by real path. Never throws: a store that cannot be read trusts none. */
internal interface FolderTrust {
    /** Whether [folder], a real path, was trusted. */
    fun trusted(folder: Path): Boolean

    /** Remember [folder], a real path. */
    fun trust(folder: Path)

    /** Forget every folder. */
    fun forget()
}

/** The folder-trust file under Hovel's support folder [support]: `workspace/folder-trust.json`. */
internal fun folderTrustFile(support: Path): FolderTrustFile = FolderTrustFile(support.resolve("workspace").resolve("folder-trust.json"))

/** [FolderTrustFile]'s content. */
@Serializable
private data class TrustedFolders(val version: Int, val folders: List<String>)

/**
 * [FolderTrust] in [file]: a versioned JSON list of real paths, oldest first, written whole with
 * `writeDurably` in a private folder (docs/desktop.md, "Folder trust"). Read afresh on each question,
 * since other copies of the app may share it. A missing, oversized, damaged or other-version file
 * trusts nothing, and [trust] replaces it.
 */
internal class FolderTrustFile(private val file: Path) : FolderTrust {
    override fun trusted(folder: Path): Boolean = folder.toString() in read()

    override fun trust(folder: Path) {
        val path = folder.toString()
        try { writeDurably(file, encoded(read().filter { it != path } + path)) } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "Could not remember a trusted folder; it will be asked about again", error)
        }
    }

    override fun forget() {
        try { Files.deleteIfExists(file) } catch (error: Exception) {
            log(LogLevel.Warn, TAG, "Could not forget the trusted folders", error)
        }
    }

    /** The stored folders, or none when the file cannot be used. */
    private fun read(): List<String> = try {
        val stored = json.decodeFromString(TrustedFolders.serializer(), readBounded(file, WorkspaceGuards.TRUSTED_FOLDERS_BYTES.toLong()).decodeToString())
        if (stored.version == VERSION) stored.folders else {
            log(LogLevel.Warn, TAG, "The trusted folders are at version ${stored.version}; trusting no folder")
            emptyList()
        }
    } catch (_: NoSuchFileException) {
        emptyList()
    } catch (error: Exception) {
        log(LogLevel.Warn, TAG, "The trusted folders could not be read; trusting no folder", error)
        emptyList()
    }

    /** [folders] as the file's bytes, less the oldest that do not fit the guard. */
    private fun encoded(folders: List<String>): ByteArray {
        var room = WorkspaceGuards.TRUSTED_FOLDERS_BYTES - encode(emptyList()).size
        val kept = ArrayDeque<String>()
        for (folder in folders.asReversed()) {
            room -= json.encodeToString(String.serializer(), folder).toByteArray().size + 1
            if (room < 0) break
            kept.addFirst(folder)
        }
        return encode(kept)
    }

    private fun encode(folders: List<String>): ByteArray = json.encodeToString(TrustedFolders.serializer(), TrustedFolders(VERSION, folders)).toByteArray()

    private companion object {
        const val VERSION = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}
