package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The trusted-folders file: private, versioned, read afresh, and trusting nothing it cannot read. */
class FolderTrustTest {
    private val support: Path = createTempDirectory("hovel-trust")
    private val store = folderTrustFile(support)
    private val file: Path = support.resolve("workspace/folder-trust.json")
    private val project: Path = Files.createDirectory(support.resolve("project")).toRealPath()

    @AfterTest fun cleanUp() {
        support.toFile().deleteRecursively()
    }

    @Test fun aTrustedFolderIsKeptByItsPathInAPrivateVersionedFile() {
        assertFalse(store.trusted(project), "no file trusts nothing")
        store.trust(project)
        store.trust(project)
        assertTrue(store.trusted(project))
        assertTrue(folderTrustFile(support).trusted(project), "read afresh, as another copy of the app would")
        assertEquals("{\"version\":1,\"folders\":[\"$project\"]}", Files.readString(file), "once, by its path")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
        assertFalse(store.trusted(support), "another folder is not trusted")
        assertFalse(store.trusted(project.resolve("inside")), "nor one inside it")
    }

    @Test fun forgettingTrustsNoFolderAgain() {
        store.trust(project)
        store.forget()
        assertFalse(store.trusted(project))
        assertFalse(Files.exists(file))
        store.forget()
    }

    @Test fun aDamagedOversizedOrForeignFileTrustsNothingAndTheNextTrustReplacesIt() {
        Files.createDirectories(file.parent)
        val damaged = listOf(
            "", "{not json", "[]", "{\"version\":1}",
            "{\"version\":1,\"folders\":[\"$project\"",
            "{\"version\":2,\"folders\":[\"$project\"]}",
        )
        for (content in damaged) {
            Files.writeString(file, content)
            assertFalse(store.trusted(project), "\"$content\" trusts nothing, and does not throw")
        }
        Files.writeString(file, "{\"version\":1,\"folders\":[\"$project\"]}" + " ".repeat(WorkspaceGuards.TRUSTED_FOLDERS_BYTES))
        assertFalse(store.trusted(project), "a file past the guard is not read")
        store.trust(project)
        assertTrue(store.trusted(project), "Open Workspace writes a good file in its place")
    }

    @Test fun theOldestFoldersGoWhenTheFileWouldPassItsGuard() {
        val long = "/" + "x".repeat(4_000)
        val count = WorkspaceGuards.TRUSTED_FOLDERS_BYTES / 4_000 + 5
        repeat(count) { store.trust(Path.of("$long/$it")) }
        assertTrue(Files.size(file) <= WorkspaceGuards.TRUSTED_FOLDERS_BYTES, "${Files.size(file)} bytes")
        assertTrue(store.trusted(Path.of("$long/${count - 1}")), "the newest stays")
        assertFalse(store.trusted(Path.of("$long/0")), "the oldest went")
    }
}
