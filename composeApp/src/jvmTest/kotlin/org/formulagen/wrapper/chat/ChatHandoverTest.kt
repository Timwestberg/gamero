package org.formulagen.wrapper.chat

import java.util.UUID
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

/** The pure handover transforms: what each switch records, what each runtime is owed, and the bounds on both. */
class ChatHandoverTest {
    private val codex = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
    private val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
    private val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
    private val thread = NativeConversation("10000000-0000-4000-8000-000000000001", "")
    private val session = NativeConversation("10000000-0000-4000-8000-000000000002", "")

    private fun id() = UUID.randomUUID().toString()
    private fun user(text: String) = ChatEntry.User(id(), text)
    private fun reply(author: TeamMember, text: String) = ChatEntry.Assistant(id(), author, text)
    private fun notes(record: ChatRecord) = record.entries.filterIsInstance<ChatEntry.Handover>()

    @Test fun recentMessagesKeepTheLastTenWithinEightThousandCharacters() {
        val entries = (1..14).flatMap { listOf(user("question $it"), reply(codex, "answer $it")) }
        val block = assertNotNull(Handovers.recentMessages(entries))
        val lines = block.removePrefix("Earlier in this conversation (the most recent messages):\n<<<\n").removeSuffix("\n>>>").lines()
        assertEquals(HandoverLimits.RECENT_MESSAGES, lines.size)
        assertEquals("Person: question 10", lines.first()); assertEquals("Companion: answer 14", lines.last())
        // The oldest message that does not fit keeps its speaker and its end, marked; older ones drop.
        val long = listOf(user("a".repeat(500)), reply(codex, "b".repeat(5000)), user("c".repeat(4000)))
        val cut = assertNotNull(Handovers.recentMessages(long)).lines().drop(2).dropLast(1)
        assertEquals(2, cut.size)
        assertTrue(cut[0].startsWith("Companion: ${Handovers.SHORTENED}b"), cut[0])
        assertEquals("Person: " + "c".repeat(4000), cut[1])
        assertEquals(HandoverLimits.RECENT_CHARS, cut.sumOf { it.length })
        // A cut shorter than its minimum is left out; a message that said nothing is never handed over.
        assertEquals(listOf("Person: " + "c".repeat(7900)), Handovers.recentMessages(listOf(user("x".repeat(1000)), user("c".repeat(7900))))!!.lines().drop(2).dropLast(1))
        assertNull(Handovers.recentMessages(listOf(user("  "), reply(codex, ""))))
    }

    @Test fun aSwitchRecordsHowTheIncomingMemberLearnedTheConversation() {
        val talked = ChatRecord(conversations = mapOf(RuntimeId.CODEX to thread), entries = listOf(user("Hi"), reply(codex, "Hello")))
        assertEquals(HandoverPlan.Record(HandoverMethod.SAME_CONVERSATION, null), Handovers.plan(talked, sonnet, opus, fromCanSummarize = true))
        assertEquals(HandoverPlan.Summarize("Person: Hi\nCompanion: Hello", Handovers.recentMessages(talked.entries)!!), Handovers.plan(talked, codex, sonnet, true))
        assertEquals(HandoverPlan.Record(HandoverMethod.RECENT_MESSAGES, Handovers.recentMessages(talked.entries)), Handovers.plan(talked, codex, sonnet, false))
        assertEquals(HandoverPlan.Record(HandoverMethod.FRESH, null), Handovers.plan(ChatRecord(), null, codex, false))
        // Back to a runtime that missed nothing: it is up to date with its own conversation.
        val back = talked.copy(conversations = talked.conversations + (RuntimeId.CLAUDE_CODE to session), entries = talked.entries + reply(sonnet, "Hi from Claude"))
        assertEquals(HandoverPlan.Record(HandoverMethod.UP_TO_DATE, null), Handovers.plan(back.copy(entries = back.entries + reply(codex, "Back")), sonnet, codex, true))
        val recorded = Handovers.handover(talked, Handovers.entry(codex, sonnet, HandoverMethod.SUMMARY, HandoverReason.MANUAL, "They said hi."), "owed")
        assertEquals(mapOf(RuntimeId.CLAUDE_CODE to "owed"), recorded.pendingContext)
        assertEquals("They said hi.", notes(recorded).single().summary)
        assertNull(Handovers.entry(codex, sonnet, HandoverMethod.RECENT_MESSAGES, HandoverReason.MANUAL, "ignored").summary)
    }

    @Test fun theHostNotesItsOwnSwitchesWithReasonHost() {
        // A runtime's first turn: fresh in an empty conversation, recent messages after earlier ones.
        val fresh = assertNotNull(Handovers.join(ChatRecord(), codex))
        assertEquals(listOf(null, codex, HandoverMethod.FRESH, HandoverReason.HOST), notes(fresh).single().let { listOf(it.from, it.to, it.method, it.reason) })
        assertTrue(fresh.pendingContext.isEmpty())
        val talked = ChatRecord(conversations = mapOf(RuntimeId.CODEX to thread), entries = fresh.entries + user("Hi") + reply(codex, "Hello"))
        val joined = assertNotNull(Handovers.join(talked, sonnet))
        assertEquals(listOf(codex, sonnet, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), notes(joined).last().let { listOf(it.from, it.to, it.method, it.reason) })
        assertEquals(Handovers.recentMessages(talked.entries), Handovers.owedTo(joined, RuntimeId.CLAUDE_CODE))
        assertNull(Handovers.join(joined, opus), "Already noted as taking over")
        assertNull(Handovers.join(talked, codex), "Codex has its own conversation")
        // A model change made by a send.
        val changed = assertNotNull(Handovers.modelChange(talked, TeamMember(RuntimeId.CODEX, "gpt-6-luna")))
        assertEquals(HandoverReason.HOST, notes(changed).last().reason)
        assertEquals(HandoverMethod.SAME_CONVERSATION, notes(changed).last().method)
        assertNull(Handovers.modelChange(talked, codex)); assertNull(Handovers.modelChange(talked, sonnet))
        // Catching up a runtime with its own conversation that missed another member's reply: Claude joined and
        // replied, then Codex became active again without a handover.
        val both = joined.copy(conversations = joined.conversations + (RuntimeId.CLAUDE_CODE to session), pendingContext = emptyMap())
        val away = both.copy(entries = both.entries + user("Claude?") + reply(sonnet, "Here"))
        val caughtUp = assertNotNull(Handovers.catchUp(away, codex))
        assertEquals(listOf(sonnet, codex, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), notes(caughtUp).last().let { listOf(it.from, it.to, it.method, it.reason) })
        assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Claude?\nCompanion: Here\n>>>", Handovers.owedTo(caughtUp, RuntimeId.CODEX))
        assertNull(Handovers.catchUp(away.copy(entries = away.entries + user("Unanswered")), sonnet), "Claude missed no reply")
        assertNull(Handovers.catchUp(away.copy(pendingContext = mapOf(RuntimeId.CODEX to "a summary")), codex), "Owed a summary already")
        assertEquals(Handovers.owedTo(caughtUp, RuntimeId.CODEX), Handovers.owedTo(Handovers.beforeTurn(away, codex), RuntimeId.CODEX))
        assertSame(away, Handovers.beforeTurn(away, sonnet), "Claude is current and missed nothing")
        assertTrue(Handovers.consumed(caughtUp, RuntimeId.CODEX).pendingContext.isEmpty())
    }

    @Test fun aLostConversationGetsWhatItWasOwedAndTheRecentMessagesWithANote() {
        val question = user("And then?")
        val record = ChatRecord(
            conversations = mapOf(RuntimeId.CLAUDE_CODE to session, RuntimeId.CODEX to thread),
            entries = listOf(user("Hi"), reply(codex, "Hello"), Handovers.entry(codex, sonnet, HandoverMethod.SUMMARY, HandoverReason.MANUAL, "They said hi."),
                question, reply(sonnet, "")),
            pendingContext = mapOf(RuntimeId.CLAUDE_CODE to "Earlier in this conversation (written by Codex (its default model)):\n<<<\nThey said hi.\n>>>"))
        val over = Handovers.startOver(record, sonnet, question.id, before = record.entries.take(3))
        assertNull(over.conversations[RuntimeId.CLAUDE_CODE]); assertEquals(thread, over.conversations[RuntimeId.CODEX])
        val note = over.entries[over.entries.indexOf(question) - 1] as ChatEntry.Handover
        assertEquals(listOf(sonnet, sonnet, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), listOf(note.from, note.to, note.method, note.reason))
        assertEquals(record.pendingContext.getValue(RuntimeId.CLAUDE_CODE) + "\n\n" +
            "Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hi\nCompanion: Hello\n>>>", Handovers.owedTo(over, RuntimeId.CLAUDE_CODE))
        // With nothing before it, the new conversation is fresh and still noted.
        val alone = ChatRecord(conversations = mapOf(RuntimeId.CLAUDE_CODE to session), entries = listOf(question, reply(sonnet, "")))
        val restarted = Handovers.startOver(alone, sonnet, question.id, before = emptyList())
        assertEquals(HandoverMethod.FRESH, notes(restarted).single().method); assertTrue(restarted.pendingContext.isEmpty())
    }
}
