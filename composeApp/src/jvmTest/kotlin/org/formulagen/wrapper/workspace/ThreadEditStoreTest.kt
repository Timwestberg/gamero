package org.formulagen.wrapper.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import org.formulagen.wrapper.storage.writeDurably
import org.formulagen.wrapper.workspace.EditGap.EARLIER_REPLIES
import org.formulagen.wrapper.workspace.EditGap.FILE_LIMIT
import org.formulagen.wrapper.workspace.EditGap.UNNAMED_EDIT

class ThreadEditStoreTest {
    private val root: Path = Files.createTempDirectory("thread-edits").toRealPath()
    private val account = id()
    private val project = id()
    private val folder: Path = Files.createDirectories(root.resolve("project"))
    /** Every store's clock: each reading is one later. */
    private val clock = AtomicLong()

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    private fun id() = UUID.randomUUID().toString()

    private fun store(limits: WorkspaceLimits = WorkspaceLimits.DEFAULTS, write: (Path, ByteArray) -> Unit = ::writeDurably) =
        ThreadEditStore(root.resolve("providers"), account, { limits }, clock::incrementAndGet, write)

    /** Open [thread]'s record for a turn that edits [paths]. */
    private fun ThreadEditStore.turn(thread: String, vararg paths: String?, earlierReplies: Boolean = false, title: String = "Implementer · task") {
        val recorder = thread(project, thread, title, earlierReplies, folder)
        for (path in paths) recorder.edited(path?.let(Path::of))
    }

    /** [thread]'s record as a fresh store reads it from disk. */
    private fun read(thread: String) = store().use { it.read(project, thread) }

    private fun projectFolder(projectId: String = project) =
        root.resolve("providers").resolve(account).resolve("workspace").resolve("edits").resolve(projectId)

    private fun recordFile(thread: String) = projectFolder().resolve("$thread.json")

    private fun records(projectId: String = project) = Files.list(projectFolder(projectId)).use { it.count() }.toInt()

    @Test fun aRecordListsEachFileOnceRelativeToTheFolderInFirstEditOrder() {
        val thread = id()
        store().use { it.turn(thread, "$folder/src/app.ts", "README.md", "$folder/src/../src/app.ts", "./README.md", "$folder/docs/guide.md") }
        val record = assertNotNull(read(thread))
        assertEquals(listOf("src/app.ts", "README.md", "docs/guide.md"), record.files)
        assertEquals(emptySet(), record.gaps)
        assertEquals("Implementer · task", record.title)
        assertEquals(thread, record.threadId)
        assertEquals(1, record.version)
        // Private folders, under the account's workspace folder.
        for (path in generateSequence(recordFile(thread).parent) { it.parent }.take(3)) {
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), path.toString())
        }
    }

    @Test fun aNewRecordHasNoFilesAndOnlyANewOneForAThreadWithRepliesGetsEarlierReplies() {
        val fresh = id()
        val continued = id()
        store().use {
            it.turn(fresh)
            it.turn(continued, earlierReplies = true)
        }
        val created = assertNotNull(read(fresh))
        assertEquals(ThreadEdits(fresh, "Implementer · task", emptyList(), emptySet(), created.updatedAt), created)
        assertEquals(setOf(EARLIER_REPLIES), read(continued)?.gaps)
        // A later turn refreshes the record: its title and time change, its files stay, and it gains no gap.
        store().use {
            it.turn(fresh, "a.txt")
            it.turn(fresh, "b.txt", earlierReplies = true, title = "Implementer · renamed")
        }
        val refreshed = assertNotNull(read(fresh))
        assertEquals(listOf("a.txt", "b.txt"), refreshed.files)
        assertEquals(emptySet(), refreshed.gaps)
        assertEquals("Implementer · renamed", refreshed.title)
        assertTrue(refreshed.updatedAt > created.updatedAt)
    }

    @Test fun anEditWithoutAFileMarksTheRecordIncomplete() {
        val thread = id()
        store().use { it.turn(thread, null, "a.txt", null) }
        val record = assertNotNull(read(thread))
        assertEquals(listOf("a.txt"), record.files)
        assertEquals(setOf(UNNAMED_EDIT), record.gaps)
    }

    @Test fun pathsOutsideTheWorkingFolderAreSkipped() {
        val thread = id()
        Files.createDirectories(root.resolve("project-old"))
        store().use { it.turn(thread, "/etc/hosts", "../elsewhere.txt", "$folder/../project-old/a.txt", "$root/project-old/b.txt", "$folder", ".", "inside.txt") }
        assertEquals(listOf("inside.txt"), read(thread)?.files)
    }

    @Test fun aPathThroughALinkToTheFolderIsInsideItAsTmpIsPrivateTmpOnAMac() {
        val real = Files.createDirectories(root.resolve("real"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        Files.writeString(real.resolve("exists.txt"), "x")
        val viaLink = id()
        val viaReal = id()
        store().use { store ->
            // Given the link, the runtime reports real paths.
            store.thread(project, viaLink, "Linked", false, link).run {
                edited(real.resolve("exists.txt")); edited(link.resolve("given.txt"))
            }
            // Given the real folder, the runtime reports paths through the link: a file that exists, one it deleted, one outside.
            store.thread(project, viaReal, "Real", false, real).run {
                edited(link.resolve("exists.txt")); edited(link.resolve("deleted/gone.txt")); edited(link.resolve("../outside.txt"))
            }
        }
        assertEquals(listOf("exists.txt", "given.txt"), read(viaLink)?.files)
        assertEquals(listOf("exists.txt", "deleted/gone.txt"), read(viaReal)?.files)
        // The Mac's own /tmp, which is /private/tmp.
        val tmp = Path.of("/tmp")
        if (Files.isSymbolicLink(tmp)) {
            val scratch = Files.createTempDirectory(tmp, "hovel-edits")
            try {
                val thread = id()
                store().use { it.thread(project, thread, "Tmp", false, scratch).edited(scratch.toRealPath().resolve("a.txt")) }
                assertEquals(listOf("a.txt"), read(thread)?.files)
            } finally { scratch.toFile().deleteRecursively() }
        }
    }

    @Test fun theFileLimitRecordsNoMoreFilesAndALoweredLimitKeepsWhatARecordHas() {
        val thread = id()
        store(WorkspaceLimits(threadFiles = 60, projectThreads = 200)).use { it.turn(thread, *Array(61) { "file-${it % 60}.txt" }) }
        assertEquals(List(60) { "file-$it.txt" }, read(thread)?.files)
        assertEquals(emptySet(), read(thread)?.gaps, "a file already listed is no new file")
        store(WorkspaceLimits(threadFiles = 50, projectThreads = 200)).use { it.turn(thread, "file-0.txt", "new.txt") }
        val lowered = assertNotNull(read(thread))
        assertEquals(List(60) { "file-$it.txt" }, lowered.files, "files already listed stay, and the new one is not added")
        assertEquals(setOf(FILE_LIMIT), lowered.gaps)
    }

    @Test fun aLimitOutsideItsRangeIsTheDefault() {
        assertEquals(500, WorkspaceSettingsLimits.THREAD_FILES.resolve(null))
        assertEquals(500, WorkspaceSettingsLimits.THREAD_FILES.resolve(49))
        assertEquals(2_000, WorkspaceSettingsLimits.THREAD_FILES.resolve(2_000))
        assertEquals(200, WorkspaceSettingsLimits.PROJECT_THREADS.resolve(1_001))
        assertEquals(20, WorkspaceSettingsLimits.PROJECT_THREADS.resolve(20))
        assertEquals(WorkspaceLimits(500, 200), WorkspaceLimits.DEFAULTS)
        val thread = id()
        store(WorkspaceLimits(threadFiles = 0, projectThreads = 0)).use { it.turn(thread, "a.txt") }
        assertEquals(listOf("a.txt"), read(thread)?.files, "a limit of 0 is out of range, so the default applies")
    }

    @Test fun aThreadOfLongPathsStopsAtTheFileLimitBeforeItsRecordOutgrowsTheGuard() {
        val thread = id()
        val long = listOf("d", "e", "f").joinToString("/") { it.repeat(200) }
        store(WorkspaceLimits(threadFiles = 2_000, projectThreads = 200)).use { it.turn(thread, *Array(2_000) { "$long/file-$it.txt" }) }
        val record = assertNotNull(read(thread), "the record still reads back")
        assertTrue(record.files.size in 1..1_999, "${record.files.size}")
        assertEquals(setOf(FILE_LIMIT), record.gaps)
        assertTrue(Files.size(recordFile(thread)) <= WorkspaceGuards.EDIT_RECORD_BYTES)
    }

    @Test fun openingARecordTrimsTheProjectsOldestBeyondTheThreadsLimit() {
        val threads = List(22) { id() }
        val other = id()
        store(WorkspaceLimits(threadFiles = 500, projectThreads = 20)).use { store ->
            threads.take(20).forEach { store.turn(it, "a.txt") }
            store.thread(other, id(), "Another project", false, folder)
            // The oldest thread works again, so it is no longer the oldest.
            store.turn(threads[0])
            store.turn(threads[20])
            store.turn(threads[21])
        }
        assertEquals(listOf(threads[0]) + threads.subList(3, 22), threads.filter { read(it) != null }, "the two oldest after the refresh are gone")
        assertEquals(20, records())
        assertEquals(1, records(other), "another project keeps its records")
    }

    @Test fun anEditForAThreadTrimmedWhileItRanIsDropped() {
        val running = id()
        store(WorkspaceLimits(threadFiles = 500, projectThreads = 20)).use { store ->
            val recorder = store.thread(project, running, "Running", false, folder)
            repeat(20) { store.turn(id()) }
            recorder.edited(Path.of("late.txt"))
        }
        assertNull(read(running), "never a record that looks complete")
        assertEquals(20, records())
    }

    @Test fun aRecordLargerThanTheGuardIsNotReadAndIsReplacedWhenItsThreadWorksAgain() {
        val thread = id()
        Files.createDirectories(recordFile(thread).parent)
        Files.write(recordFile(thread), ByteArray(WorkspaceGuards.EDIT_RECORD_BYTES + 1) { ' '.code.toByte() })
        assertNull(read(thread))
        store().use { it.turn(thread, "a.txt", earlierReplies = true) }
        val replaced = assertNotNull(read(thread))
        assertEquals(listOf("a.txt"), replaced.files)
        assertEquals(setOf(EARLIER_REPLIES), replaced.gaps, "what it listed before is gone")
        // A damaged record reads as none too.
        Files.writeString(recordFile(thread), "{\"threadId\":")
        assertNull(read(thread))
    }

    @Test fun aFailedWriteNeverThrowsAndTheNextChangeWritesTheWholeRecord() {
        val thread = id()
        val failures = AtomicInteger(2)
        val writes = AtomicInteger()
        val failing = { file: Path, bytes: ByteArray ->
            writes.incrementAndGet()
            if (failures.getAndDecrement() > 0) throw IOException("Disk full") else writeDurably(file, bytes)
        }
        store(write = failing).use { store ->
            val recorder = store.thread(project, thread, "Implementer · task", false, folder)
            recorder.edited(Path.of("a.txt"))
            val until = System.nanoTime() + 10_000_000_000L
            while (writes.get() < 2 && System.nanoTime() < until) Thread.sleep(5)
            assertFalse(Files.exists(recordFile(thread)), "the opening and the first edit failed to write")
            recorder.edited(Path.of("b.txt"))
        }
        assertEquals(listOf("a.txt", "b.txt"), read(thread)?.files)
        assertEquals(3, writes.get())
        // Once the store is closed, an edit is dropped without throwing.
        val closed = store()
        val recorder = closed.thread(project, thread, "Implementer · task", false, folder)
        closed.close()
        recorder.edited(Path.of("c.txt"))
        assertEquals(listOf("a.txt", "b.txt"), read(thread)?.files)
    }

    @Test fun recordsLeftOutOfMemoryAreReadBackWhenTheirThreadEditsAgain() {
        val threads = List(80) { id() }
        store().use { store ->
            val first = store.thread(project, threads[0], "First", false, folder)
            first.edited(Path.of("a.txt"))
            threads.drop(1).forEach { store.turn(it, "x.txt") }
            first.edited(Path.of("b.txt"))
        }
        assertEquals(listOf("a.txt", "b.txt"), read(threads[0])?.files)
        assertEquals(80, records())
    }

    @Test fun onlyRecordIdsNameARecord() {
        store().use { store ->
            assertFailsWith<IllegalArgumentException> { store.thread("../escape", id(), "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { store.thread(project, "../../conversations/x", "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { store.thread(project, id().uppercase(), "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { store.read(project, "x") }
        }
        assertFailsWith<IllegalArgumentException> { ThreadEditStore(root, "not-an-account") }
        assertFalse(Files.exists(root.resolve("providers")), "nothing was written")
    }
}
