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
import org.formulagen.wrapper.providers.CodexAccountRpc
import org.formulagen.wrapper.providers.CodexRequestRejected
import kotlin.test.*

class DesktopChatTest {
    private val threadId = "10000000-0000-4000-8000-000000000001"
    private val freshThreadId = "10000000-0000-4000-8000-000000000002"
    private fun scope(profile: String = "local", companion: String = UUID.randomUUID().toString()) = ChatScope(profile, companion, "Ember", UUID.randomUUID().toString())
    private fun send(scope: ChatScope, speak: Boolean = true) = ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, UUID.randomUUID().toString(), "Hello", speak)
    private class Speech : ChatSpeech {
        val said = CopyOnWriteArrayList<String>()
        var stops = 0
        override fun status() = ChatVoice()
        override fun speak(text: String) { said += text }
        override fun stop() { stops++ }
        override fun close() = Unit
    }
    private inner class Rpc(val notify: (String, JsonObject) -> Unit) : CodexAccountRpc {
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

    @Test fun streamsPersistsDeduplicatesAndResumesOnlyItsCompanion() = runBlocking {
        val root = Files.createTempDirectory("chat-history")
        val speech = Speech(); val rpcs = CopyOnWriteArrayList<Rpc>()
        DesktopChat(root, Path.of("/usr/bin/true"), speech) { _, receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == "ready" }
            val request = send(a); chat.send(request); chat.send(request)
            await { rpcs[0].calls.contains("turn/start") }
            rpcs[0].delta("Ignored", "other-turn"); rpcs[0].delta("Hello "); rpcs[0].delta("friend"); rpcs[0].complete()
            await { state(chat,a).state == "ready" }
            assertEquals("Hello friend", chat.snapshot(a).messages.last().text)
            assertEquals(listOf("Hello friend"), speech.said)
            assertEquals(1, rpcs[0].calls.count { it == "turn/start" })
            chat.closeSession(a)
            val b = scope(); chat.open(b); await { state(chat,b).state == "ready" }
            assertTrue(chat.snapshot(b).messages.isEmpty())
            chat.closeSession(a); assertEquals("ready", chat.snapshot(b).state)
            val a2 = a.copy(sessionId = UUID.randomUUID().toString())
            chat.open(a2); await { state(chat,a2).state == "ready" }
            assertEquals("Hello friend", chat.snapshot(a2).messages.last().text)
            chat.send(send(a2,false)); await { rpcs.last().calls.contains("turn/start") }
            assertContains(rpcs.last().calls,"thread/resume")
            val resume = rpcs.last().params[rpcs.last().calls.indexOf("thread/resume")]
            assertEquals(JsonPrimitive(true), resume["excludeTurns"])
            assertEquals(JsonPrimitive(false), rpcs.last().params[rpcs.last().calls.indexOf("account/read")]["refreshToken"])
            chat.stop(a2); rpcs.last().delta("late"); rpcs.last().complete()
            assertEquals("cancelled", chat.snapshot(a2).state)
            assertEquals(listOf("Hello friend"), speech.said)
            val otherProfile = scope(UUID.randomUUID().toString(),a.companionId)
            chat.open(otherProfile); await { state(chat,otherProfile).state == "ready" }
            assertTrue(chat.snapshot(otherProfile).messages.isEmpty())
        }
    }

    @Test fun buffersEventsBeforeTurnAcknowledgementAndMutesPendingReply() = runBlocking {
        val speech = Speech(); lateinit var rpc: Rpc
        DesktopChat(Files.createTempDirectory("chat-early"), Path.of("/usr/bin/true"), speech) { _, receive -> Rpc(receive).also { rpc=it; it.earlyReply=true } }.use { chat ->
            val a=scope(); chat.open(a); await { state(chat,a).state=="ready" }
            chat.send(send(a)); await { state(chat,a).state=="ready" }
            assertEquals("Early hello",chat.snapshot(a).messages.last().text)
            assertEquals(listOf("Early hello"),speech.said)
            rpc.earlyReply=false; chat.send(send(a)); chat.stopSpeaking(a)
            await { rpc.calls.count { it=="turn/start" }==2 }
            rpc.delta("Muted"); rpc.complete(); await { state(chat,a).state=="ready" }
            assertEquals(listOf("Early hello"),speech.said)
        }
    }

    @Test fun stoppingAnAccountProbeCannotResurrectIt() = runBlocking {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val speech=Speech(); lateinit var old: Rpc
        DesktopChat(Files.createTempDirectory("chat-stop"),Path.of("/usr/bin/true"),speech) { _, receive ->
            Rpc(receive).also { old=it; it.beforeAccount={ entered.countDown(); release.await(5,TimeUnit.SECONDS) } }
        }.use { chat ->
            val a=scope(); chat.open(a); assertTrue(entered.await(5,TimeUnit.SECONDS))
            chat.stop(a); release.countDown(); old.notify("transport/closed",buildJsonObject {})
            Thread.sleep(50); assertEquals("cancelled",chat.snapshot(a).state); await { old.closed }
        }
    }

    @Test fun disconnectedAccountsNeverStartATurnAndCorruptHistoryIsPreserved() = runBlocking {
        val root=Files.createTempDirectory("chat-invalid"); lateinit var rpc: Rpc
        DesktopChat(root,Path.of("/usr/bin/true"),Speech()) { _, receive -> Rpc(receive).also { rpc=it; it.connected=false } }.use { chat ->
            val a=scope(); chat.open(a); await { state(chat,a).state=="disconnected" }
            assertFails { chat.send(send(a)) }; assertEquals(listOf("account/read"),rpc.calls.toList())
            await { rpc.closed }
            chat.closeSession(a)
            val file=root.resolve("local/codex/companion-chats/${a.companionId}.json")
            Files.writeString(file,"broken")
            assertFails { chat.open(a) }; assertEquals("broken",Files.readString(file))
        }
    }

    @Test fun failedResumeStartsAFreshThreadAndSavesIt() = runBlocking {
        val root = Files.createTempDirectory("chat-resume"); val rpcs = CopyOnWriteArrayList<Rpc>()
        DesktopChat(root, Path.of("/usr/bin/true"), Speech()) { _, receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == "ready" }
            chat.send(send(a, false)); await { rpcs[0].calls.contains("turn/start") }
            rpcs[0].complete(); await { state(chat,a).state == "ready" }
            chat.closeSession(a)
            val again = a.copy(sessionId = UUID.randomUUID().toString())
            chat.open(again); await { state(chat,again).state == "ready" }
            rpcs.last().resumeRejected = true
            chat.send(send(again, false)); await { rpcs.last().calls.contains("turn/start") }
            // Opening and sending each confirm the account before any thread call.
            assertEquals(listOf("account/read", "account/read", "thread/resume", "thread/start", "turn/start"), rpcs.last().calls.toList())
            assertEquals(JsonPrimitive(freshThreadId), rpcs.last().params.last()["threadId"])
            rpcs.last().delta("Fresh hello"); rpcs.last().complete(); await { state(chat,again).state == "ready" }
            assertEquals("Fresh hello", chat.snapshot(again).messages.last().text)
            val saved = ChatHistory(root.resolve("local/codex/companion-chats/${a.companionId}.json")).read()
            assertEquals(freshThreadId, saved.threadId)
            assertEquals(4, saved.messages.size)
        }
    }

    @Test fun losingAnIdleAppServerStartsANewOneOnTheNextSend() = runBlocking {
        val rpcs = CopyOnWriteArrayList<Rpc>()
        DesktopChat(Files.createTempDirectory("chat-idle-loss"), Path.of("/usr/bin/true"), Speech()) { _, receive -> Rpc(receive).also { rpcs += it } }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == "ready" }
            chat.send(send(a, false)); await { rpcs[0].calls.contains("turn/start") }
            rpcs[0].complete(); await { state(chat,a).state == "ready" }
            rpcs[0].notify("transport/closed", buildJsonObject {})
            await { rpcs[0].closed }
            assertEquals("ready", chat.snapshot(a).state)
            chat.send(send(a, false)); await { rpcs.size == 2 && rpcs[1].calls.contains("turn/start") }
            assertEquals(listOf("account/read", "thread/resume", "turn/start"), rpcs[1].calls.toList())
            assertEquals(1, rpcs[0].calls.count { it == "turn/start" })
        }
    }

    @Test fun viewsTheHostEndedKeepTheirFinalSnapshot() = runBlocking {
        DesktopChat(Files.createTempDirectory("chat-no-codex"), null, Speech()).use { chat ->
            val a = scope()
            assertEquals("error", chat.open(a).state)
            assertEquals("error", chat.snapshot(a).state)
            assertEquals("Install Codex on this Mac and restart Hovel.", chat.snapshot(a).notice)
            assertFails { chat.snapshot(scope()) }
            chat.closeSession(a); assertFails { chat.snapshot(a) }
        }
        DesktopChat(Files.createTempDirectory("chat-invalidate"), Path.of("/usr/bin/true"), Speech()) { _, receive -> Rpc(receive) }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == "ready" }
            chat.invalidate()
            assertEquals("error", chat.snapshot(a).state)
            assertFails { chat.send(send(a)) }
            chat.open(a); await { state(chat,a).state == "ready" }
        }
    }

    @Test fun stoppingNeverWaitsForTheAppServerToExit() = runBlocking {
        val closing = CountDownLatch(1); val release = CountDownLatch(1); lateinit var rpc: Rpc
        DesktopChat(Files.createTempDirectory("chat-release"), Path.of("/usr/bin/true"), Speech()) { _, receive ->
            Rpc(receive).also { rpc = it; it.onClose = { closing.countDown(); release.await(5, TimeUnit.SECONDS) } }
        }.use { chat ->
            val a = scope(); chat.open(a); await { state(chat,a).state == "ready" }
            val started = System.nanoTime()
            assertEquals("cancelled", chat.stop(a).state)
            assertEquals("cancelled", chat.snapshot(a).state)
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4))
            assertFalse(rpc.closed)
            release.countDown(); await { rpc.closed }
        }
    }

    @Test fun voiceSanitizesTextAndClampsInvalidPcm() {
        assertEquals("Hello link Code is shown in the chat.",speechText("**Hello** [link](https://example.test) ```secret code```"))
        assertEquals(1200,speechText("x".repeat(2000)).length)
        assertContentEquals(byteArrayOf(0,0,-1,127,1,-128,0,0), pcm16(floatArrayOf(0f,2f,-2f,Float.NaN)))
        assertContains(chatProcessArguments(),"features.shell_tool=false")
        assertContains(chatProcessArguments(),"web_search=\"disabled\"")
        DesktopSpeech(null).use { speech -> speech.speak("Hello"); assertEquals("unavailable",speech.status().state) }
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
