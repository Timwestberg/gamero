package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.ProjectFolders
import org.formulagen.wrapper.workspace.FolderPicker

class ProjectFoldersTest {
    private val root = folder("project-folders")
    private val disk = folder("project-disk")
    private val account = id()
    private val project = id()
    @Volatile private var picked: Path? = null
    private val titles = mutableListOf<String>()
    private val store = ProjectFolderStore(root, FolderPicker { title, _ -> titles += title; picked })

    private fun dir(name: String): Path = Files.createDirectories(disk.resolve(name)).toRealPath()
    private fun choose(path: Path?, role: FolderRole): List<LocalFolder> { picked = path; return store.choose(account, project, role) }

    /** A claim or read of the project told this Mac its [revision] and the [folders] the account lists. */
    private fun seen(revision: Long, folders: List<WireProjectFolder> = emptyList(), computerId: String = id()) =
        store.claimed(account, CompanionProject(false, id(), computerId, folders, project, "Hovel notes", revision))

    @Test fun aWorkingFolderReplacesTheOneBeforeAndContextFoldersJoin() {
        val first = choose(dir("hovel-notes"), FolderRole.WORKING).single()
        assertEquals("hovel-notes", first.name)
        val second = choose(dir("hovel-next"), FolderRole.WORKING).single()
        assertNotEquals(first.id, second.id, "another folder is another folder")
        choose(dir("docs"), FolderRole.CONTEXT)
        val folders = choose(dir("specs"), FolderRole.CONTEXT)
        assertEquals(listOf(FolderRole.WORKING, FolderRole.CONTEXT, FolderRole.CONTEXT), folders.map { it.role })
        assertEquals(ProjectFolders(disk.resolve("hovel-next"), listOf(disk.resolve("docs"), disk.resolve("specs"))),
            store.projectFolders(account, project))
        assertEquals(listOf("Choose the project's working folder", "Choose the project's working folder",
            "Choose a context folder agents may only read", "Choose a context folder agents may only read"), titles)
    }

    @Test fun cancellingOrChoosingAFolderTwiceChangesNothing() {
        val docs = dir("docs")
        val before = choose(docs, FolderRole.CONTEXT)
        assertEquals(before, choose(null, FolderRole.CONTEXT))
        assertEquals(before, choose(docs, FolderRole.WORKING))
        assertEquals(before, choose(disk.resolve("docs/../docs"), FolderRole.CONTEXT))
    }

    @Test fun contextFoldersStopAtTheContractsLimit() {
        repeat(CompanionLimits.CONTEXT_FOLDERS) { choose(dir("context-$it"), FolderRole.CONTEXT) }
        picked = dir("one-more")
        assertFails { store.choose(account, project, FolderRole.CONTEXT) }
        assertEquals(CompanionLimits.CONTEXT_FOLDERS, store.folders(account, project).size)
    }

    @Test fun sameNamedFoldersAreToldApart() {
        choose(dir("a/docs"), FolderRole.CONTEXT)
        choose(dir("b/docs"), FolderRole.CONTEXT)
        choose(dir("c/DOCS"), FolderRole.CONTEXT)
        assertEquals(listOf("docs", "docs (2)", "DOCS (3)"), store.folders(account, project).map { it.name })
    }

    @Test fun forgettingAFolderLeavesItOnDisk() {
        val docs = dir("docs")
        val folder = choose(docs, FolderRole.CONTEXT).single()
        assertEquals(emptyList(), store.forget(account, project, folder.id))
        assertTrue(Files.isDirectory(docs))
        assertEquals(emptyList(), store.forget(account, project, id()), "an unknown folder changes nothing")
    }

    @Test fun aMovedFolderStillListsButNeverRuns() {
        val gone = dir("gone")
        choose(gone, FolderRole.WORKING)
        Files.delete(gone)
        assertFalse(store.folders(account, project).single().exists)
        assertEquals(ProjectFolders(null), store.projectFolders(account, project))
    }

    @Test fun foldersArePerAccountAndProject() {
        choose(dir("mine"), FolderRole.WORKING)
        assertEquals(emptyList(), store.folders(id(), project))
        assertEquals(emptyList(), store.folders(account, id()))
        assertFails { store.folders("local", project) }
        assertFails { store.folders(account, project.uppercase()) }
    }

    @Test fun aCorruptRecordIsNeverOverwritten() {
        choose(dir("mine"), FolderRole.WORKING)
        val file = root.resolve(account).resolve("project-folders.json")
        Files.writeString(file, "{\"version\":1,\"projects\":{\"$project\":{\"folders\":[{\"id\":\"${id()}\",\"name\":\"x\",\"path\":\"relative\",\"role\":\"working\"}]}}}")
        assertFails { store.folders(account, project) }
        picked = dir("other")
        assertFails { store.choose(account, project, FolderRole.WORKING) }
        assertTrue(Files.readString(file).contains("relative"))
    }

    @Test fun theAccountGetsTheFoldersAgainstTheRevisionLastSeen() {
        val server = FakeServer()
        val publisher = ProjectFolderPublisher(store, server, account, server.hostId) { listOf(RuntimeId.CLAUDE_CODE) }
        choose(dir("hovel-notes"), FolderRole.WORKING)
        choose(dir("docs"), FolderRole.CONTEXT)
        publisher.publish(project)
        assertEquals(emptyList(), server.folderSaves, "no revision known yet: the folders wait")
        seen(3)
        publisher.publish(project)
        val (savedProject, save) = server.folderSaves.single()
        assertEquals(project, savedProject)
        assertEquals(3, save.expectedRevision)
        assertEquals(listOf("hovel-notes" to emptyList(), "docs" to listOf(RuntimeId.CLAUDE_CODE)), save.folders.map { it.name to it.readableBy })
        assertEquals(emptyMap(), store.unpublished(account))
        choose(dir("specs"), FolderRole.CONTEXT)
        publisher.publishPending()
        assertEquals(4, server.folderSaves.last().second.expectedRevision, "the next save is against the revision the last one made")
    }

    @Test fun aWorkingFolderChosenAfterAContextFolderIsPublishedOnce() {
        val server = FakeServer()
        val publisher = ProjectFolderPublisher(store, server, account, server.hostId) { emptyList() }
        seen(1)
        choose(dir("docs"), FolderRole.CONTEXT)
        choose(dir("hovel-notes"), FolderRole.WORKING)
        publisher.publishPending()
        publisher.publishPending()
        assertEquals(1, server.folderSaves.size)
        assertEquals(listOf(FolderRole.WORKING, FolderRole.CONTEXT), server.folderSaves.single().second.folders.map { it.role })
    }

    @Test fun aConflictReadsTheProjectAgainAndARefusalMeansAnotherComputer() {
        val server = FakeServer()
        val publisher = ProjectFolderPublisher(store, server, account, server.hostId) { emptyList() }
        seen(5)
        choose(dir("hovel-notes"), FolderRole.WORKING)
        // Another device saved the project since this Mac last saw it.
        server.projects[project] = CompanionProject(false, id(), server.hostId, emptyList(), project, "Hovel notes", 6)
        publisher.publish(project)
        assertEquals(emptyList(), server.folderSaves)
        assertEquals(mapOf(project to 6L), store.unpublished(account), "the stale save read the project again")
        publisher.publish(project)
        assertEquals(6, server.folderSaves.single().second.expectedRevision)
        choose(dir("docs"), FolderRole.CONTEXT)
        server.foldersStatus = 404
        publisher.publish(project)
        assertTrue(store.isForeign(account, project))
        assertEquals(emptyMap(), store.unpublished(account))
        picked = dir("again")
        assertFails { store.choose(account, project, FolderRole.WORKING) }
        seen(8)
        assertFalse(store.isForeign(account, project), "a claim of the project says it runs here")
    }

    @Test fun aProjectNeverClaimedHereIsReadForItsRevision() {
        val server = FakeServer()
        val publisher = ProjectFolderPublisher(store, server, account, server.hostId) { emptyList() }
        choose(dir("hovel-notes"), FolderRole.WORKING)
        server.projects[project] = CompanionProject(false, id(), server.hostId, emptyList(), project, "Hovel notes", 7)
        publisher.publish(project)
        assertEquals(7, server.folderSaves.single().second.expectedRevision, "read, then saved against the account's revision")
        assertEquals(emptyMap(), store.unpublished(account))
        // A project on another computer is never written to.
        val elsewhere = id()
        picked = dir("elsewhere")
        store.choose(account, elsewhere, FolderRole.WORKING)
        server.projects[elsewhere] = CompanionProject(false, id(), id(), emptyList(), elsewhere, "Theirs", 2)
        publisher.publish(elsewhere)
        assertEquals(1, server.folderSaves.size)
        assertTrue(store.isForeign(account, elsewhere))
    }

    @Test fun projectChangedPublishesWhenTheAccountListsOtherFolders() {
        val server = FakeServer()
        val publisher = ProjectFolderPublisher(store, server, account, server.hostId) { emptyList() }
        choose(dir("hovel-notes"), FolderRole.WORKING)
        server.projects[project] = CompanionProject(false, id(), server.hostId, emptyList(), project, "Hovel notes", 2)
        publisher.publish(project)
        val published = server.folderSaves.single().second.folders
        assertEquals(emptyMap(), store.unpublished(account))
        publisher.refresh(project)
        assertEquals(1, server.folderSaves.size, "the account already lists these folders")
        // Another device cleared them: the account now lists none, so this Mac's are published again.
        server.projects[project] = server.projects.getValue(project).copy(folders = emptyList(), revision = 9)
        publisher.refresh(project)
        assertEquals(2, server.folderSaves.size)
        assertEquals(published, server.folderSaves.last().second.folders)
        assertEquals(9, server.folderSaves.last().second.expectedRevision)
    }

    @Test fun aClaimWhoseFoldersDifferMarksTheProjectUnpublished() {
        val working = choose(dir("hovel-notes"), FolderRole.WORKING).single()
        seen(3, listOf(WireProjectFolder(working.id, working.name, emptyList(), FolderRole.WORKING)))
        store.published(account, project, listOf(WireProjectFolder(working.id, working.name, emptyList(), FolderRole.WORKING)), 4)
        assertEquals(emptyMap(), store.unpublished(account))
        seen(4, listOf(WireProjectFolder(working.id, working.name, emptyList(), FolderRole.WORKING)))
        assertEquals(emptyMap(), store.unpublished(account), "the same folders: nothing to publish")
        seen(5)
        assertEquals(mapOf(project to 5L), store.unpublished(account), "the account lost them")
    }

    @Test fun theBridgeAnswersStatusNeverAPath() = runBlocking<Unit> {
        val changed = mutableListOf<String>()
        var registered: String? = null
        val projects = DesktopProjects(store, { registered }, { listOf(RuntimeId.CODEX) },
            changed = { _, projectId -> changed += projectId })
        assertFails("not connected yet") { projects.folderStatus(ProjectScope(project)) }
        registered = account
        picked = dir("hovel-notes")
        val working = projects.chooseFolder(ProjectChooseFolderArgs(project, FolderRole.WORKING)).folders.single()
        assertEquals(ProjectFolderStatus(true, working.id, "hovel-notes", emptyList(), FolderRole.WORKING), working)
        picked = dir("docs")
        val status = projects.chooseFolder(ProjectChooseFolderArgs(project, FolderRole.CONTEXT))
        assertEquals(listOf(RuntimeId.CODEX), status.folders.last().readableBy)
        assertEquals(status, projects.folderStatus(ProjectScope(project)))
        picked = null
        assertEquals(status, projects.chooseFolder(ProjectChooseFolderArgs(project, FolderRole.CONTEXT)), "cancelled")
        assertEquals(listOf(project, project), changed, "only a change is published")
        val after = projects.forgetFolder(ProjectForgetFolderArgs(project, working.id))
        assertEquals(listOf("docs"), after.folders.map { it.name })
        assertEquals(3, changed.size)
        assertFalse(bridgeJson.encodeToString(ProjectFoldersStatus.serializer(), after).contains(disk.toString()))
        store.foreign(account, project)
        assertFails { projects.folderStatus(ProjectScope(project)) }
    }

    @Test fun folderStatusAnswersOnlyForAProjectTheAccountGivesThisMac() = runBlocking<Unit> {
        var verdict: Boolean? = null
        val projects = DesktopProjects(store, { account }, { emptyList() }, changed = { _, _ -> }, owns = { _, _ -> verdict })
        projects.folderStatus(ProjectScope(project))
        verdict = false
        assertFails("the account runs it on another computer") { projects.folderStatus(ProjectScope(project)) }
        store.foreign(account, project)
        verdict = true
        projects.folderStatus(ProjectScope(project))
        verdict = null
        assertFails("with no word from the account, what this Mac learned stands") { projects.folderStatus(ProjectScope(project)) }

        val server = FakeServer()
        val other = id()
        assertEquals(false, ConversationHost.projectOnThisMac(server, store, account, server.hostId, other),
            "the server gives it to no one here")
        assertTrue(store.isForeign(account, other))
        server.projects[other] = CompanionProject(false, id(), server.hostId, emptyList(), other, "Project", 1)
        assertEquals(true, ConversationHost.projectOnThisMac(server, store, account, server.hostId, other))
        assertFalse(store.isForeign(account, other), "the account's word makes its folders choosable here again")
        picked = dir("moved-back")
        assertEquals(listOf("moved-back"), store.choose(account, other, FolderRole.WORKING).map { it.name })
        server.projects[other] = CompanionProject(false, id(), id(), emptyList(), other, "Project", 2)
        assertEquals(false, ConversationHost.projectOnThisMac(server, store, account, server.hostId, other), "it moved to another Mac")
        assertTrue(store.isForeign(account, other))

        // A newer read marks it foreign while the account's older answer is on its way: the mark stands.
        server.projects[other] = CompanionProject(false, id(), server.hostId, emptyList(), other, "Project", 3)
        val racing = object : ConversationApi by server {
            override fun project(projectId: String) = server.project(projectId).also { store.foreign(account, projectId) }
        }
        assertEquals(false, ConversationHost.projectOnThisMac(racing, store, account, server.hostId, other))
        assertTrue(store.isForeign(account, other), "an answer asked for before the mark never clears it")
    }

    @Test fun aConfirmedProjectAnswersAtOnceUntilMarkedForeignAndIsCheckedOnceAtATime() {
        val server = FakeServer()
        val other = id()
        server.projects[other] = CompanionProject(false, id(), server.hostId, emptyList(), other, "Project", 1)
        val checks = mutableListOf<Runnable>()
        val onThisMac = ProjectsOnThisMac(server, store, account, server.hostId) { checks += it }
        assertEquals(true, onThisMac.answer(other), "the account confirms it")
        assertTrue(checks.isEmpty())
        assertEquals(true, onThisMac.answer(other))
        assertEquals(true, onThisMac.answer(other))
        assertEquals(1, checks.size, "one background check per project at a time")
        checks.removeFirst().run()
        assertEquals(true, onThisMac.answer(other))
        assertEquals(1, checks.size, "the next check waits for the last one to finish")

        // A read elsewhere finds it moved: the mark sends the answer back to the account.
        server.projects[other] = CompanionProject(false, id(), id(), emptyList(), other, "Project", 2)
        store.foreign(account, other)
        assertEquals(false, onThisMac.answer(other))
        assertEquals(1, checks.size, "no answer from memory, so no new background check")
        checks.removeFirst().run()
        assertTrue(store.isForeign(account, other))
    }
}
