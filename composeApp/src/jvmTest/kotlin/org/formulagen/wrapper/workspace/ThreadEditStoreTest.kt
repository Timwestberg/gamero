package org.formulagen.wrapper.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import org.formulagen.wrapper.storage.readBounded
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

    private fun editStore(limits: WorkspaceLimits = WorkspaceLimits.DEFAULTS, write: (Path, ByteArray) -> Unit = ::writeDurably,
        readFile: (Path, Long) -> ByteArray = ::readBounded) =
        ThreadEditStore(root.resolve("providers"), account, { limits }, clock::incrementAndGet, write, readFile)

    /** Open [thread]'s record for a turn that edits [paths]. */
    private fun ThreadEditStore.turn(thread: String, vararg paths: String?, earlierReplies: Boolean = false, title: String = "Implementer · task") {
        val listener = thread(project, thread, title, earlierReplies, folder)
        for (path in paths) listener.edited(path?.let(Path::of))
    }

    /** [thread]'s record as a fresh store reads it from disk. */
    private fun read(thread: String) = editStore().use { it.read(project, thread) }

    private fun projectFolder(projectId: String = project) =
        root.resolve("providers").resolve(account).resolve("workspace").resolve("edits").resolve(projectId)

    private fun recordFile(thread: String) = projectFolder().resolve("$thread.json")

    private fun records(projectId: String = project) = Files.list(projectFolder(projectId)).use { it.count() }.toInt()

    /** A read that notes each file it reads. */
    private fun countingReads(reads: MutableList<Path>) = { file: Path, limit: Long -> reads.add(file); readBounded(file, limit) }

    /**
     * A disk that refuses every write while [failing]. [refusedEdit] opens once it refused [watched]'s record holding
     * `a.txt`, and [written] once [watched]'s record is written.
     */
    private class FlakyDisk(private val watched: Path) {
        @Volatile var failing = true
        val refusedEdit = CountDownLatch(1)
        val written = CountDownLatch(1)

        fun write(file: Path, bytes: ByteArray) {
            if (failing) {
                if (file == watched && "a.txt" in bytes.decodeToString()) refusedEdit.countDown()
                throw IOException("Disk full")
            }
            writeDurably(file, bytes)
            if (file == watched) written.countDown()
        }
    }

    @Test fun aRecordListsEachFileOnceRelativeToTheFolderInFirstEditOrder() {
        val thread = id()
        editStore().use { it.turn(thread, "$folder/src/app.ts", "README.md", "$folder/src/../src/app.ts", "./README.md", "$folder/docs/guide.md") }
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
        editStore().use {
            it.turn(fresh)
            it.turn(continued, earlierReplies = true)
        }
        val created = assertNotNull(read(fresh))
        assertEquals(ThreadEdits(fresh, "Implementer · task", emptyList(), emptySet(), created.updatedAt), created)
        assertEquals(setOf(EARLIER_REPLIES), read(continued)?.gaps)
        // A later turn refreshes the record: its title and time change, its files stay, and it gains no gap.
        editStore().use {
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
        editStore().use { it.turn(thread, null, "a.txt", null) }
        val record = assertNotNull(read(thread))
        assertEquals(listOf("a.txt"), record.files)
        assertEquals(setOf(UNNAMED_EDIT), record.gaps)
    }

    @Test fun pathsOutsideTheWorkingFolderAreSkipped() {
        val thread = id()
        Files.createDirectories(root.resolve("project-old"))
        editStore().use { it.turn(thread, "/etc/hosts", "../elsewhere.txt", "$folder/../project-old/a.txt", "$root/project-old/b.txt", "$folder", ".", "inside.txt") }
        assertEquals(listOf("inside.txt"), read(thread)?.files)
    }

    @Test fun aPathThroughALinkToTheFolderIsInsideItAsTmpIsPrivateTmpOnAMac() {
        val real = Files.createDirectories(root.resolve("real"))
        val link = Files.createSymbolicLink(root.resolve("link"), real)
        Files.writeString(real.resolve("exists.txt"), "x")
        val viaLink = id()
        val viaReal = id()
        editStore().use { editStore ->
            // Given the link, the runtime reports real paths.
            editStore.thread(project, viaLink, "Linked", false, link).run {
                edited(real.resolve("exists.txt")); edited(link.resolve("given.txt"))
            }
            // Given the real folder, the runtime reports paths through the link: a file that exists, one it deleted, one outside.
            editStore.thread(project, viaReal, "Real", false, real).run {
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
                editStore().use { it.thread(project, thread, "Tmp", false, scratch).edited(scratch.toRealPath().resolve("a.txt")) }
                assertEquals(listOf("a.txt"), read(thread)?.files)
            } finally { scratch.toFile().deleteRecursively() }
        }
    }

    @Test fun aPathThroughALinkOutOfTheFolderIsOutsideItWhereverItsDotDotsAre() {
        val outside = Files.createDirectories(root.resolve("elsewhere/deep"))
        Files.writeString(outside.resolve("x.ts"), "x")
        Files.writeString(root.resolve("elsewhere/a"), "a")
        Files.writeString(folder.resolve("a"), "a")
        Files.createSymbolicLink(folder.resolve("vendor"), outside)
        // A link inside the folder to a place inside it: the file it reaches is recorded.
        Files.createDirectories(folder.resolve("docs"))
        Files.createSymbolicLink(folder.resolve("docs-link"), folder.resolve("docs"))
        val thread = id()
        editStore().use {
            it.turn(thread,
                "$folder/vendor/x.ts", "vendor/new.ts",             // through the link, as given and relative
                "$folder/vendor/../a", "vendor/../a",               // the link first, then `..`: elsewhere/a, not the folder's a
                "vendor/../gone.txt",                               // the same for a file that does not exist
                "missing/../vendor/y.ts",                           // `..` by name past what does not exist, back through the link
                "docs-link/guide.md", "inside.txt")
        }
        assertEquals(listOf("docs/guide.md", "inside.txt"), read(thread)?.files)
    }

    @Test fun theFileLimitRecordsNoMoreFilesAndALoweredLimitKeepsWhatARecordHas() {
        val thread = id()
        editStore(WorkspaceLimits.of(threadFiles = 60, projectThreads = null)).use { it.turn(thread, *Array(61) { "file-${it % 60}.txt" }) }
        assertEquals(List(60) { "file-$it.txt" }, read(thread)?.files)
        assertEquals(emptySet(), read(thread)?.gaps, "a file already listed is no new file")
        editStore(WorkspaceLimits.of(threadFiles = 50, projectThreads = null)).use { it.turn(thread, "file-0.txt", "new.txt") }
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
        assertEquals(500 to 200, WorkspaceLimits.DEFAULTS.threadFiles to WorkspaceLimits.DEFAULTS.projectThreads)
        assertEquals(WorkspaceLimits.DEFAULTS, WorkspaceLimits.of(threadFiles = 0, projectThreads = 0), "a limit of 0 is out of range, so the default applies")
        assertEquals(WorkspaceLimits.DEFAULTS, WorkspaceLimits.of(threadFiles = 2_001, projectThreads = 19))
        val set = WorkspaceLimits.of(threadFiles = 50, projectThreads = 1_000)
        assertEquals(50 to 1_000, set.threadFiles to set.projectThreads)
    }

    @Test fun aThreadOfLongPathsStopsAtTheFileLimitBeforeItsRecordOutgrowsTheGuard() {
        val thread = id()
        val long = listOf("d", "e", "f").joinToString("/") { it.repeat(200) }
        editStore(WorkspaceLimits.of(threadFiles = 2_000, projectThreads = null)).use { it.turn(thread, *Array(2_000) { "$long/file-$it.txt" }) }
        val record = assertNotNull(read(thread), "the record still reads back")
        assertTrue(record.files.size in 1..1_999, "${record.files.size}")
        assertEquals(setOf(FILE_LIMIT), record.gaps)
        assertTrue(Files.size(recordFile(thread)) <= WorkspaceGuards.EDIT_RECORD_BYTES)
    }

    @Test fun openingARecordTrimsTheProjectsOldestBeyondTheThreadsLimit() {
        val threads = List(22) { id() }
        val other = id()
        editStore(WorkspaceLimits.of(threadFiles = null, projectThreads = 20)).use { editStore ->
            threads.take(20).forEach { editStore.turn(it, "a.txt") }
            editStore.thread(other, id(), "Another project", false, folder)
            // The oldest thread works again, so it is no longer the oldest.
            editStore.turn(threads[0])
            editStore.turn(threads[20])
            editStore.turn(threads[21])
        }
        assertEquals(listOf(threads[0]) + threads.subList(3, 22), threads.filter { read(it) != null }, "the two oldest after the refresh are gone")
        assertEquals(20, records())
        assertEquals(1, records(other), "another project keeps its records")
    }

    @Test fun aTrimReadsEachOtherRecordOnce() {
        val threads = List(30) { id() }
        editStore().use { editStore -> threads.forEach { editStore.turn(it) } }
        val reads = CopyOnWriteArrayList<Path>()
        editStore(WorkspaceLimits.of(threadFiles = null, projectThreads = 20), readFile = countingReads(reads)).use { it.turn(id()) }
        assertEquals(threads.map(::recordFile).sorted(), reads.sorted(), "once each, not once per comparison")
        assertEquals(20, records())
        assertEquals(threads.drop(11), threads.filter { read(it) != null }, "the eleven oldest are gone")
    }

    @Test fun anEditForAThreadTrimmedWhileItRanIsDropped() {
        val running = id()
        editStore(WorkspaceLimits.of(threadFiles = null, projectThreads = 20)).use { editStore ->
            val listener = editStore.thread(project, running, "Running", false, folder)
            repeat(20) { editStore.turn(id()) }
            listener.edited(Path.of("late.txt"))
        }
        assertNull(read(running), "never a record that looks complete")
        assertEquals(20, records())
    }

    @Test fun aRecordLargerThanTheGuardIsNotReadAndIsReplacedWhenItsThreadWorksAgain() {
        val thread = id()
        Files.createDirectories(recordFile(thread).parent)
        Files.write(recordFile(thread), ByteArray(WorkspaceGuards.EDIT_RECORD_BYTES + 1) { ' '.code.toByte() })
        assertNull(read(thread))
        editStore().use { it.turn(thread, "a.txt", earlierReplies = true) }
        val replaced = assertNotNull(read(thread))
        assertEquals(listOf("a.txt"), replaced.files)
        assertEquals(setOf(EARLIER_REPLIES), replaced.gaps, "what it listed before is gone")
        // A damaged record reads as none too.
        Files.writeString(recordFile(thread), "{\"threadId\":")
        assertNull(read(thread))
    }

    @Test fun keysAndGapsThisBuildDoesNotKnowAreIgnored() {
        val thread = id()
        Files.createDirectories(recordFile(thread).parent)
        Files.writeString(recordFile(thread), """{"threadId":"$thread","title":"Implementer · task","files":["a.txt"],""" +
            """"gaps":["unnamed-edit","renamed-file"],"updatedAt":1,"version":1,"reviewedAt":2}""")
        assertEquals(ThreadEdits(thread, "Implementer · task", listOf("a.txt"), setOf(UNNAMED_EDIT), 1), read(thread))
        editStore().use { it.turn(thread, "b.txt") }
        val record = assertNotNull(read(thread))
        assertEquals(listOf("a.txt", "b.txt"), record.files)
        assertEquals(setOf(UNNAMED_EDIT), record.gaps)
    }

    @Test fun aRecordFromALaterBuildIsNeverWrittenOverOrTrimmedAndItsThreadRecordsNothing() {
        val thread = id()
        val later = """{"threadId":"$thread","title":"Implementer · task","files":{"src":["a.ts"]},"updatedAt":0,"version":2}"""
        Files.createDirectories(recordFile(thread).parent)
        Files.writeString(recordFile(thread), later)
        val reads = CopyOnWriteArrayList<Path>()
        editStore(readFile = countingReads(reads)).use { editStore ->
            editStore.turn(thread, "a.txt", null)
            editStore.turn(thread, "b.txt", earlierReplies = true)
        }
        assertEquals(later, Files.readString(recordFile(thread)))
        assertEquals(listOf(recordFile(thread)), reads, "known as a later build's after one read, so it is logged once")
        assertNull(read(thread), "this build cannot show it")
        // It counts towards the project's threads, but a trim removes only this build's records.
        editStore(WorkspaceLimits.of(threadFiles = null, projectThreads = 20)).use { editStore -> repeat(25) { editStore.turn(id()) } }
        assertEquals(later, Files.readString(recordFile(thread)))
        assertEquals(20, records())
    }

    @Test fun aFailedWriteNeverThrowsAndTheNextFlushWritesTheWholeRecord() {
        val thread = id()
        val queued = CountDownLatch(1)
        val failed = CountDownLatch(2)
        val writes = AtomicInteger()
        val failing = { file: Path, bytes: ByteArray ->
            val write = writes.incrementAndGet()
            // The opening's write waits until the first edit is queued too, so what follows runs in a fixed order.
            if (write == 1) queued.await(10, TimeUnit.SECONDS)
            if (write <= 2) { failed.countDown(); throw IOException("Disk full") }
            writeDurably(file, bytes)
        }
        editStore(write = failing).use { editStore ->
            val listener = editStore.thread(project, thread, "Implementer · task", false, folder)
            listener.edited(Path.of("a.txt"))
            queued.countDown()
            assertTrue(failed.await(10, TimeUnit.SECONDS))
            assertFalse(Files.exists(recordFile(thread)), "the opening and the first edit failed to write")
            listener.edited(Path.of("b.txt"))
        }
        assertEquals(listOf("a.txt", "b.txt"), read(thread)?.files)
        assertEquals(3, writes.get())
    }

    @Test fun aFailedWriteIsTriedAgainAtTheNextFlushThoughItsThreadChangesNoMore() {
        val thread = id()
        val disk = FlakyDisk(recordFile(thread))
        editStore(write = disk::write).use { editStore ->
            editStore.turn(thread, "a.txt")
            assertTrue(disk.refusedEdit.await(10, TimeUnit.SECONDS))
            assertFalse(Files.exists(recordFile(thread)))
            disk.failing = false
            // Another thread's turn: its flush writes what is still pending.
            editStore.turn(id())
            assertTrue(disk.written.await(10, TimeUnit.SECONDS), "written before the store closes")
        }
        assertEquals(listOf("a.txt"), read(thread)?.files)
    }

    @Test fun closingWritesARecordWhoseLastWriteFailed() {
        val thread = id()
        val disk = FlakyDisk(recordFile(thread))
        editStore(write = disk::write).use { editStore ->
            editStore.turn(thread, "a.txt")
            assertTrue(disk.refusedEdit.await(10, TimeUnit.SECONDS))
            assertFalse(Files.exists(recordFile(thread)))
            disk.failing = false
        }
        assertEquals(listOf("a.txt"), read(thread)?.files)
    }

    @Test fun aClosedStoreDropsEditsWithoutThrowing() {
        val thread = id()
        editStore().use { it.turn(thread, "a.txt") }
        val closed = editStore()
        val listener = closed.thread(project, thread, "Implementer · task", false, folder)
        closed.close()
        listener.edited(Path.of("b.txt"))
        closed.thread(project, id(), "Too late", false, folder).edited(null)
        closed.close()
        assertEquals(listOf("a.txt"), read(thread)?.files)
        assertEquals(1, records(), "nothing is opened after the close")
    }

    @Test fun recordsLeftOutOfMemoryAreReadBackWhenTheirThreadEditsAgain() {
        val threads = List(80) { id() }
        editStore().use { editStore ->
            val first = editStore.thread(project, threads[0], "First", false, folder)
            first.edited(Path.of("a.txt"))
            threads.drop(1).forEach { editStore.turn(it, "x.txt") }
            first.edited(Path.of("b.txt"))
        }
        assertEquals(listOf("a.txt", "b.txt"), read(threads[0])?.files)
        assertEquals(80, records())
    }

    @Test fun onlyRecordIdsNameARecord() {
        editStore().use { editStore ->
            assertFailsWith<IllegalArgumentException> { editStore.thread("../escape", id(), "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { editStore.thread(project, "../../conversations/x", "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { editStore.thread(project, id().uppercase(), "t", false, folder) }
            assertFailsWith<IllegalArgumentException> { editStore.read(project, "x") }
        }
        assertFailsWith<IllegalArgumentException> { ThreadEditStore(root, "not-an-account") }
        assertFalse(Files.exists(root.resolve("providers")), "nothing was written")
    }
}
