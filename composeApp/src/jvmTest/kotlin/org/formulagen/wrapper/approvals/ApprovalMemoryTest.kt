package org.formulagen.wrapper.approvals

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.conversation.TeamMember

class ApprovalMemoryTest {
    private val root: Path = Files.createTempDirectory("approvals").toRealPath()
    private val account = UUID.randomUUID().toString()
    private val conversation = UUID.randomUUID().toString()
    private val project = UUID.randomUUID().toString()
    private val folder = Path.of("/Users/someone/code/shop")
    private val codex = TeamMember(RuntimeId.CODEX, "gpt-6-luna")

    @Test fun aStartOfWorkApprovalOutlivesTheApp() {
        val first = StartOfWork(ApprovalMemory(root, account))
        assertFalse(first.isConfirmed(conversation, project, folder, codex))
        first.remember(conversation, project, folder, codex)
        assertTrue(first.isConfirmed(conversation, project, folder, codex))
        // The app starts again: nothing is in memory, and the person is not asked again.
        val restarted = StartOfWork(ApprovalMemory(root, account))
        assertTrue(restarted.isConfirmed(conversation, project, folder, codex))
        // It is an approval of exactly what the dialog named.
        assertFalse(restarted.isConfirmed(conversation, project, Path.of("/Users/someone/code/other"), codex), "another folder asks")
        assertFalse(restarted.isConfirmed(conversation, project, folder, TeamMember(RuntimeId.CLAUDE_CODE, "opus")), "another provider asks")
        assertFalse(restarted.isConfirmed(conversation, project, folder, TeamMember(RuntimeId.CODEX, "another-model")), "another model asks")
        assertFalse(restarted.isConfirmed(UUID.randomUUID().toString(), project, folder, codex), "another conversation asks")
        assertFalse(StartOfWork(ApprovalMemory(root, UUID.randomUUID().toString())).isConfirmed(conversation, project, folder, codex), "another account asks")
    }

    @Test fun withoutAMemoryAnApprovalLastsUntilTheAppQuits() {
        val only = StartOfWork()
        only.remember(conversation, project, folder, codex)
        assertTrue(only.isConfirmed(conversation, project, folder, codex))
        assertFalse(StartOfWork().isConfirmed(conversation, project, folder, codex))
    }

    @Test fun anApprovalCanBeTakenBackAndReadingIsNotWorking() {
        val memory = ApprovalMemory(root, account)
        val work = Approved(conversation, project, folder.toString(), RuntimeId.CODEX, "gpt-6-luna", ApprovedFor.WORK)
        val read = work.copy(purpose = ApprovedFor.READ)
        memory.remember(work)
        memory.remember(work)
        assertEquals(listOf(work), memory.all())
        assertFalse(memory.has(read), "an approval to work is not one to read")
        memory.remember(read)
        memory.withdraw(work)
        assertEquals(listOf(read), ApprovalMemory(root, account).all())
        assertFalse(StartOfWork(ApprovalMemory(root, account)).isConfirmed(conversation, project, folder, codex), "taken back, it asks again")
    }

    @Test fun aFileThatCannotBeReadMeansAskAgainAndIsLeftAlone() {
        val file = Files.createDirectories(root.resolve(account)).resolve("approvals.json")
        Files.writeString(file, "{ not json")
        val memory = ApprovalMemory(root, account)
        val work = Approved(conversation, project, folder.toString(), RuntimeId.CODEX, "gpt-6-luna", ApprovedFor.WORK)
        assertFalse(memory.has(work))
        memory.remember(work)
        assertEquals("{ not json", Files.readString(file), "nothing is assumed and nothing is overwritten")
        assertFalse(memory.has(work))
    }

    @Test fun aKeptApprovalHasANameThatSaysNothingOfTheFolderAndCanBeWithdrawnByIt() {
        val start = StartOfWork(ApprovalMemory(root, account))
        start.remember(conversation, project, folder, codex)
        start.remember(conversation, project, folder, codex, ApprovedFor.READ)
        start.remember(conversation, UUID.randomUUID().toString(), folder, codex)
        val kept = start.kept(project)
        assertEquals(listOf(ApprovedFor.WORK, ApprovedFor.READ), kept.map { it.purpose })
        assertTrue(kept.all { Regex("^[0-9a-f]{64}$").matches(it.id) }, kept.map { it.id }.toString())
        assertEquals(2, kept.map { it.id }.toSet().size, "reading and working are two approvals")
        assertEquals(kept[0].id, kept[0].copy().id, "the same approval always has the same name")
        // Reading is taken back: working stays, and reading asks again, after a restart too.
        start.withdraw(project, kept[1].id)
        assertFalse(start.isConfirmed(conversation, project, folder, codex, ApprovedFor.READ))
        assertTrue(start.isConfirmed(conversation, project, folder, codex))
        val restarted = StartOfWork(ApprovalMemory(root, account))
        assertEquals(listOf(ApprovedFor.WORK), restarted.kept(project).map { it.purpose })
        // A name of another project's approval, or of none, changes nothing.
        restarted.withdraw(UUID.randomUUID().toString(), kept[0].id)
        restarted.withdraw(project, "0".repeat(64))
        assertTrue(restarted.isConfirmed(conversation, project, folder, codex))
    }
}
