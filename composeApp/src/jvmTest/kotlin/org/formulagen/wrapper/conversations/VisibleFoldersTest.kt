package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.bridge.RuntimeId

class VisibleFoldersTest {
    private val root: Path = folder("visible").resolve("Hovel")
    private val ledgers: Path = folder("ledgers")
    private val account = id()
    private val companion = id()
    private val conversation = id()
    private val at = Instant.parse("2026-11-10T14:28:05Z")
    private val stored = HashMap<String, ByteArray>()
    private val folders = VisibleFolders(root, ledgers, ZoneOffset.UTC) { at }

    private fun names(companionName: String = "Ember", title: String = "Add sign-in", accountId: String = account, companionId: String = companion,
        conversationId: String = conversation) = FolderNames(accountId, companionId, companionName, conversationId, title)

    /** An output as a thread holds it, stored [minutes] after the first. */
    private fun output(text: String, madeBy: String = "TPM", minutes: Long = 0, id: String = id()): RecordedOutput {
        stored[id] = text.toByteArray()
        return RecordedOutput(ArtifactRef(id, "text/markdown", text.take(40), madeBy), madeBy, RuntimeId.CODEX, "gpt-6-luna", at.plusSeconds(minutes * 60))
    }

    private var notes = emptyList<TeamNote>()

    private fun sync(names: FolderNames, vararg outputs: RecordedOutput) =
        folders.sync(names, outputs.toList(), notes) { stored.getValue(it.id) }

    private fun tree(): List<String> = if (!Files.exists(root)) emptyList() else Files.walk(root).use { all ->
        all.filter { Files.isRegularFile(it) }.map { root.relativize(it).toString() }.sorted().toList()
    }

    private val home = "Ember/conversations/2026-11-10 Add sign-in"

    @Test fun anOutputBecomesAReadableFileWithAnIndex() {
        sync(names())
        assertFalse(Files.exists(root), "a conversation that has produced nothing leaves nothing on disk")
        val spec = output("# Shopping list spec\n\nThe list.")
        sync(names(), spec)
        assertEquals(listOf("Ember/.hovel.json", "$home/.hovel.json", "$home/index.md", "$home/outputs/01 Shopping list spec.md"), tree())
        assertEquals("# Shopping list spec\n\nThe list.", Files.readString(root.resolve("$home/outputs/01 Shopping list spec.md")))
        val index = Files.readString(root.resolve("$home/index.md"))
        assertTrue("| 01 | [01 Shopping list spec.md](outputs/01%20Shopping%20list%20spec.md) | TPM | Codex | gpt-6-luna | 2026-11-10 14:28 |" in index, index)
        // It is safe to repeat, and the next output takes the next number.
        sync(names(), spec)
        val reply = output("Looks good to me.", madeBy = "Architect", minutes = 5)
        sync(names(), spec, reply)
        sync(names(), spec, reply)
        assertEquals(listOf("01 Shopping list spec.md", "02 Architect.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') })
    }

    @Test fun theSharedNotesAreCopiedForThePersonAndGoWhenTheNotesGo() {
        val spec = output("# Spec")
        notes = listOf(TeamNote("2026-11-10T14:02:00Z", "TPM", TeamNoteKind.TASK, "Write the spec"))
        sync(names(), spec)
        val file = root.resolve("$home/notes.md")
        assertTrue("## 2026-11-10 14:02 · Task for TPM\n\nWrite the spec" in Files.readString(file), Files.readString(file))
        notes = notes + TeamNote("2026-11-10T14:05:00Z", "TPM", TeamNoteKind.NOTE, "Prices are in cents.")
        sync(names(), spec)
        assertTrue("Note from TPM\n\nPrices are in cents." in Files.readString(file))
        // Memory cleanup erased the notes: the person's copy is Hovel's file, and it goes too. The outputs stay.
        notes = emptyList()
        sync(names(), spec)
        assertFalse(Files.exists(file))
        assertTrue(Files.exists(root.resolve("$home/outputs/01 Spec.md")))
    }

    @Test fun aConversationWithNotesAndNoOutputHasNoFolderYet() {
        notes = listOf(TeamNote("2026-11-10T14:02:00Z", "TPM", TeamNoteKind.TASK, "Write the spec"))
        sync(names())
        assertFalse(Files.exists(root))
    }

    @Test fun aConversationOlderThanTheFoldersIsWrittenOutInTheOrderItWasMade() {
        val later = output("# Second", minutes = 9)
        val earlier = output("# First", minutes = -3 * 24 * 60)
        sync(names(), later, earlier)
        assertEquals(listOf("01 First.md", "02 Second.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') })
        // Its folder carries the day of its first output, not the day it was written out.
        assertTrue(Files.isDirectory(root.resolve("Ember/conversations/2026-11-07 Add sign-in")), tree().toString())
    }

    @Test fun aRenameInHovelRenamesTheFolderAndARenameByHandIsKept() {
        val spec = output("# Spec")
        sync(names(), spec)
        sync(names(companionName = "Juniper", title = "Sign-in, again"), spec)
        assertTrue(Files.isDirectory(root.resolve("Juniper/conversations/2026-11-10 Sign-in, again/outputs")), tree().toString())
        assertFalse(Files.exists(root.resolve("Ember")))
        // The person renames both in Finder: the folders are still found, and their names stay.
        Files.move(root.resolve("Juniper/conversations/2026-11-10 Sign-in, again"), root.resolve("Juniper/conversations/My sign-in work"))
        Files.move(root.resolve("Juniper"), root.resolve("My robot"))
        val next = output("# More", minutes = 3)
        sync(names(companionName = "Juniper", title = "Sign-in, again"), spec, next)
        assertTrue(Files.exists(root.resolve("My robot/conversations/My sign-in work/outputs/02 More.md")), tree().toString())
        sync(names(companionName = "Cedar", title = "Another title"), spec, next)
        assertTrue(Files.exists(root.resolve("My robot/conversations/My sign-in work/outputs/02 More.md")), "a folder renamed by hand is not renamed again")
        assertEquals(1, Files.list(root).use { it.count() })
    }

    @Test fun whatThePersonDeletesIsNotWrittenAgain() {
        val spec = output("# Spec")
        sync(names(), spec)
        Files.delete(root.resolve("$home/outputs/01 Spec.md"))
        sync(names(), spec)
        assertFalse(Files.exists(root.resolve("$home/outputs/01 Spec.md")))
        // The whole folder gone: a new one is made for what comes next, and the old output stays deleted.
        root.resolve("Ember").toFile().deleteRecursively()
        val next = output("# Next", minutes = 2)
        sync(names(), spec, next)
        assertEquals(listOf("02 Next.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') })
    }

    @Test fun anOutputThatCannotBeWrittenStopsNothingAndIsTriedAgain() {
        val first = output("# First")
        val broken = output("# Broken", minutes = 1)
        val last = output("# Last", minutes = 2)
        val bytes = stored.remove(broken.artifact.id)!!
        sync(names(), first, broken, last)
        assertEquals(listOf("01 First.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') }, "it stops at the one it cannot write, to keep the order")
        stored[broken.artifact.id] = bytes
        sync(names(), first, broken, last)
        assertEquals(listOf("01 First.md", "02 Broken.md", "03 Last.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') })
    }

    @Test fun aFileWrittenBeforeAStopIsNotWrittenTwice() {
        val spec = output("# Spec")
        sync(names(), spec)
        // The ledger was not saved: the folder is found by its marker and the file by its bytes.
        Files.delete(ledgers.resolve(account).resolve("companion-folders.json"))
        sync(names(), spec)
        assertEquals(listOf("01 Spec.md"), tree().filter { "/outputs/" in it }.map { it.substringAfterLast('/') })
        // A different file already has the name: the output goes beside it.
        Files.delete(ledgers.resolve(account).resolve("companion-folders.json"))
        Files.writeString(root.resolve("$home/outputs/01 Spec.md"), "the person's own text")
        sync(names(), spec)
        assertEquals("the person's own text", Files.readString(root.resolve("$home/outputs/01 Spec.md")))
        assertEquals("# Spec", Files.readString(root.resolve("$home/outputs/01 Spec 2.md")))
    }

    @Test fun twoAccountsWithACompanionOfTheSameNameGetTheirOwnFolders() {
        sync(names(), output("# Mine"))
        val other = id()
        sync(names(accountId = other, companionId = id(), conversationId = id()), output("# Theirs"))
        assertEquals(setOf("Ember", "Ember 2"), Files.list(root).use { all -> all.map { it.fileName.toString() }.toList().toSet() })
        assertTrue(Files.exists(root.resolve("Ember 2/conversations/2026-11-10 Add sign-in/outputs/01 Theirs.md")))
    }

    @Test fun aLedgerThatCannotBeReadIsLeftAsItIs() {
        val file = Files.createDirectories(ledgers.resolve(account)).resolve("companion-folders.json")
        Files.writeString(file, "{ not json")
        sync(names(), output("# Spec"))
        assertEquals("{ not json", Files.readString(file))
        assertFalse(Files.exists(root), "nothing is written while the ledger cannot be trusted")
    }

    // ---- Shown, behind, and gone -------------------------------------------------------------

    private val revealed = ArrayList<java.nio.file.Path>()
    private val trashed = ArrayList<String>()
    private var trashWorks = true

    /** Folders whose Finder and Trash the test watches: a trashed folder is deleted, as it leaves its place. */
    private val watched = VisibleFolders(root, ledgers, ZoneOffset.UTC, reveal = { revealed.add(it) }, trash = { dir ->
        if (trashWorks) { trashed.add(root.relativize(dir).toString()); dir.toFile().deleteRecursively() }
        trashWorks
    }) { at }

    @Test fun aConversationsFolderIsShownOnceItHasOne() {
        assertEquals(FolderState.NONE, watched.state(account, conversation))
        assertFalse(watched.show(account, conversation), "nothing to show before the first output")
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        assertEquals(FolderState.READY, watched.state(account, conversation))
        assertTrue(watched.show(account, conversation))
        assertEquals(listOf(root.resolve(home)), revealed)
        // The person renamed it: it is still found, by its marker.
        Files.move(root.resolve(home), root.resolve("Ember/conversations/Sign-in work"))
        assertTrue(watched.show(account, conversation))
        assertEquals(root.resolve("Ember/conversations/Sign-in work"), revealed.last())
        // Another account never sees it.
        assertEquals(FolderState.NONE, watched.state(id(), conversation))
    }

    @Test fun aFolderThatCouldNotBeWrittenIsBehindUntilItIs() {
        val broken = output("# Broken")
        val bytes = stored.remove(broken.artifact.id)!!
        watched.sync(names(), listOf(broken), emptyList()) { stored.getValue(it.id) }
        assertEquals(FolderState.BEHIND, watched.state(account, conversation))
        stored[broken.artifact.id] = bytes
        watched.sync(names(), listOf(broken), emptyList()) { stored.getValue(it.id) }
        assertEquals(FolderState.READY, watched.state(account, conversation))
    }

    @Test fun aDeletedConversationsFolderGoesToTheTrashAndOnlyThatOne() {
        val other = id()
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        watched.sync(names(title = "Add sign-out", conversationId = other), listOf(output("# Sign-out")), emptyList()) { stored.getValue(it.id) }
        assertEquals(FolderIds(setOf(companion), setOf(conversation, other)), watched.kept(account))
        watched.gone(account, FolderIds(conversationIds = setOf(conversation, id())))
        assertEquals(listOf(home), trashed)
        assertEquals(FolderIds(setOf(companion), setOf(other)), watched.kept(account))
        assertEquals(FolderState.NONE, watched.state(account, conversation))
        assertTrue(Files.exists(root.resolve("Ember/conversations/2026-11-10 Add sign-out/outputs/01 Sign-out.md")), tree().toString())
        // The companion goes: its folder goes whole, with the person's own files in it, and is still in the Trash.
        Files.writeString(root.resolve("Ember/my notes.txt"), "mine")
        watched.gone(account, FolderIds(companionIds = setOf(companion)))
        assertEquals(listOf(home, "Ember"), trashed)
        assertEquals(FolderIds(), watched.kept(account))
    }

    @Test fun aFolderIsTrashedOnlyWhenItsMarkerSaysItIsThatConversations() {
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        // The person replaced the folder with one of their own under the same name: no marker, so it is not Hovel's.
        root.resolve(home).toFile().deleteRecursively()
        Files.createDirectories(root.resolve(home))
        Files.writeString(root.resolve("$home/mine.txt"), "mine")
        watched.gone(account, FolderIds(conversationIds = setOf(conversation)))
        assertEquals(emptyList(), trashed)
        assertEquals("mine", Files.readString(root.resolve("$home/mine.txt")))
        assertEquals(emptySet(), watched.kept(account).conversationIds, "the folder Hovel made is gone, so it is forgotten")
        // Another account's answer never reaches this account's folders.
        watched.sync(names(), listOf(output("# Again")), emptyList()) { stored.getValue(it.id) }
        watched.gone(id(), FolderIds(setOf(companion), setOf(conversation)))
        assertEquals(emptyList(), trashed)
    }

    @Test fun aFolderTheTrashRefusesStaysAndIsTriedAgain() {
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        trashWorks = false
        watched.gone(account, FolderIds(conversationIds = setOf(conversation)))
        assertTrue(Files.exists(root.resolve("$home/outputs/01 Spec.md")))
        assertEquals(setOf(conversation), watched.kept(account).conversationIds, "still kept, so the next connect asks again")
        trashWorks = true
        watched.gone(account, FolderIds(conversationIds = setOf(conversation)))
        assertEquals(listOf(home), trashed)
    }

    @Test fun atConnectTheAccountSaysWhichFoldersBelongToDeletedConversations() {
        val other = id()
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        watched.sync(names(title = "Add sign-out", conversationId = other), listOf(output("# Sign-out")), emptyList()) { stored.getValue(it.id) }
        val asked = ArrayList<FoldersKept>()
        var answer: (FoldersKept) -> FoldersKept = { FoldersKept() }
        val server = object : ConversationApi by FakeServer() {
            override fun foldersGone(kept: FoldersKept): FoldersKept { asked += kept; return answer(kept) }
        }
        // A server older than the route refuses it for good: nothing is gone.
        answer = { throw org.formulagen.wrapper.hovel.HovelStatusException(404) }
        ConversationHost.discardDeletedFolders(server, watched, account)
        assertEquals(emptyList(), trashed)
        assertEquals(FoldersKept(listOf(companion), listOf(conversation, other)).let { it.copy(conversationIds = it.conversationIds.sorted()) },
            asked.single().let { it.copy(conversationIds = it.conversationIds.sorted()) })
        // It names one deleted conversation, and an id nobody asked about, which changes nothing.
        answer = { FoldersKept(listOf(id()), listOf(other, id())) }
        ConversationHost.discardDeletedFolders(server, watched, account)
        assertEquals(listOf("Ember/conversations/2026-11-10 Add sign-out"), trashed)
        assertEquals(FolderIds(setOf(companion), setOf(conversation)), watched.kept(account))
        // An account with no folders asks nothing.
        ConversationHost.discardDeletedFolders(server, watched, id())
        assertEquals(2, asked.size)
    }

    // ---- Scratch ---------------------------------------------------------------------------------

    @Test fun aWorkingTurnsScratchFolderIsMadeBeforeAnyOutputAndIsNeverInTheIndex() {
        val scratch = assertNotNull(watched.scratch(names()))
        assertEquals(root.resolve("$home/scratch"), scratch)
        assertTrue(Files.isDirectory(scratch) && scratch.isAbsolute)
        assertEquals(scratch, watched.scratch(names()), "the same folder every time")
        assertEquals(FolderState.READY, watched.state(account, conversation))
        // An output then lands beside it, and the index lists outputs only.
        Files.writeString(scratch.resolve("draft.md"), "a draft")
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        assertTrue(Files.exists(root.resolve("$home/outputs/01 Spec.md")), tree().toString())
        val index = Files.readString(root.resolve("$home/index.md"))
        assertFalse("draft" in index || "scratch" in index, index)
        assertEquals("a draft", Files.readString(scratch.resolve("draft.md")), "a sync never touches scratch")
    }

    @Test fun scratchIsEmptiedWhenItsRunCompletesAndNothingElseIs() {
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        val scratch = assertNotNull(watched.scratch(names()))
        val elsewhere = folder("elsewhere")
        val kept = Files.writeString(elsewhere.resolve("keep.txt"), "keep")
        Files.writeString(scratch.resolve("draft.md"), "a draft")
        Files.writeString(Files.createDirectories(scratch.resolve("logs/run")).resolve("test.log"), "ok")
        Files.createSymbolicLink(scratch.resolve("link.txt"), kept)
        Files.createSymbolicLink(scratch.resolve("linked-folder"), elsewhere)
        // Another account's run never empties this one.
        watched.emptyScratch(id(), conversation)
        assertTrue(Files.exists(scratch.resolve("draft.md")))
        watched.emptyScratch(account, conversation)
        assertEquals(emptyList(), Files.list(scratch).use { it.toList() })
        assertTrue(Files.isDirectory(scratch), "the folder stays for the next run")
        assertEquals("keep", Files.readString(kept), "a link is deleted as a link, never followed")
        assertTrue(Files.exists(root.resolve("$home/outputs/01 Spec.md")), "outputs are the person's and stay")
    }

    @Test fun aScratchFileARunNeverClearedGoesAfterAWeek() {
        val scratch = assertNotNull(watched.scratch(names()))
        fun aged(file: java.nio.file.Path, days: Long) =
            Files.setLastModifiedTime(Files.writeString(file, "x"), java.nio.file.attribute.FileTime.from(at.minusSeconds(days * 86_400)))
        val old = aged(scratch.resolve("old.log"), 8)
        val oldInside = aged(Files.createDirectories(scratch.resolve("logs")).resolve("old.txt"), 30)
        val fresh = aged(scratch.resolve("fresh.md"), 6)
        val mixed = Files.createDirectories(scratch.resolve("mixed"))
        aged(mixed.resolve("old.txt"), 9)
        val keptInside = aged(mixed.resolve("new.txt"), 1)
        watched.sweepScratch(account)
        assertFalse(Files.exists(old) || Files.exists(oldInside))
        assertFalse(Files.exists(scratch.resolve("logs")), "a folder with nothing left goes too")
        assertTrue(Files.exists(fresh) && Files.exists(keptInside), tree().toString())
        assertFalse(Files.exists(mixed.resolve("old.txt")))
        // An output is never swept, however old it is.
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        val spec = root.resolve("$home/outputs/01 Spec.md")
        Files.setLastModifiedTime(spec, java.nio.file.attribute.FileTime.from(at.minusSeconds(400L * 86_400)))
        watched.sweepScratch(account)
        assertTrue(Files.exists(spec))
    }

    @Test fun aLinkWhereTheScratchFolderShouldBeIsNeverGivenToAnAgent() {
        watched.sync(names(), listOf(output("# Spec")), emptyList()) { stored.getValue(it.id) }
        val elsewhere = folder("elsewhere")
        val theirs = Files.writeString(elsewhere.resolve("theirs.txt"), "theirs")
        Files.createSymbolicLink(root.resolve("$home/scratch"), elsewhere)
        assertNull(watched.scratch(names()), "write access is never given through a link")
        watched.emptyScratch(account, conversation)
        watched.sweepScratch(account)
        assertEquals("theirs", Files.readString(theirs))
        // A Mac that keeps no visible folders gives no scratch.
        assertNull(CompanionFolders.NONE.scratch(names()))
    }

    @Test fun aFolderWithoutTheMarkerIsNeverTaken() {
        Files.createDirectories(root.resolve("Ember/conversations"))
        Files.writeString(root.resolve("Ember/notes.txt"), "the person's own folder")
        sync(names(), output("# Spec"))
        assertEquals("the person's own folder", Files.readString(root.resolve("Ember/notes.txt")))
        assertTrue(Files.exists(root.resolve("Ember 2/conversations/2026-11-10 Add sign-in/outputs/01 Spec.md")), tree().toString())
    }
}
