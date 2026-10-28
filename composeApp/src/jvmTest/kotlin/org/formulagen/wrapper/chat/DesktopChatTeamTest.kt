package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

/** One conversation shared by a team on two runtimes, and every way one member hands it to the next. */
class DesktopChatTeamTest {
    private val codex = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
    private val sonnet = TeamMember(RuntimeId.CLAUDE_CODE, "sonnet")
    private val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")
    private val thread = "10000000-0000-4000-8000-000000000001"
    private val session = "10000000-0000-4000-8000-000000000002"
    private val root: Path = Files.createTempDirectory("chat-team")
    private val scope = ChatScope("local", UUID.randomUUID().toString(), "Ember", UUID.randomUUID().toString())

    private class Speech : ChatSpeech {
        override fun status() = ChatVoice()
        override fun speak(text: String) = Unit
        override fun stop() = Unit
        override fun close() = Unit
    }

    /** A runtime the test drives by hand. A summary waits for [summaryGate] when one is set. */
    private class Fake(override val id: RuntimeId) : ChatRuntime {
        val requests = CopyOnWriteArrayList<TurnRequest>()
        val events = CopyOnWriteArrayList<TurnEvents>()
        val summaries = CopyOnWriteArrayList<SummaryRequest>()
        @Volatile var readiness: Readiness = Readiness.Ready
        @Volatile var checks = 0
        @Volatile var summaryGate: CountDownLatch? = null
        @Volatile var summary: () -> String = { "They said hello." }
        @Volatile var releases = 0
        override fun readiness(profile: String): Readiness { checks++; return readiness }
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle { requests += turn; this.events += events; return TurnHandle { stops++ } }
        @Volatile var stops = 0
        override fun summarize(request: SummaryRequest): String {
            summaries += request
            summaryGate?.await(5, TimeUnit.SECONDS)
            return summary()
        }
        override fun release() { releases++ }
        override fun close() = Unit
    }

    private fun send(member: TeamMember, text: String = "Hello") =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, false)
    private fun handover(from: TeamMember?, to: TeamMember, id: String = UUID.randomUUID().toString()) =
        ChatHandover(scope.profile, scope.companionId, scope.name, scope.sessionId, from, HandoverReason.MANUAL, id, to)
    private fun chat(vararg runtimes: ChatRuntime) = DesktopChat(root, runtimes.toList(), Speech())
    private fun state(chat: DesktopChat) = runBlocking { chat.snapshot(scope) }
    private fun saved() = ChatHistory(root.resolve("local/companion-chats/${scope.companionId}.json")).read()
    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { if (System.nanoTime() > until) fail("Condition timed out"); Thread.sleep(10) }
    }

    /** Send as [member] and answer with [reply] in [conversation], as a runtime that took the turn does. */
    private fun turn(chat: DesktopChat, fake: Fake, member: TeamMember, text: String, reply: String, conversation: String) = runBlocking {
        val before = fake.events.size
        chat.send(send(member, text)); await { fake.events.size == before + 1 }
        fake.events.last().opened(conversation); fake.events.last().accepted(); fake.events.last().completed(reply, conversation)
        assertEquals(ChatState.READY, chat.snapshot(scope).state)
    }

    @Test fun twoRuntimesShareOneConversationAndEachReplyNamesItsAuthor() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello from Codex", thread)
            assertEquals(SharedRules.RUNTIME_DEFAULT_MODEL, codexRuntime.requests[0].model); assertNull(codexRuntime.requests[0].context)
            // Claude has no conversation of its own yet, so it hears what it missed, and the conversation says so.
            turn(chat, claude, sonnet, "And you?", "Hello from Claude", session)
            val first = claude.requests[0]
            assertEquals("sonnet", first.model); assertNull(first.conversation)
            assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hi\nCompanion: Hello from Codex\n>>>", first.context)
            val entries = chat.snapshot(scope).messages
            assertEquals(listOf("handover", "user", "assistant", "handover", "user", "assistant"), entries.map { it::class.simpleName!!.lowercase() })
            val fresh = entries[0] as ChatEntry.Handover
            assertEquals(listOf(null, codex, HandoverMethod.FRESH, HandoverReason.HOST), listOf(fresh.from, fresh.to, fresh.method, fresh.reason))
            val joined = entries[3] as ChatEntry.Handover
            assertEquals(listOf(codex, sonnet, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), listOf(joined.from, joined.to, joined.method, joined.reason))
            assertEquals(listOf(codex, sonnet), entries.filterIsInstance<ChatEntry.Assistant>().map { it.author })
            assertEquals(entries.map { it.id }, chat.snapshot(scope).messages.map { it.id }, "Entry ids are stable across polls")
            val record = saved()
            val hash = Persona.companion("Ember").hash
            assertEquals(mapOf(RuntimeId.CODEX to NativeConversation(thread, hash), RuntimeId.CLAUDE_CODE to NativeConversation(session, hash)), record.conversations)
            assertTrue(record.pendingContext.isEmpty())
            // Codex becomes active again without a handover (the team changed in the editor): it continues its own
            // conversation and is caught up on the reply it missed.
            turn(chat, codexRuntime, codex, "Back to you", "Welcome back", thread)
            assertEquals(thread, codexRuntime.requests[1].conversation?.id)
            assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: And you?\nCompanion: Hello from Claude\n>>>",
                codexRuntime.requests[1].context)
            val caughtUp = chat.snapshot(scope).messages.dropLast(2).last() as ChatEntry.Handover
            assertEquals(listOf(sonnet, codex, HandoverMethod.RECENT_MESSAGES, HandoverReason.HOST), listOf(caughtUp.from, caughtUp.to, caughtUp.method, caughtUp.reason))
            // Caught up, it is owed nothing more.
            turn(chat, codexRuntime, codex, "And now?", "Now", thread)
            assertNull(codexRuntime.requests[2].context)
        }
    }

    @Test fun aSwitchWithinARuntimeKeepsItsConversationWithTheNewModel() = runBlocking {
        val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(claude).use { chat ->
            chat.open(scope)
            turn(chat, claude, sonnet, "Hi", "Hello", session)
            val id = UUID.randomUUID().toString()
            val switched = chat.handover(handover(sonnet, opus, id))
            assertEquals(ChatState.READY, switched.state)
            val entry = switched.messages.last() as ChatEntry.Handover
            assertEquals(listOf(id, sonnet, opus, HandoverMethod.SAME_CONVERSATION), listOf(entry.id, entry.from, entry.to, entry.method))
            assertNull(entry.summary)
            assertEquals(switched.messages.size, chat.handover(handover(sonnet, opus, id)).messages.size, "A retried switch is one entry")
            turn(chat, claude, opus, "Still there?", "Yes", session)
            assertEquals(session, claude.requests[1].conversation?.id); assertEquals("opus", claude.requests[1].model); assertNull(claude.requests[1].context)
            assertEquals(opus, (chat.snapshot(scope).messages.last() as ChatEntry.Assistant).author)
            assertTrue(claude.summaries.isEmpty())
        }
    }

    @Test fun changingTheModelInASendIsNotedOnceEachTime() = runBlocking {
        val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(claude).use { chat ->
            chat.open(scope)
            turn(chat, claude, sonnet, "Hi", "Hello", session)
            fun switches() = state(chat).messages.filterIsInstance<ChatEntry.Handover>().filter { it.method == HandoverMethod.SAME_CONVERSATION }
            turn(chat, claude, sonnet, "Again", "Hello again", session)
            assertTrue(switches().isEmpty(), "No note while the model is unchanged")
            turn(chat, claude, opus, "Think harder", "Thinking", session)
            val entries = chat.snapshot(scope).messages
            val note = entries[entries.size - 3] as ChatEntry.Handover
            assertEquals(listOf(sonnet, opus, HandoverMethod.SAME_CONVERSATION, HandoverReason.HOST), listOf(note.from, note.to, note.method, note.reason))
            assertEquals(1, switches().size)
            assertEquals(session, claude.requests.last().conversation?.id); assertEquals("opus", claude.requests.last().model)
            assertNull(claude.requests.last().context)
            turn(chat, claude, opus, "And?", "Done", session)
            assertEquals(1, switches().size)
            turn(chat, claude, sonnet, "Back", "Back", session)
            assertEquals(listOf(opus to sonnet), switches().drop(1).map { it.from to it.to })
            // A switch recorded by chat.handover is not noted again by the send that follows it.
            chat.handover(handover(sonnet, opus))
            turn(chat, claude, opus, "Once more", "Sure", session)
            assertEquals(3, switches().size)
        }
    }

    @Test fun aModelChangeAfterAConversationFromBeforeTeamsIsNoted() = runBlocking {
        val legacy = root.resolve("local/codex/companion-chats/${scope.companionId}.json")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, """{"threadId":"$thread","messages":[{"id":"${UUID.randomUUID()}","role":"user","text":"Hi"},""" +
            """{"id":"${UUID.randomUUID()}","role":"assistant","text":"Hello again"}]}""")
        val codexRuntime = Fake(RuntimeId.CODEX)
        val luna = TeamMember(RuntimeId.CODEX, "gpt-6-luna")
        chat(codexRuntime).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, luna, "Hello", "Hi", thread)
            val entries = chat.snapshot(scope).messages
            assertEquals(listOf("user", "assistant", "handover", "user", "assistant"), entries.map { it::class.simpleName!!.lowercase() })
            val note = entries[2] as ChatEntry.Handover
            assertEquals(listOf(codex, luna, HandoverMethod.SAME_CONVERSATION), listOf(note.from, note.to, note.method))
            assertEquals(thread, codexRuntime.requests.single().conversation?.id); assertNull(codexRuntime.requests.single().context)
        }
    }

    @Test fun aSwitchAcrossRuntimesHandsOverASummaryByTheOutgoingMember() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello from Codex", thread)
            val gate = CountDownLatch(1).also { codexRuntime.summaryGate = it }
            val id = UUID.randomUUID().toString()
            assertEquals(ChatState.HANDING_OVER, chat.handover(handover(codex, sonnet, id)).state)
            assertEquals(ChatState.HANDING_OVER, chat.handover(handover(codex, sonnet, id)).state)
            assertFailsWith<BridgeBusyException> { chat.send(send(codex)) }
            await { codexRuntime.summaries.size == 1 }
            val request = codexRuntime.summaries.single()
            assertEquals(SharedRules.RUNTIME_DEFAULT_MODEL, request.model)
            assertEquals("Person: Hi\nCompanion: Hello from Codex", request.transcript)
            assertEquals(1, claude.checks, "The incoming runtime is checked before the summary")
            gate.countDown(); await { state(chat).state == ChatState.READY }
            val entry = chat.snapshot(scope).messages.last() as ChatEntry.Handover
            assertEquals(listOf(id, codex, sonnet, HandoverMethod.SUMMARY, "They said hello."), listOf(entry.id, entry.from, entry.to, entry.method, entry.summary))
            assertEquals(1, codexRuntime.summaries.size)
            // The summary is Claude's context for its next reply only, with no second note.
            turn(chat, claude, sonnet, "What were we saying?", "We said hello", session)
            assertEquals("Earlier in this conversation (written by Codex (its default model)):\n<<<\nThey said hello.\n>>>", claude.requests[0].context)
            assertEquals(1, chat.snapshot(scope).messages.count { it is ChatEntry.Handover && it.to == sonnet })
            assertTrue(saved().pendingContext.isEmpty())
            // Back to Codex: it hears only what it missed, summarized by Claude.
            claude.summary = { "Claude said hello too." }
            chat.handover(handover(sonnet, codex)); await { state(chat).state == ChatState.READY }
            assertEquals("Person: What were we saying?\nCompanion: We said hello", claude.summaries.single().transcript)
            assertEquals("sonnet", claude.summaries.single().model)
            turn(chat, codexRuntime, codex, "Hi again", "Hi", thread)
            assertEquals("Earlier in this conversation (written by Claude (sonnet)):\n<<<\nClaude said hello too.\n>>>", codexRuntime.requests[1].context)
        }
    }

    @Test fun aSummaryThatFailsFallsBackToTheRecentMessages() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            codexRuntime.summary = { error("Usage limit") }
            chat.handover(handover(codex, sonnet)); await { state(chat).state == ChatState.READY }
            val entry = chat.snapshot(scope).messages.last() as ChatEntry.Handover
            assertEquals(HandoverMethod.RECENT_MESSAGES, entry.method); assertNull(entry.summary)
            turn(chat, claude, sonnet, "Hello?", "Hi", session)
            assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hi\nCompanion: Hello\n>>>", claude.requests[0].context)
        }
        // An outgoing member whose runtime no longer has this conversation is not asked for a summary.
        ChatHistory(root.resolve("local/companion-chats/${scope.companionId}.json")).write(ChatRecord(entries = listOf(
            ChatEntry.User(UUID.randomUUID().toString(), "Hi"), ChatEntry.Assistant(UUID.randomUUID().toString(), codex, "Hello"))))
        val lost = Fake(RuntimeId.CODEX)
        chat(lost, Fake(RuntimeId.CLAUDE_CODE)).use { chat ->
            chat.open(scope)
            chat.handover(handover(codex, sonnet)); await { state(chat).state == ChatState.READY }
            assertEquals(HandoverMethod.RECENT_MESSAGES, (chat.snapshot(scope).messages.last() as ChatEntry.Handover).method)
            assertTrue(lost.summaries.isEmpty())
        }
    }

    @Test fun stopCancelsASwitchAndRecordsNothing() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            val gate = CountDownLatch(1).also { codexRuntime.summaryGate = it }
            val before = chat.snapshot(scope).messages
            chat.handover(handover(codex, sonnet)); await { codexRuntime.summaries.size == 1 }
            val releases = codexRuntime.releases
            val stopped = chat.stop(scope)
            assertEquals(ChatState.READY, stopped.state); assertEquals(before, stopped.messages)
            assertEquals(releases + 1, codexRuntime.releases, "The outgoing runtime abandons its summary")
            gate.countDown(); Thread.sleep(100)
            assertEquals(before, chat.snapshot(scope).messages); assertTrue(saved().pendingContext.isEmpty())
            // Codex is still the member, and a later switch works.
            turn(chat, codexRuntime, codex, "Still here", "Yes", thread)
            codexRuntime.summaryGate = null
            chat.handover(handover(codex, sonnet)); await { state(chat).state == ChatState.READY }
            assertEquals(HandoverMethod.SUMMARY, (chat.snapshot(scope).messages.last() as ChatEntry.Handover).method)
        }
    }

    @Test fun aRefusedSwitchIsANoticeWithoutANewEntry() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            chat.send(send(codex)); await { codexRuntime.events.size == 2 }
            val responding = chat.handover(handover(codex, sonnet))
            assertEquals(ChatState.RESPONDING, responding.state)
            assertEquals("Wait for the reply to finish, or stop it, before switching.", responding.notice)
            assertEquals(chat.snapshot(scope).messages, responding.messages)
            codexRuntime.events.last().completed("Done", thread)
            val count = chat.snapshot(scope).messages.size
            claude.readiness = Readiness.SignedOut
            assertEquals(ChatState.HANDING_OVER, chat.handover(handover(codex, sonnet)).state)
            await { state(chat).state == ChatState.READY }
            assertEquals("Sign in to Claude Code again.", chat.snapshot(scope).notice)
            assertEquals(count, chat.snapshot(scope).messages.size)
            assertTrue(codexRuntime.summaries.isEmpty(), "No summary is written for a member that cannot take over")
        }
        Files.delete(root.resolve("local/companion-chats/${scope.companionId}.json"))
        chat(Fake(RuntimeId.CODEX)).use { chat ->
            chat.open(scope)
            val missing = chat.handover(handover(codex, sonnet))
            assertEquals("Install Claude Code on this Mac, then restart Hovel to use Claude.", missing.notice)
            assertTrue(missing.messages.isEmpty())
            val sent = chat.send(send(sonnet))
            assertEquals(ChatState.DISCONNECTED, sent.state); assertTrue(sent.messages.isEmpty())
        }
        // A build that does not offer Claude says so, rather than asking to install it.
        DesktopChat(root, listOf(Fake(RuntimeId.CODEX)), Speech(), offered = setOf(RuntimeId.CODEX)).use { chat ->
            chat.open(scope)
            assertEquals("Claude is not available in this version of Hovel.", chat.handover(handover(codex, sonnet)).notice)
            assertEquals("Claude is not available in this version of Hovel.", chat.send(send(sonnet)).notice)
        }
    }

    @Test fun aSignedOutRuntimeTakesAResendOnceItIsBack() = runBlocking {
        val claude = Fake(RuntimeId.CLAUDE_CODE).also { it.readiness = Readiness.SignedOut }
        chat(claude).use { chat ->
            chat.open(scope)
            chat.send(send(sonnet)); await { state(chat).state == ChatState.DISCONNECTED }
            assertEquals("Sign in to Claude Code again.", chat.snapshot(scope).notice)
            assertTrue(chat.snapshot(scope).messages.isEmpty(), "Nothing reached Claude Code, so nothing is kept")
            claude.readiness = Readiness.Unavailable("Update Claude Code to use it here. Hovel needs version 2.1.304 or later.")
            chat.send(send(sonnet)); await { state(chat).state == ChatState.DISCONNECTED || state(chat).state == ChatState.ERROR }
            assertEquals(ChatState.ERROR, chat.snapshot(scope).state)
            assertEquals("Update Claude Code to use it here. Hovel needs version 2.1.304 or later.", chat.snapshot(scope).notice)
            chat.open(scope)
            claude.readiness = Readiness.Ready
            turn(chat, claude, sonnet, "Hi", "Hello", session)
            assertEquals(3, claude.checks)
            // A sign-in lost during a turn keeps the message, and the next send checks again.
            chat.send(send(sonnet)); await { claude.events.size == 2 }
            claude.events.last().failed(ChatFailure.SignedOut)
            assertEquals(ChatState.DISCONNECTED, chat.snapshot(scope).state)
            chat.send(send(sonnet)); await { claude.events.size == 3 }
            assertEquals(4, claude.checks)
            claude.events.last().failed(ChatFailure.UsageLimit(null))
            assertEquals("You've reached your Claude usage limit. Try again later.", chat.snapshot(scope).notice)
        }
    }

    @Test fun whatARuntimeIsOwedIsSpentOnceItTakesTheTurn() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            chat.handover(handover(codex, sonnet)); await { state(chat).state == ChatState.READY }
            // Claude takes the turn with its summary, then fails: the summary reached it, so it is not sent twice.
            chat.send(send(sonnet)); await { claude.events.size == 1 }
            assertNotNull(claude.requests[0].context)
            claude.events[0].opened(session); claude.events[0].accepted(); claude.events[0].failed(ChatFailure.Temporary)
            assertTrue(saved().pendingContext.isEmpty())
            chat.open(scope); turn(chat, claude, sonnet, "Again", "Hi", session)
            assertNull(claude.requests[1].context)
            // A turn that fails before its runtime took it keeps what that runtime is owed.
            chat.handover(handover(sonnet, codex)); await { state(chat).state == ChatState.READY }
            chat.send(send(codex)); await { codexRuntime.events.size == 2 }
            val owed = assertNotNull(codexRuntime.requests[1].context)
            codexRuntime.events[1].failed(ChatFailure.Temporary)
            assertEquals(mapOf(RuntimeId.CODEX to owed), saved().pendingContext)
            chat.open(scope); turn(chat, codexRuntime, codex, "Once more", "Ok", thread)
            assertEquals(owed, codexRuntime.requests[2].context)
        }
    }

    @Test fun switchingBackToARuntimeThatMissedNothingIsUpToDate() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            chat.handover(handover(codex, sonnet)); await { state(chat).state == ChatState.READY }
            val back = chat.handover(handover(sonnet, codex))
            val entry = back.messages.last() as ChatEntry.Handover
            assertEquals(listOf(sonnet, codex, HandoverMethod.UP_TO_DATE, HandoverReason.MANUAL), listOf(entry.from, entry.to, entry.method, entry.reason))
            assertEquals(ChatState.READY, back.state)
            assertEquals(1, codexRuntime.summaries.size + claude.summaries.size, "Only the switch to Claude needed a summary")
        }
    }

    @Test fun stopWithNothingInProgressChangesNothingAndClosingEndsOnlyWhatTheViewUsed() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX); val claude = Fake(RuntimeId.CLAUDE_CODE)
        chat(codexRuntime, claude).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            val before = chat.snapshot(scope)
            assertEquals(before, chat.stop(scope))
            assertEquals(0, codexRuntime.releases + codexRuntime.stops)
            chat.closeSession(scope)
            assertEquals(1, codexRuntime.releases); assertEquals(0, claude.releases)
        }
    }

    @Test fun thePageReadsEveryEntryInTheContractShape() = runBlocking {
        val codexRuntime = Fake(RuntimeId.CODEX)
        chat(codexRuntime).use { chat ->
            chat.open(scope)
            turn(chat, codexRuntime, codex, "Hi", "Hello", thread)
            val wire = bridgeJson.encodeToJsonElement(ChatSnapshot.serializer(), chat.snapshot(scope)).jsonObject
            val entries = wire.getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(listOf("handover", "user", "assistant"), entries.map { it.getValue("role").jsonPrimitive.content })
            assertEquals(JsonNull, entries[0]["from"], "The first member takes over from no one, written as null")
            assertEquals(JsonPrimitive("fresh"), entries[0]["method"]); assertNull(entries[0]["summary"])
            assertTrue(Regex("""^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d{1,3})?Z$""").matches(entries[0].getValue("at").jsonPrimitive.content))
            assertEquals(buildJsonObject { put("runtimeId", "codex"); put("modelId", "runtime-default") }, entries[2]["author"])
        }
    }
}
