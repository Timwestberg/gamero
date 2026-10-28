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

/** One conversation shared by a team on two runtimes. */
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
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle { requests += turn; this.events += events; return TurnHandle {} }
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

    /** Send as [member] and answer with [reply] in [conversation]. */
    private fun turn(chat: DesktopChat, fake: Fake, member: TeamMember, text: String, reply: String, conversation: String) = runBlocking {
        val before = fake.events.size
        chat.send(send(member, text)); await { fake.events.size == before + 1 }
        fake.events.last().opened(conversation); fake.events.last().completed(reply, conversation)
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
            assertEquals(listOf(null, codex, HandoverMethod.FRESH), listOf(fresh.from, fresh.to, fresh.method))
            val joined = entries[3] as ChatEntry.Handover
            assertEquals(listOf(codex, sonnet, HandoverMethod.RECENT_MESSAGES), listOf(joined.from, joined.to, joined.method))
            assertEquals(listOf(codex, sonnet), entries.filterIsInstance<ChatEntry.Assistant>().map { it.author })
            assertEquals(entries.map { it.id }, chat.snapshot(scope).messages.map { it.id }, "Entry ids are stable across polls")
            val record = saved()
            val hash = Persona.companion("Ember").hash
            assertEquals(mapOf(RuntimeId.CODEX to NativeConversation(thread, hash), RuntimeId.CLAUDE_CODE to NativeConversation(session, hash)), record.conversations)
            assertNull(record.pendingContext)
            // Each runtime then continues its own conversation, with nothing owed.
            turn(chat, codexRuntime, codex, "Back to you", "Welcome back", thread)
            assertEquals(thread, codexRuntime.requests[1].conversation?.id); assertNull(codexRuntime.requests[1].context)
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
