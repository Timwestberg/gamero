package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

class WorkspaceStoreTest {
    private val root: Path = Files.createTempDirectory("hovel-workspaces")
    private val companion = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()

    /** Answers each pick with the next entry of [answers] (null is a cancel), and records what it was asked. */
    private class Picker(vararg answers: Path?) : FolderPicker {
        val queue = ArrayDeque(answers.toList())
        val asked = mutableListOf<Pair<String, Path?>>()
        override fun choose(title: String, current: Path?): Path? { asked += title to current; return queue.removeFirst() }
    }

    private fun project(name: String): Path = Files.createDirectories(root.resolve("projects").resolve(name)).toRealPath()

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    @Test fun aChosenFolderIsKeptPerProfileAndCompanionPrivatelyAndAtomically() {
        val game = project("my-game")
        val store = WorkspaceStore(root, Picker(game))
        assertNull(store.folder("local", companion))
        assertEquals(ProjectFolder(game, exists = true), store.choose("local", companion, "Ember"))
        assertEquals("my-game", store.folder("local", companion)?.name)
        val file = root.resolve("local/workspaces.json")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
        assertTrue(Files.list(file.parent).use { files -> files.noneMatch { it.fileName.toString().endsWith(".tmp") } })
        // Another companion and another profile have none of their own.
        assertNull(store.folder("local", other))
        val profile = UUID.randomUUID().toString()
        assertNull(store.folder(profile, companion))
        // A new store reads what was kept.
        assertEquals(game, WorkspaceStore(root, Picker()).folder("local", companion)?.path)
    }

    @Test fun cancellingThePickerChangesNothing() {
        val game = project("my-game")
        val picker = Picker(game, null)
        val store = WorkspaceStore(root, picker)
        store.choose("local", companion, "Ember")
        assertEquals(ProjectFolder(game, exists = true), store.choose("local", companion, "Ember"))
        assertEquals(listOf("Choose a project folder for Ember" to null, "Choose a project folder for Ember" to game), picker.asked)
        assertNull(WorkspaceStore(root, Picker(null)).choose("local", other))
        assertNull(WorkspaceStore(root, Picker()).folder("local", other))
    }

    @Test fun changingAndClearingTheFolderLeavesTheFoldersThemselves() {
        val first = project("first"); val second = project("second")
        val store = WorkspaceStore(root, Picker(first, second))
        store.choose("local", companion); store.choose("local", other)
        assertEquals(second, store.folder("local", other)?.path)
        assertNull(store.clear("local", companion))
        assertNull(store.folder("local", companion))
        assertEquals(second, store.folder("local", other)?.path)
        assertTrue(Files.isDirectory(first))
        assertNull(store.clear("local", companion), "Clearing twice is fine")
    }

    @Test fun aFolderMovedOrDeletedSinceStillShowsItsNameButNotThatItExists() {
        val game = project("my-game")
        val store = WorkspaceStore(root, Picker(game))
        store.choose("local", companion)
        Files.delete(game)
        assertEquals(ProjectFolder(game, exists = false), store.folder("local", companion))
        assertEquals("my-game", store.folder("local", companion)?.name)
    }

    @Test fun onlyAFolderIsKeptAndALinkIsKeptAsWhereItLeads() {
        val game = project("my-game")
        val note = Files.writeString(root.resolve("note.txt"), "x")
        val link = Files.createSymbolicLink(root.resolve("shortcut"), game)
        val store = WorkspaceStore(root, Picker(note, link))
        assertFails { store.choose("local", companion) }
        assertNull(store.folder("local", companion))
        assertEquals(game, store.choose("local", companion)?.path)
    }

    @Test fun thePageCannotNameAProfileOrCompanionOutsideItsOwn() {
        val store = WorkspaceStore(root, Picker(project("x")))
        assertFails { store.folder("../..", companion) }
        assertFails { store.folder("local", "../../etc") }
        // The contract's UUIDs may be upper case; they name the same companion.
        val project = store.choose("local", companion)
        assertEquals(project, store.folder("local", companion.uppercase()))
        assertFails { store.choose("LOCAL", companion) }
    }

    @Test fun aCorruptOrForgedRecordIsRefusedAndNeverOverwritten() {
        val file = Files.createDirectories(root.resolve("local")).resolve("workspaces.json")
        for (bad in listOf("broken", """{"version":2,"folders":{}}""", """{"version":1,"folders":{"$companion":"relative/path"}}""",
            """{"version":1,"folders":{"$companion":"/a/../b"}}""", """{"version":1,"folders":{"not-a-uuid":"/a"}}""")) {
            Files.writeString(file, bad)
            val store = WorkspaceStore(root, Picker(project("x")))
            assertFails { store.folder("local", companion) }
            assertFails { store.choose("local", companion) }
            assertEquals(bad, Files.readString(file))
        }
    }

    @Test fun aLongFolderNameIsShortenedForThePage() {
        assertEquals(FOLDER_NAME_LIMIT, ProjectFolder(Path.of("/x/" + "n".repeat(300)), true).name.length)
        assertEquals("/", ProjectFolder(Path.of("/"), true).name)
    }

    @Test fun theWorkspaceMethodsAnswerNamesAndExistenceNeverPaths() = kotlinx.coroutines.runBlocking {
        val game = project("hovel-notes")
        val workspace = DesktopWorkspace(WorkspaceStore(root, Picker(game, null)))
        val scope = org.formulagen.wrapper.bridge.WorkspaceScope(companion.uppercase(), "local")
        assertEquals(org.formulagen.wrapper.bridge.WorkspaceStatus(null), workspace.status(scope))
        val chosen = org.formulagen.wrapper.bridge.WorkspaceStatus(org.formulagen.wrapper.bridge.WorkspaceFolder(exists = true, name = "hovel-notes"))
        assertEquals(chosen, workspace.choose(scope))
        assertEquals(chosen, workspace.choose(scope), "A cancelled picker answers the unchanged status")
        Files.delete(game)
        assertEquals(chosen.copy(folder = chosen.folder!!.copy(exists = false)), workspace.status(scope), "A deleted folder stays chosen")
        assertEquals(org.formulagen.wrapper.bridge.WorkspaceStatus(null), workspace.clear(scope))
        assertEquals(org.formulagen.wrapper.bridge.WorkspaceStatus(null), workspace.status(scope))
    }
}
