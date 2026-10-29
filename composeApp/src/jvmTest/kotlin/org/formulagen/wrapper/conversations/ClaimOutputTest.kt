package org.formulagen.wrapper.conversations

import kotlin.test.*
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.hovel.HovelRejectedException

class ClaimOutputTest {
    private val server = FakeServer()
    private val talk = TestConversation()
    private val claim = run {
        server.queue(talk.id, Queued(talk.message("Go")) { claimId, message -> talk.claim(claimId, message, brain()) })
        (server.claim(ClaimRequest(id(), talk.id)) as ClaimResponse.Claimed).claim
    }
    private val waits = mutableListOf<Long>()

    @Test fun deltasAreNumberedPerReplyCutToSizeAndSentInOrder() {
        ClaimOutput(server, claim.claimId, claim.message.id).use { output ->
            val reply = id(); val other = id(); val thread = id()
            output.delta(reply, null, "Hel")
            output.delta(reply, null, "lo")
            output.delta(other, thread, "x".repeat(CompanionLimits.DELTA + 10))
            output.delta(reply, null, "")
            output.drain()
        }
        val forReply = server.deltas.filter { it.threadId == null }
        assertEquals("Hello", forReply.joinToString("") { it.text })
        assertEquals((0 until forReply.size).toList(), forReply.map { it.seq })
        val long = server.deltas.filter { it.threadId != null }
        assertEquals(listOf(CompanionLimits.DELTA, 10), long.map { it.text.length })
        assertEquals(listOf(0, 1), long.map { it.seq })
    }

    @Test fun piecesNeverSplitASurrogatePair() {
        val text = "a".repeat(CompanionLimits.DELTA - 1) + "😀" + "b"
        val pieces = ClaimOutput.pieces(text)
        assertEquals(text, pieces.joinToString(""))
        assertTrue(pieces.all { it.length <= CompanionLimits.DELTA && !it.last().isHighSurrogate() })
    }

    @Test fun aCheckpointIsRetriedUntilStoredAndARefusalIsThrown() {
        server.offline = true
        val output = ClaimOutput(server, claim.claimId, claim.message.id, attempts = 3, backoffMillis = 10) { waits += it; server.offline = waits.size < 2 }
        val run = MessageCheckpoint.RunChanged(ConversationRun(4, null, 0, id(), 0, 1, emptyList(), org.formulagen.wrapper.bridge.BrainRunState.PLANNING, null))
        output.checkpoint(run)
        assertEquals(listOf(10L, 20L), waits)
        assertEquals(listOf<MessageCheckpoint>(run), server.checkpoints)
        server.offline = true
        assertFails { ClaimOutput(server, claim.claimId, claim.message.id, attempts = 2, backoffMillis = 1) {}.checkpoint(run) }
        server.offline = false
        val closed = MessageCheckpoint.ApprovalClosed(id(), ApprovalOutcome.Withdrawn)
        server.approvals[closed.approvalId] = AgentApproval(ApprovalAnswerRecord(ApprovalSurface.PHONE, id(), ApprovalOption.DENY), id(), talk.id, AT,
            closed.approvalId, ApprovalKind.PERMISSION, listOf(ApprovalOption.ALLOW_ONCE, ApprovalOption.DENY), ApprovalPresentation.MacOnly("x"), 1,
            ApprovalState.ANSWERED, null)
        assertFailsWith<HovelRejectedException> { output.checkpoint(closed) }
        assertFalse(output.bestEffort(closed), "an approval's close is best effort")
    }
}
