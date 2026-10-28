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
import org.formulagen.wrapper.providers.CodexAppServer
import org.formulagen.wrapper.providers.CodexRequestRejected
import kotlin.test.*

class DesktopChatTest {
    private val threadId = "10000000-0000-4000-8000-000000000001"
    private val freshThreadId = "10000000-0000-4000-8000-000000000002"
    private fun scope(profile: String = "local", companion: String = UUID.randomUUID().toString()) = ChatScope(profile, companion, "Ember", UUID.randomUUID().toString())
    private val codex = TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL)
    private fun send(scope: ChatScope, speak: Boolean = true, member: TeamMember = codex, text: String = "Hello") =
        ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, speak)
    private fun handover(scope: ChatScope, from: TeamMember?, to: TeamMember, requestId: String = UUID.randomUUID().toString()) =
        ChatHandover(scope.profile, scope.companionId, scope.name, scope.sessionId, from, HandoverReason.MANUAL, requestId, to)
    /** The text a person reads for an entry: a message, or a handover's summary. */
    private val ChatEntry.text: String get() = when (this) {
        is ChatEntry.User -> text
        is ChatEntry.Assistant -> text
        is ChatEntry.Handover -> summary.orEmpty()
    }
    private class Speech : ChatSpeech {
        val said = CopyOnWriteArrayList<String>()
        var stops = 0
        override fun status() = ChatVoice()
        override fun speak(text: String) { said += text }
        override fun stop() { stops++ }
        override fun close() = Unit
    }
    private inner class Rpc(val notify: (String, JsonObject) -> Unit) : CodexAppServer {
        val calls = CopyOnWriteArrayList<String>()
        val params = CopyOnWriteArrayList<JsonObject>()
        var connected = true
        var beforeAccount: () -> Unit = {}
        var earlyReply = false
        /** Codex no longer has the saved thread, so resume answers with an error. */
        var resumeRejected = false
        var onClose: () -> Unit = {}
        @Volatile var thread = threadId
        @Volatile var closed = false
        override fun call(method: String, params: JsonObject): JsonObject {
            calls += method; this.params += params
            return when(method) {
                "account/read" -> { beforeAccount(); if (connected) json("""{"account":{"type":"chatgpt"}}""") else json("""{"account":null}""") }
                "thread/resume" -> { if (resumeRejected) throw CodexRequestRejected(); json("""{"thread":{"id":"$thread"}}""") }
                "thread/start" -> { if (resumeRejected) thread = freshThreadId; json("""{"thread":{"id":"$thread"}}""") }
                "turn/start" -> {
                    if (earlyReply) { delta("Early hello"); complete() }
                    json("""{"turn":{"id":"turn-1"}}""")
                }
                else -> error("Unexpected method $method")
            }
        }
        fun delta(text: String, turn: String = "turn-1") = notify("item/agentMessage/delta", buildJsonObject {
            put("threadId", thread); put("turnId", turn); put("itemId", "message"); put("delta", text)
        })
        fun complete() = notify("turn/completed", json("""{"threadId":"$thread","turn":{"id":"turn-1","status":"completed"}}"""))
        override fun loginResult(loginId: String): Boolean? = null
        override fun close() { onClose(); closed = true }
    }
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun await(check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!check()) { if (System.nanoTime() > until) fail("Condition timed out"); Thread.sleep(10) }
    }
    private fun state(chat: DesktopChat, scope: ChatScope) = runBlocking { chat.snapshot(scope) }
    /** A chat whose only runtime is Codex, served by [rpc] instead of an app-server process. */
    private fun chat(root: Path, speech: ChatSpeech, rpc: (receive: (String, JsonObject) -> Unit) -> CodexAppServer) =
        DesktopChat(root, listOf(CodexChatRuntime(root, Path.of("/usr/bin/true")) { _, receive -> rpc(receive) }), speech)

    @Test fun streamsPersistsDeduplicatesAndResumesOnlyItsCompanion() = runBlocking {
        val root = Files.createTempDirectory("chat-history")
        val speech = Speech(); val rpcs = CopyOnWriteArrayList<Rpc>()
        chat(root, speech) { receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == ChatState.READY }
            val request = send(a); chat.send(request); chat.send(request)
            await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            rpcs[0].delta("Ignored", "other-turn"); rpcs[0].delta("Hello "); rpcs[0].delta("friend"); rpcs[0].complete()
            await { state(chat,a).state == ChatState.READY }
            assertEquals("Hello friend", chat.snapshot(a).messages.last().text)
            assertEquals(listOf("Hello friend"), speech.said)
            assertEquals(1, rpcs[0].calls.count { it == "turn/start" })
            chat.closeSession(a)
            val b = scope(); chat.open(b); await { state(chat,b).state == ChatState.READY }
            assertTrue(chat.snapshot(b).messages.isEmpty())
            chat.closeSession(a); assertEquals(ChatState.READY, chat.snapshot(b).state)
            val a2 = a.copy(sessionId = UUID.randomUUID().toString())
            chat.open(a2); await { state(chat,a2).state == ChatState.READY }
            assertEquals("Hello friend", chat.snapshot(a2).messages.last().text)
            // The new view's first send starts a new app-server, which resumes the thread.
            chat.send(send(a2,false)); await { rpcs.size == 2 && rpcs.last().calls.contains("turn/start") }
            assertContains(rpcs.last().calls,"thread/resume")
            val resume = rpcs.last().params[rpcs.last().calls.indexOf("thread/resume")]
            assertEquals(JsonPrimitive(true), resume["excludeTurns"])
            assertEquals(JsonPrimitive(false), rpcs.last().params[rpcs.last().calls.indexOf("account/read")]["refreshToken"])
            chat.stop(a2); rpcs.last().delta("late"); rpcs.last().complete()
            assertEquals(ChatState.CANCELLED, chat.snapshot(a2).state)
            assertEquals(listOf("Hello friend"), speech.said)
            val otherProfile = scope(UUID.randomUUID().toString(),a.companionId)
            chat.open(otherProfile); await { state(chat,otherProfile).state == ChatState.READY }
            assertTrue(chat.snapshot(otherProfile).messages.isEmpty())
        }
    }

    @Test fun buffersEventsBeforeTurnAcknowledgementAndMutesPendingReply() = runBlocking {
        val speech = Speech(); lateinit var rpc: Rpc
        chat(Files.createTempDirectory("chat-early"), speech) { receive -> Rpc(receive).also { rpc=it; it.earlyReply=true } }.use { chat ->
            val a=scope(); chat.open(a); await { state(chat,a).state==ChatState.READY }
            chat.send(send(a)); await { state(chat,a).state==ChatState.READY }
            assertEquals("Early hello",chat.snapshot(a).messages.last().text)
            assertEquals(listOf("Early hello"),speech.said)
            rpc.earlyReply=false; chat.send(send(a)); chat.stopSpeaking(a)
            await { rpc.calls.count { it=="turn/start" }==2 }
            rpc.delta("Muted"); rpc.complete(); await { state(chat,a).state==ChatState.READY }
            assertEquals(listOf("Early hello"),speech.said)
        }
    }

    @Test fun stoppingAnAccountProbeCannotResurrectIt() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val speech=Speech(); lateinit var old: Rpc
        chat(Files.createTempDirectory("chat-stop"), speech) { receive ->
            Rpc(receive).also { old=it; it.beforeAccount={ entered.countDown(); release.await(5,TimeUnit.SECONDS) } }
        }.use { chat ->
            // A view checks the account on its first send.
            val a=scope(); chat.open(a); chat.send(send(a)); assertTrue(entered.await(5,TimeUnit.SECONDS))
            chat.stop(a); release.countDown(); old.notify("transport/closed",buildJsonObject {})
            Thread.sleep(50); assertEquals(ChatState.CANCELLED,chat.snapshot(a).state); await { old.closed }
        }
    }

    @Test fun disconnectedAccountsNeverStartATurnAndCorruptHistoryIsPreserved() = runBlocking {
        val root=Files.createTempDirectory("chat-invalid"); lateinit var rpc: Rpc; val all = CopyOnWriteArrayList<Rpc>()
        fun rpcs() = all
        chat(root, Speech()) { receive -> Rpc(receive).also { rpc=it; all += it; it.connected=false } }.use { chat ->
            val a=scope(); chat.open(a); chat.send(send(a)); await { state(chat,a).state==ChatState.DISCONNECTED }
            assertEquals("Connect Codex in Profile → Providers to message your companion.", chat.snapshot(a).notice)
            // Nothing reached Codex: the message stays, unanswered, with why; a resend checks the account again.
            val failed = chat.snapshot(a)
            assertEquals(UserStatus.UNANSWERED, (failed.messages.last() as ChatEntry.User).status)
            assertEquals(ChatFailureReport(ChatFailureKind.SIGNED_OUT, codex), failed.failure)
            assertTrue(failed.messages.none { it is ChatEntry.Assistant }, "No empty reply is left")
            chat.send(send(a)); await { state(chat,a).state==ChatState.DISCONNECTED }
            assertEquals(listOf("account/read", "account/read"), rpcs().flatMap { it.calls }.toList())
            await { rpc.closed }
            chat.closeSession(a)
            val file=root.resolve("local/companion-chats/${a.companionId}.json")
            Files.writeString(file,"broken")
            assertFails { chat.open(a) }; assertEquals("broken",Files.readString(file))
        }
    }

    @Test fun failedResumeStartsAFreshThreadAndSavesIt() = runBlocking {
        val root = Files.createTempDirectory("chat-resume"); val rpcs = CopyOnWriteArrayList<Rpc>()
        var rejectResume = false
        chat(root, Speech()) { receive -> Rpc(receive).also { rpcs += it; it.resumeRejected = rejectResume } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            rpcs[0].complete(); await { state(chat,a).state == ChatState.READY }
            chat.closeSession(a)
            val again = a.copy(sessionId = UUID.randomUUID().toString())
            chat.open(again); await { state(chat,again).state == ChatState.READY }
            rejectResume = true
            chat.send(send(again, false, text = "Still there?")); await { rpcs.size == 2 && rpcs.last().calls.contains("turn/start") }
            // A view's first send checks the account and the turn confirms it before any thread call. Codex no longer has
            // the thread, so the turn starts over in a new one, which hears the recent messages.
            assertEquals(listOf("account/read", "account/read", "thread/resume", "account/read", "thread/start", "turn/start"), rpcs.last().calls.toList())
            val turn = rpcs.last().params.last()
            assertEquals(JsonPrimitive(freshThreadId), turn["threadId"])
            assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hello\n>>>\n\nStill there?",
                turn.getValue("input").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content)
            rpcs.last().delta("Fresh hello"); rpcs.last().complete(); await { state(chat,again).state == ChatState.READY }
            assertEquals("Fresh hello", chat.snapshot(again).messages.last().text)
            val saved = ChatHistory(root.resolve("local/companion-chats/${a.companionId}.json")).read()
            assertEquals(freshThreadId, saved.conversations[RuntimeId.CODEX]?.id)
            assertTrue(saved.pendingContext.isEmpty())
            // Codex's first turn is noted as a fresh start, and its new thread as getting the recent messages.
            assertEquals(listOf("handover", "user", "assistant", "handover", "user", "assistant"), saved.entries.map { it::class.simpleName!!.lowercase() })
            val note = saved.entries[3] as ChatEntry.Handover
            assertEquals(listOf(codex, codex, HandoverMethod.RECENT_MESSAGES), listOf(note.from, note.to, note.method))
        }
    }

    @Test fun aConversationFromBeforeTeamsResumesItsCodexThread() = runBlocking {
        val root = Files.createTempDirectory("chat-migrate"); val rpcs = CopyOnWriteArrayList<Rpc>()
        val a = scope()
        val legacy = root.resolve("local/codex/companion-chats/${a.companionId}.json")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, """{"threadId":"$threadId","messages":[{"id":"${UUID.randomUUID()}","role":"user","text":"Hi"},{"id":"${UUID.randomUUID()}","role":"assistant","text":"Hello again"}]}""")
        chat(root, Speech()) { receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            chat.open(a); await { state(chat,a).state == ChatState.READY }
            assertEquals(listOf("Hi", "Hello again"), chat.snapshot(a).messages.map { it.text })
            assertFalse(Files.exists(legacy))
            chat.send(send(a, false)); await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            assertEquals(JsonPrimitive(threadId), rpcs[0].params[rpcs[0].calls.indexOf("thread/resume")]["threadId"])
            rpcs[0].complete(); await { state(chat,a).state == ChatState.READY }
            // The first reply records the persona the migrated thread now runs with.
            val saved = ChatHistory(root.resolve("local/companion-chats/${a.companionId}.json")).read()
            assertEquals(NativeConversation(threadId, Persona.companion("Ember").hash), saved.conversations[RuntimeId.CODEX])
        }
    }

    @Test fun anUnreadableOldConversationStaysBesideANewOne() = runBlocking {
        val root = Files.createTempDirectory("chat-migrate-broken")
        val a = scope()
        val legacy = root.resolve("local/codex/companion-chats/${a.companionId}.json")
        Files.createDirectories(legacy.parent); Files.writeString(legacy, "broken")
        chat(root, Speech()) { receive -> Rpc(receive) }.use { chat ->
            chat.open(a); await { state(chat,a).state == ChatState.READY }
            assertTrue(chat.snapshot(a).messages.isEmpty())
            chat.send(send(a, false)); await { state(chat,a).messages.size == 3 }
            assertEquals(HandoverMethod.FRESH, (chat.snapshot(a).messages.first() as ChatEntry.Handover).method)
        }
        assertEquals("broken", Files.readString(legacy))
        assertTrue(Files.exists(root.resolve("local/companion-chats/${a.companionId}.json")))
    }

    @Test fun handoverTextOwedToARuntimeIsSentWithItsNextTurnOnly() = runBlocking {
        val root = Files.createTempDirectory("chat-pending"); val rpcs = CopyOnWriteArrayList<Rpc>()
        val a = scope()
        val history = ChatHistory(root.resolve("local/companion-chats/${a.companionId}.json"))
        val context = "Earlier in this conversation (written by Claude · Sonnet):\n<<<\nuser: Hi\n>>>"
        history.write(ChatRecord(pendingContext = mapOf(RuntimeId.CODEX to context)))
        fun input(rpc: Rpc, turn: Int) = rpc.params.filterIndexed { index, _ -> rpc.calls[index] == "turn/start" }[turn]
            .getValue("input").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        chat(root, Speech()) { receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            assertEquals("$context\n\nHello", input(rpcs[0], 0))
            rpcs[0].complete(); await { state(chat,a).state == ChatState.READY }
            assertTrue(history.read().pendingContext.isEmpty())
            chat.send(send(a, false)); await { rpcs[0].calls.count { it == "turn/start" } == 2 }
            assertEquals("Hello", input(rpcs[0], 1))
        }
        history.write(history.read().copy(pendingContext = mapOf(RuntimeId.CLAUDE_CODE to context)))
        chat(root, Speech()) { receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val again = a.copy(sessionId = UUID.randomUUID().toString())
            chat.open(again); await { state(chat,again).state == ChatState.READY }
            chat.send(send(again, false)); await { rpcs.size == 2 && rpcs.last().calls.contains("turn/start") }
            assertEquals("Hello", input(rpcs.last(), 0))
            rpcs.last().complete(); await { state(chat,again).state == ChatState.READY }
            assertEquals(setOf(RuntimeId.CLAUDE_CODE), history.read().pendingContext.keys)
        }
    }

    @Test fun losingAnIdleAppServerStartsANewOneOnTheNextSend() = runBlocking {
        val rpcs = CopyOnWriteArrayList<Rpc>()
        chat(Files.createTempDirectory("chat-idle-loss"), Speech()) { receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            rpcs[0].complete(); await { state(chat,a).state == ChatState.READY }
            rpcs[0].notify("transport/closed", buildJsonObject {})
            await { rpcs[0].closed }
            assertEquals(ChatState.READY, chat.snapshot(a).state)
            chat.send(send(a, false)); await { rpcs.size == 2 && rpcs[1].calls.contains("turn/start") }
            assertEquals(listOf("account/read", "thread/resume", "turn/start"), rpcs[1].calls.toList())
            assertEquals(1, rpcs[0].calls.count { it == "turn/start" })
        }
    }

    @Test fun viewsTheHostEndedKeepTheirFinalSnapshot() = runBlocking {
        DesktopChat(Files.createTempDirectory("chat-no-codex"), emptyList(), Speech()).use { chat ->
            val a = scope()
            assertEquals(ChatState.READY, chat.open(a).state)
            // Codex is offered but not installed: the message stays, unanswered, for a retry with another member.
            val sent = chat.send(send(a))
            assertEquals(ChatState.DISCONNECTED, sent.state)
            assertEquals(listOf(UserStatus.UNANSWERED), sent.messages.filterIsInstance<ChatEntry.User>().map { it.status })
            assertEquals(ChatFailureReport(ChatFailureKind.UNAVAILABLE, codex), sent.failure)
            assertEquals("Install Codex on this Mac, then restart Hovel to connect.", chat.snapshot(a).notice)
            assertFails { chat.snapshot(scope()) }
            chat.closeSession(a); assertFails { chat.snapshot(a) }
        }
        chat(Files.createTempDirectory("chat-invalidate"), Speech()) { receive -> Rpc(receive) }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.invalidate()
            assertEquals(ChatState.ERROR, chat.snapshot(a).state)
            assertFails { chat.send(send(a)) }
            chat.open(a); await { state(chat,a).state == ChatState.READY }
        }
    }

    @Test fun stoppingNeverWaitsForTheAppServerToExit() = runBlocking {
        val closing = CountDownLatch(1); val release = CountDownLatch(1); val rpcs = CopyOnWriteArrayList<Rpc>()
        chat(Files.createTempDirectory("chat-release"), Speech()) { receive ->
            Rpc(receive).also { rpcs += it; it.onClose = { closing.countDown(); release.await(5, TimeUnit.SECONDS) } }
        }.use { chat ->
            // Stop during a reply closes the app-server, which here takes long to exit.
            val a = scope(); chat.open(a); chat.send(send(a, false)); await { rpcs.isNotEmpty() && rpcs[0].calls.contains("turn/start") }
            val rpc = rpcs[0]
            Thread.sleep(50)
            val started = System.nanoTime()
            assertEquals(ChatState.CANCELLED, chat.stop(a).state)
            assertEquals(ChatState.CANCELLED, chat.snapshot(a).state)
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4))
            assertFalse(rpc.closed)
            release.countDown(); await { rpc.closed }
        }
    }

    /** A runtime the test drives by hand: it records turns and summaries and answers what the test sets. */
    private class FakeRuntime(override val id: RuntimeId = RuntimeId.CODEX) : ChatRuntime {
        val requests = CopyOnWriteArrayList<TurnRequest>()
        val events = CopyOnWriteArrayList<TurnEvents>()
        val summaries = CopyOnWriteArrayList<SummaryRequest>()
        @Volatile var readiness: Readiness = Readiness.Ready
        @Volatile var summary: (SummaryRequest) -> String = { "They said hello." }
        @Volatile var stops = 0
        @Volatile var releases = 0
        override fun readiness(profile: String) = readiness
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle { requests += turn; this.events += events; return TurnHandle { stops++ } }
        override fun summarize(request: SummaryRequest): String { summaries += request; return summary(request) }
        override fun release() { releases++ }
        override fun close() = Unit
    }

    @Test fun aLostConversationStartsFreshOnceAndFailuresBecomeNotices() = runBlocking {
        val runtime = FakeRuntime()
        DesktopChat(Files.createTempDirectory("chat-fake-runtime"), listOf(runtime), Speech()).use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { runtime.events.size == 1 }
            assertNull(runtime.requests[0].conversation)
            runtime.events[0].opened(threadId); runtime.events[0].partial("Hel"); runtime.events[0].completed("Hello", threadId)
            assertEquals(ChatState.READY, chat.snapshot(a).state); assertEquals("Hello", chat.snapshot(a).messages.last().text)

            chat.send(send(a, false)); await { runtime.events.size == 2 }
            assertEquals(NativeConversation(threadId, Persona.companion("Ember").hash), runtime.requests[1].conversation)
            runtime.events[1].partial("Partial"); runtime.events[1].failed(ChatFailure.ConversationLost)
            await { runtime.events.size == 3 }
            assertNull(runtime.requests[2].conversation); assertEquals("Hello", runtime.requests[2].text)
            assertEquals(ChatState.RESPONDING, chat.snapshot(a).state); assertEquals("", chat.snapshot(a).messages.last().text)
            // The new conversation hears the recent messages, noted just before the person's message.
            assertEquals("Earlier in this conversation (the most recent messages):\n<<<\nPerson: Hello\nCompanion: Hello\n>>>", runtime.requests[2].context)
            val note = chat.snapshot(a).messages.dropLast(2).last() as ChatEntry.Handover
            assertEquals(HandoverMethod.RECENT_MESSAGES, note.method); assertEquals(codex, note.from); assertEquals(codex, note.to)
            // A new conversation that is lost too is not retried again.
            runtime.events[2].failed(ChatFailure.ConversationLost)
            assertEquals(ChatState.ERROR, chat.snapshot(a).state)
            assertEquals("Codex could not continue this conversation. Send your message again to start fresh.", chat.snapshot(a).notice)
            assertEquals(3, runtime.requests.size)

            // An error needs Reconnect, which opens the same view again.
            chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { runtime.events.size == 4 }
            runtime.events[3].failed(ChatFailure.UsageLimit(java.time.Instant.now().plusSeconds(3600)))
            assertEquals(ChatState.ERROR, chat.snapshot(a).state)
            assertTrue(chat.snapshot(a).notice!!.startsWith("You've reached your Codex usage limit. It resets at "))
            assertEquals("You've reached your Claude usage limit. Try again later.", failureNotice(RuntimeId.CLAUDE_CODE, ChatFailure.UsageLimit(null)))

            // Stop ends a started reply through its handle, without ending the runtime's other work.
            chat.open(a); await { state(chat,a).state == ChatState.READY }
            chat.send(send(a, false)); await { runtime.events.size == 5 }
            Thread.sleep(100)
            val releases = runtime.releases
            assertEquals(ChatState.CANCELLED, chat.stop(a).state)
            assertEquals(1, runtime.stops); assertEquals(releases, runtime.releases)
            runtime.events[4].completed("Late", threadId)
            assertEquals(ChatState.CANCELLED, chat.snapshot(a).state); assertEquals("", chat.snapshot(a).messages.last().text)

            chat.send(send(a, false)); await { runtime.events.size == 6 }
            runtime.events[5].failed(ChatFailure.SignedOut)
            assertEquals(ChatState.DISCONNECTED, chat.snapshot(a).state)
            assertEquals("Connect Codex in Profile → Providers to message your companion.", chat.snapshot(a).notice)
            // Every failed send keeps the person's message.
            assertEquals(5, chat.snapshot(a).messages.count { it is ChatEntry.User })
        }
    }

    @Test fun voiceSanitizesTextAndClampsInvalidPcm() {
        assertEquals("Hello link Code is shown in the chat.",speechText("**Hello** [link](https://example.test) ```secret code```"))
        assertEquals(1200,speechText("x".repeat(2000)).length)
        assertContentEquals(byteArrayOf(0,0,-1,127,1,-128,0,0), pcm16(floatArrayOf(0f,2f,-2f,Float.NaN)))
        assertContains(chatProcessArguments(),"features.shell_tool=false")
        assertContains(chatProcessArguments(),"web_search=\"disabled\"")
        DesktopSpeech(null).use { speech -> speech.speak("Hello"); assertEquals(VoiceState.UNAVAILABLE,speech.status().state) }
    }

    @Test fun aVoiceRuntimeThatCannotLinkReportsUnavailableAndKeepsTheWorker() {
        var opens = 0
        val config = org.formulagen.wrapper.voice.VoiceConfig(modelDir = "/missing", voiceWav = "/missing.wav")
        DesktopSpeech(config) { opens++; throw UnsatisfiedLinkError("no sherpa-onnx-jni in java.library.path") }.use { speech ->
            speech.speak("Hello")
            await { speech.status().state == VoiceState.UNAVAILABLE }
            assertEquals("Local voice could not start on this Mac. Text chat is available.", speech.status().notice)
            speech.stop(); assertEquals(VoiceState.UNAVAILABLE, speech.status().state)
            speech.speak("Hello again"); Thread.sleep(50)
            assertEquals(VoiceState.UNAVAILABLE, speech.status().state)
            assertEquals(1, opens)
        }
    }

    @Test fun workerThreadsLogEscapedFailuresInsteadOfReachingTheDefaultHandler() {
        val reached = java.util.concurrent.atomic.AtomicBoolean()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> reached.set(true) }
        try {
            val thread = loggingDaemonThreads("hovel-test").newThread { throw ExceptionInInitializerError("native load") }
            thread.start(); thread.join(5000)
            assertTrue(thread.isDaemon); assertFalse(reached.get())
        } finally { Thread.setDefaultUncaughtExceptionHandler(previous) }
    }

    @Test fun voiceIsUnavailableUntilTheEngineAndFilesArePresent() {
        val model = Files.createTempDirectory("voice-model")
        val clip = Files.createTempFile(model, "voice", ".wav")
        val env = mapOf("HOVEL_VOICE_MODEL" to model.toString(), "HOVEL_VOICE_REFERENCE" to clip.toString())
        try {
            assertNotNull(desktopVoiceConfig(env, engineLinked = true))
            assertNull(desktopVoiceConfig(env, engineLinked = false))
            assertNull(desktopVoiceConfig(env - "HOVEL_VOICE_REFERENCE", engineLinked = true))
            assertNull(desktopVoiceConfig(env + ("HOVEL_VOICE_MODEL" to model.resolve("missing").toString()), engineLinked = true))
        } finally { model.toFile().deleteRecursively() }
    }
}
