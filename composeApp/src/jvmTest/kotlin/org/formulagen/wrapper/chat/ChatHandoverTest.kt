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

    @Test fun aRuntimeThatTookItsHandoverHasSeenEverythingBeforeIt() {
        val question = user("And then?")
        val handed = Handovers.entry(codex, sonnet, HandoverMethod.SUMMARY, HandoverReason.MANUAL, "They said hi.")
        val record = ChatRecord(conversations = mapOf(RuntimeId.CLAUDE_CODE to session, RuntimeId.CODEX to thread),
            entries = listOf(user("Hi"), reply(codex, "Hello"), handed, question))
        assertEquals(listOf(question), Handovers.unseen(record, RuntimeId.CLAUDE_CODE))
        // Still owed the summary, it has seen nothing since its own last reply.
        assertEquals(3, Handovers.unseen(record.copy(pendingContext = mapOf(RuntimeId.CLAUDE_CODE to "the summary")), RuntimeId.CLAUDE_CODE).size)
        assertEquals(listOf(question), Handovers.unseen(record, RuntimeId.CODEX))
        assertEquals(3, Handovers.unseen(record.copy(conversations = emptyMap()), RuntimeId.CODEX).size)
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

    private fun worked(author: TeamMember, text: String, vararg steps: ActivityStep) = ChatEntry.Assistant(id(), author, text, Activity(steps.toList(), false))

    @Test fun aWorkLogNamesTheChangesCommandsDenialsAndWhereTheWorkStopped() {
        val failing = ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED, "1 failing\nmore")
        val passing = ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.DONE, "12 passing")
        val push = ActivityStep(ActivityKind.COMMAND, "git push", ActivityStatus.DENIED)
        val entries = listOf(user("Fix the date test"),
            worked(sonnet, "Reading first.", ActivityStep(ActivityKind.READ, "src/date.ts", ActivityStatus.DONE),
                ActivityStep(ActivityKind.SEARCH, "parseDate", ActivityStatus.DONE, "2 files")),
            worked(sonnet, "The parser dropped the offset. Fixed; I did not push.", failing,
                ActivityStep(ActivityKind.EDIT, "src/date.ts", ActivityStatus.DONE, "+3 -1"), passing,
                ActivityStep(ActivityKind.CONNECTOR, "linear · create_issue", ActivityStatus.DONE), push))
        val log = WorkLog(changed = listOf("src/date.ts"), commands = listOf(failing, passing, push), denied = listOf("git push"),
            stoppedAt = "The parser dropped the offset. Fixed; I did not push.")
        assertEquals(log, Handovers.workLog(entries, sonnet))
        // The incoming model reads it as prose, line breaks in a result cut to its first line.
        assertEquals("""
            Files changed: src/date.ts.
            Commands: npm test (failed: 1 failing); npm test (done: 12 passing); git push (denied).
            Denied: git push.
            Where it stopped: The parser dropped the offset. Fixed; I did not push.""".trimIndent(), Handovers.workLogProse(log))
        // Only the outgoing member's own work since the last handover, and nothing when nobody worked.
        assertNull(Handovers.workLog(entries, codex))
        assertNull(Handovers.workLog(listOf(user("Hi"), reply(sonnet, "Hello")), sonnet))
        val after = entries + Handovers.entry(sonnet, codex, HandoverMethod.UP_TO_DATE, HandoverReason.MANUAL)
        assertNull(Handovers.workLog(after, sonnet))
        // Each list stays within the contract's bounds, the newest commands kept.
        fun steps(range: IntRange) = range.map { ActivityStep(ActivityKind.COMMAND, "step $it", ActivityStatus.DONE) }.toTypedArray()
        val busy = Handovers.workLog(listOf(worked(sonnet, "first", *steps(1..30)), worked(sonnet, "x".repeat(1000), *steps(31..60))), sonnet)!!
        assertEquals(ActivityLimits.STEPS, busy.commands.size); assertEquals("step 60", busy.commands.last().label)
        assertEquals(ActivityLimits.DETAIL, busy.stoppedAt!!.length)
        assertTrue(worked(sonnet, "x").worked); assertFalse(reply(sonnet, "y").worked)
    }

    @Test fun aRecentMessagesHandoverCarriesTheLastWorkLogAndOwesIt() {
        val record = ChatRecord(conversations = mapOf(RuntimeId.CLAUDE_CODE to session), entries = listOf(user("Fix it"),
            worked(sonnet, "Fixed.", ActivityStep(ActivityKind.EDIT, "a.txt", ActivityStatus.DONE, "+1 -0"))))
        val handed = Handovers.handover(record, Handovers.entry(sonnet, codex, HandoverMethod.RECENT_MESSAGES, HandoverReason.MANUAL),
            Handovers.recentMessages(record.entries))
        val note = notes(handed).single()
        val log = WorkLog(listOf("a.txt"), emptyList(), emptyList(), "Fixed.")
        assertEquals(log, note.workLog)
        assertTrue(Handovers.owedTo(handed, RuntimeId.CODEX)!!.endsWith("Earlier in this conversation (the work log):\n<<<\nFiles changed: a.txt.\nWhere it stopped: Fixed.\n>>>"))
        // A summary or a fresh start carries only what it is given.
        assertNull(Handovers.entry(sonnet, codex, HandoverMethod.UP_TO_DATE, HandoverReason.MANUAL, workLog = log).workLog)
        assertEquals(log, Handovers.entry(sonnet, codex, HandoverMethod.SUMMARY, HandoverReason.MANUAL, "s", workLog = log).workLog)
        // With no working reply since, the last handover's log is the last work log there was.
        val later = handed.copy(entries = handed.entries + user("And now?") + reply(codex, "Sure"))
        assertEquals(log, Handovers.lastWorkLog(later.entries))
    }

    @Test fun workingAndTalkingConversationsOfOneRuntimeCatchEachOtherUp() {
        val talked = ChatRecord(conversations = mapOf(RuntimeId.CLAUDE_CODE to session), entries = listOf(user("Hi"), reply(sonnet, "Hello")))
        // A first working turn hears the whole conversation; its own working replies count as seen next time.
        assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hi\nCompanion: Hello\n>>>",
            Handovers.workContext(talked, RuntimeId.CLAUDE_CODE, talked.entries))
        val working = talked.copy(workConversations = mapOf(RuntimeId.CLAUDE_CODE to thread),
            entries = talked.entries + user("Fix it") + worked(sonnet, "Fixed.", ActivityStep(ActivityKind.EDIT, "a.txt", ActivityStatus.DONE)))
        assertNull(Handovers.workContext(working, RuntimeId.CLAUDE_CODE, working.entries))
        val more = working.copy(entries = working.entries + user("Thanks") + reply(sonnet, "Any time"))
        assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Thanks\nCompanion: Any time\n>>>",
            Handovers.workContext(more, RuntimeId.CLAUDE_CODE, more.entries))
        // The talk-only conversation missed the working reply, so the next talk turn is caught up, with the work log.
        val caught = assertNotNull(Handovers.catchUp(working, sonnet))
        assertEquals(listOf(HandoverMethod.RECENT_MESSAGES), notes(caught).map { it.method })
        assertEquals(WorkLog(listOf("a.txt"), emptyList(), emptyList(), "Fixed."), notes(caught).single().workLog)
        assertContains(Handovers.owedTo(caught, RuntimeId.CLAUDE_CODE)!!, "Person: Fix it\nCompanion: Fixed.")
    }
}
