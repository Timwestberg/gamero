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
}
