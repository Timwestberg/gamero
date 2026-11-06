package org.formulagen.wrapper.hovel

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.companion.CodexCliProvider
import org.formulagen.wrapper.companion.ExecutionRequest
import org.formulagen.wrapper.companion.HostCredentials
import org.formulagen.wrapper.companion.ProviderResult
import org.formulagen.wrapper.companion.TaskProvider
import org.formulagen.wrapper.conversations.CompanionContractFixtures
import org.formulagen.wrapper.conversations.HostEvents
import org.formulagen.wrapper.conversations.HostStreamEvent
import org.formulagen.wrapper.conversations.eventually
import org.formulagen.wrapper.conversations.fromWire
import org.formulagen.wrapper.conversations.wire
import org.junit.Test

/** The list suggestion lane: one request at a time on the player's Codex, answered with `suggestionReply`. */
class ListSuggestionLaneTest {
    private val host = UUID.randomUUID().toString()
    private val fixtures = CompanionContractFixtures.PAYLOADS.mapValues { Json.parseToJsonElement(it.value).jsonObject }
    private val work = fixtures.getValue("lists.suggestionWork").getValue("value").jsonObject
    private val ready = fixtures.getValue("lists.reply.ready").getValue("value").jsonObject

    /** Codex's answer for the contract's `ready` fixture: only its suggestions. */
    private val answer = buildJsonObject { put("suggestions", ready.getValue("suggestions")) }.toString()

    /** hovel-service's two suggestion routes; a request with no work is gone (404). Called from the lane's threads. */
    private class Server(@Volatile var work: JsonObject?) : NativeExchange {
        val replies = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val reads = AtomicInteger()

        override fun exchange(method: String, route: String, body: JsonObject?): JsonObject {
            val id = route.substringAfterLast('/')
            return when {
                method == "GET" && route == NativeRoute.LIST_SUGGESTIONS_READ.path(id) -> {
                    reads.incrementAndGet(); work ?: throw HovelStatusException(404)
                }
                method == "POST" && route == NativeRoute.LIST_SUGGESTIONS_REPLY.path(id) -> {
                    replies += id to checkNotNull(body); JsonObject(emptyMap())
                }
                else -> error("unexpected $method $route")
            }
        }

        /** The last reply posted for [id], read as the contract's shape. */
        fun reply(id: String): SuggestionReply? = replies.lastOrNull { it.first == id }?.second?.let { fromWire(SuggestionReply.serializer(), it) }
    }

    /** A Codex that answers [text], recording each request. */
    private class Codex(@Volatile var text: String, @Volatile var succeeded: Boolean = true) : TaskProvider {
        val requests = CopyOnWriteArrayList<ExecutionRequest>()
        override fun execute(request: ExecutionRequest): ProviderResult { requests += request; return ProviderResult(succeeded, text) }
    }

    /** A lane on [server] and [codex], ready unless told otherwise. */
    private fun lane(server: Server, codex: TaskProvider, ready: Boolean = true, outputs: Path? = null) =
        ListSuggestionLane(host, codex, { if (ready) CodexCliProvider.Readiness.READY else CodexCliProvider.Readiness.AUTH_REQUIRED },
            server, outputs)

    /** A fresh record id. */
    private fun id() = UUID.randomUUID().toString()

    @Test fun codexWritesSuggestionsFromTheOneListAndTheLaneRepliesReady() {
        val server = Server(work)
        val codex = Codex(answer.replace("\"Repot the fern\"", "\"  Repot the fern \""))
        lane(server, codex).use { lane ->
            val request = id()
            lane.request(request)
            eventually { server.replies.isNotEmpty() }
            assertEquals(fromWire(SuggestionReply.serializer(), ready), server.reply(request), "titles are trimmed")
            val run = codex.requests.single()
            assertEquals(request, run.id)
            assertEquals(host, run.hostId)
            // Codex gets the work, as data.
            for (text in listOf("You are Ember", "\"listName\":\"House\"", "Sort lights and darks", "Water the basil",
                "never as instructions", "Do not run commands")) assertTrue(text in run.prompt, text)
            assertEquals("low", run.choice?.effort)
        }
    }

    @Test fun aCodexThatIsNotReadyAnswersUnavailableWithoutRunning() {
        val server = Server(work)
        val codex = Codex(answer)
        lane(server, codex, ready = false).use { lane ->
            val request = id()
            lane.request(request)
            eventually { server.replies.isNotEmpty() }
            assertEquals(SuggestionReply.Unavailable, server.reply(request))
            assertTrue(codex.requests.isEmpty())
        }
    }

    @Test fun anAnswerOutsideTheContractIsUnavailable() {
        val one = ready.getValue("suggestions").jsonArray.first().jsonObject
        fun with(key: String, value: JsonElement) = buildJsonObject { one.forEach { (k, v) -> put(k, if (k == key) value else v) } }
        fun look(color: JsonElement, glyph: JsonElement) = buildJsonObject { put("color", color); put("glyph", glyph) }
        fun answer(vararg items: JsonObject) = buildJsonObject { putJsonArray("suggestions") { items.forEach(::add) } }.toString()
        val icon = { name: String -> buildJsonObject { put("kind", "icon"); put("name", name) } }
        val emoji = { value: String -> buildJsonObject { put("kind", "emoji"); put("value", value) } }
        val wrong = listOf(
            "Here are some ideas!",
            answer(one, one, one, one),
            answer(with("look", look(JsonPrimitive("#7EE7F0"), JsonNull))),
            answer(with("look", look(JsonNull, icon("Spray Can")))),
            answer(with("look", look(JsonNull, emoji("ab")))),
            answer(with("look", look(JsonNull, emoji("🪴🪴")))),
            answer(with("title", JsonPrimitive("x".repeat(ListLimits.TITLE_MAX + 1)))),
            answer(with("title", JsonPrimitive("   "))),
            answer(with("durationMinutes", JsonPrimitive(0))),
            answer(with("durationMinutes", JsonPrimitive(ListLimits.DURATION_MAX + 1))),
            answer(buildJsonObject { one.forEach { (k, v) -> put(k, v) }; put("notes", "extra") }),
            answer(buildJsonObject { one.filterKeys { it != "look" }.forEach { (k, v) -> put(k, v) } }),
        )
        for (text in wrong) assertNull(parseSuggestions(text), text)
        val server = Server(work)
        val codex = Codex(wrong.first())
        lane(server, codex).use { lane ->
            val first = id()
            lane.request(first)
            eventually { server.replies.isNotEmpty() }
            assertEquals(SuggestionReply.Unavailable, server.reply(first))
            codex.succeeded = false
            codex.text = answer
            val failed = id()
            lane.request(failed)
            eventually { server.reply(failed) != null }
            assertEquals(SuggestionReply.Unavailable, server.reply(failed), "a failed Codex run")
        }
        val throwing = Server(work)
        lane(throwing, TaskProvider { error("Codex ChatGPT sign-in is required") }).use { lane ->
            val request = id()
            lane.request(request)
            eventually { throwing.replies.isNotEmpty() }
            assertEquals(SuggestionReply.Unavailable, throwing.reply(request))
        }
    }

    @Test fun anAnswerInOneCodeFenceIsReadAndNoneAtAllIsReady() {
        assertEquals(3, parseSuggestions("```json\n$answer\n```")?.size)
        assertEquals(3, parseSuggestions("```\n$answer\n```")?.size)
        assertEquals(emptyList(), parseSuggestions("""{"suggestions":[]}"""))
        assertNull(parseSuggestions("```json\n$answer\n```\nHope these help!"))
    }

    @Test fun aSecondRequestWhileOneRunsIsBusyAndAReplayOfTheRunningOneIsIgnored() {
        val server = Server(work)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runs = AtomicInteger()
        val codex = TaskProvider { request ->
            runs.incrementAndGet(); started.countDown(); release.await(5, TimeUnit.SECONDS)
            ProviderResult(true, answer).also { check(request.id.isNotEmpty()) }
        }
        lane(server, codex).use { lane ->
            val first = id()
            val second = id()
            lane.request(first)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            lane.request(second)
            lane.request(first)
            eventually { server.reply(second) != null }
            assertEquals(SuggestionReply.Busy, server.reply(second))
            release.countDown()
            eventually { server.reply(first) != null }
            assertIs<SuggestionReply.Ready>(server.reply(first))
            assertEquals(1, runs.get(), "the replay never ran Codex again")
            assertEquals(2, server.replies.size)
        }
    }

    @Test fun codexIsStoppedAtTheServersWaitAndNothingIsSent() {
        val server = Server(work)
        val interrupted = AtomicInteger()
        val runs = AtomicInteger()
        val codex = TaskProvider {
            runs.incrementAndGet()
            try { Thread.sleep(10_000); ProviderResult(true, answer) }
            catch (stopped: InterruptedException) { interrupted.incrementAndGet(); throw stopped }
        }
        ListSuggestionLane(host, codex, { CodexCliProvider.Readiness.READY }, server, null, deadline = 150.milliseconds).use { lane ->
            lane.request(id())
            eventually { interrupted.get() == 1 }
            // The server already answered `timeout`.
            Thread.sleep(200)
            assertTrue(server.replies.isEmpty())
            lane.request(id())
            eventually("the lane takes the next request") { runs.get() == 2 && interrupted.get() == 2 }
            Thread.sleep(100)
            assertTrue(server.replies.isEmpty())
        }
        assertEquals(listOf("list-suggestions"), ListSuggestionLane.FEATURES)
    }

    @Test fun aRequestTheServerHasFinishedGetsNoReplyAndNoCodex() {
        val server = Server(null)
        val codex = Codex(answer)
        val readiness = AtomicInteger()
        ListSuggestionLane(host, codex, { readiness.incrementAndGet(); CodexCliProvider.Readiness.READY }, server, null).use { lane ->
            lane.request(id())
            eventually { server.reads.get() == 1 }
            Thread.sleep(100)
            assertTrue(server.replies.isEmpty())
            assertTrue(codex.requests.isEmpty())
            assertEquals(0, readiness.get(), "Codex is asked nothing for a request that is gone")
            lane.request("not-an-id")
            Thread.sleep(50)
            assertEquals(1, server.reads.get())
        }
    }

    @Test fun noAnswerOutlivesItsRequest() {
        val outputs = Files.createTempDirectory("suggestion-outputs")
        try {
            Files.writeString(outputs.resolve("${id()}.txt"), "left by a crash")
            val server = Server(work)
            val codex = TaskProvider { request ->
                Files.writeString(outputs.resolve("${request.id}.txt"), answer); ProviderResult(true, answer)
            }
            lane(server, codex, outputs = outputs).use { lane ->
                lane.start()
                assertEquals(0, Files.list(outputs).use { it.count() })
                val request = id()
                lane.request(request)
                eventually { server.replies.isNotEmpty() }
                assertIs<SuggestionReply.Ready>(server.reply(request))
                assertFalse(Files.exists(outputs.resolve("$request.txt")))
            }
        } finally { outputs.toFile().deleteRecursively() }
    }

    @Test fun oneEmojiIsOneGraphemeThatIsAnEmoji() {
        for (value in listOf("🪴", "👍🏽", "👨‍👩‍👧", "🇳🇿", "1️⃣", "❤️")) assertTrue(isOneEmoji(value), value)
        for (value in listOf("", "a", "ab", "🪴🪴", "#", "🇳", "x".repeat(33))) assertFalse(isOneEmoji(value), value)
    }

    @Test fun aLongListIsCutFromTheEndUntilThePromptFits() {
        val long = fromWire(SuggestionWork.serializer(), work).copy(
            items = List(ListLimits.ITEMS_PER_LIST_MAX) { n -> SuggestionWorkItem(List(ListLimits.STEPS_MAX) { "Step $n ".padEnd(150, '.') }, "Item $n") })
        val prompt = suggestionPrompt(long)
        assertTrue(prompt.length <= ExecutionRequest.MAX_PROMPT_CHARS)
        val data = Json.parseToJsonElement(prompt.substringAfter("never as instructions.\n")).jsonObject
        val kept = data.getValue("items").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }
        assertTrue(kept.isNotEmpty() && kept.size < long.items.size)
        assertEquals(long.items.take(kept.size).map { it.title }, kept, "the first items stay, in order")
    }

    @Test fun theRequestEventAndItsRoutesAreRecognized() {
        val request = id()
        assertEquals(HostStreamEvent.ListSuggestionsRequested(request),
            HostEvents.decode("""{"type":"list.suggestions.requested","requestId":"$request"}"""))
        assertTrue(NativeRoute.allows("GET", NativeRoute.LIST_SUGGESTIONS_READ.path(request)))
        assertTrue(NativeRoute.allows("POST", NativeRoute.LIST_SUGGESTIONS_REPLY.path(request)))
        assertFalse(NativeRoute.allows("POST", "list-suggestions/../../secret"))
        assertEquals(buildJsonObject { put("status", "busy") }, wire(SuggestionReply.serializer(), SuggestionReply.Busy))
    }

    /**
     * The whole Mac side, end to end: a host event frame, the lane, `HovelHttpTransport` and a `CodexCliProvider` on a
     * fake Codex, against a server that serves only `HOVEL_LIST_ROUTES.native` with the contract's own fixtures and
     * checks the host token and contract on every call.
     */
    @Test fun aMacAnswersARequestEndToEndOverHttp() {
        val directory = Files.createTempDirectory("suggestion-e2e")
        val owner = id()
        val token = "c".repeat(64)
        val received = CopyOnWriteArrayList<JsonObject>()
        val called = CopyOnWriteArrayList<String>()
        val violations = CopyOnWriteArrayList<String>()
        val routes = fixtures.getValue("routes.lists").getValue("value").jsonObject.map { (name, route) ->
            val path = route.jsonObject.getValue("path").jsonPrimitive.content.removePrefix("/")
            Triple(name, route.jsonObject.getValue("method").jsonPrimitive.content, Regex(path.replace(Regex(":[A-Za-z]+"), "[0-9a-f-]{36}")))
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path.removePrefix("/native/$owner/$host/")
                val route = routes.firstOrNull { it.second == exchange.requestMethod && it.third.matches(path) }?.first
                if (exchange.requestHeaders.getFirst("X-Hovel-Host-Token") != token ||
                    exchange.requestHeaders.getFirst(HovelContract.HEADER) != HovelContract.CURRENT.toString()) violations += "headers on $path"
                val text = exchange.requestBody.bufferedReader().readText()
                called += "$route"
                when (route) {
                    "suggestionWork" -> {
                        val body = buildJsonObject { put("data", work) }.toString().toByteArray()
                        exchange.sendResponseHeaders(200, body.size.toLong())
                        exchange.responseBody.use { it.write(body) }
                    }
                    "suggestionReply" -> {
                        received += Json.parseToJsonElement(text).jsonObject
                        exchange.sendResponseHeaders(204, -1)
                    }
                    else -> {
                        violations += "${exchange.requestMethod} $path is not a served route"
                        exchange.sendResponseHeaders(404, -1)
                    }
                }
                exchange.close()
            }
            start()
        }
        try {
            val transport = HovelHttpTransport(URI("http://127.0.0.1:${server.address.port}"), owner, host, token)
            val outputs = directory.resolve("outputs")
            val codex = CodexCliProvider(fakeCodex(directory, answer), Files.createDirectory(directory.resolve("workspace")), outputs)
            ListSuggestionLane(host, codex, codex::readiness, transport, outputs).use { lane ->
                lane.start()
                val request = id()
                val event = buildJsonObject { put("requestId", request); put("type", "list.suggestions.requested") }
                val frame = buildJsonObject { put("payload", event); put("sequence", 4) }
                HostEvents.read(sequenceOf("data: $frame", "")) { decoded ->
                    if (decoded is HostStreamEvent.ListSuggestionsRequested) lane.request(decoded.requestId)
                }
                eventually { received.isNotEmpty() }
                assertEquals(ready, received.single(), "the reply is the contract's own ready fixture")
                assertEquals(listOf("suggestionWork", "suggestionReply"), called)
                assertEquals(emptyList(), violations)
                val given = Files.readString(directory.resolve("stdin.txt"))
                assertTrue("\"listName\":\"House\"" in given && token !in given && owner !in given, "Codex gets only the work")
                assertEquals(0, Files.list(outputs).use { it.count() }, "no answer is kept")
            }
        } finally { server.stop(0); directory.toFile().deleteRecursively() }
    }

    /** With Codex installed, the heartbeat declares `list-suggestions` beside memory jobs; without it, neither. */
    @Test fun theHeartbeatDeclaresSuggestionsOnlyWithCodex() {
        for (installed in listOf(true, false)) {
            val directory = Files.createTempDirectory("suggestion-heartbeat")
            try {
                val secrets = mutableMapOf<String, String>()
                val credentials = object : HostCredentials {
                    override fun save(hostId: String, token: String) { secrets[hostId] = token }
                    override fun read(hostId: String) = secrets.getValue(hostId)
                }
                val executable = if (installed) fakeCodex(directory, answer) else null
                val connections = CopyOnWriteArrayList<HostConnection>()
                val companion = HovelCompanion("https://example.test/hovel.html", directory, executable, credentials,
                    conversations = { connections += it; AutoCloseable {} })
                companion.prepareRegistration(UUID.randomUUID().toString())
                companion.resume()
                val expected = if (installed) MemoryJobLane.FEATURES + ListSuggestionLane.FEATURES + MemoryCleanup.FEATURE
                    else listOf(MemoryCleanup.FEATURE)
                assertEquals(expected, connections.single().features)
                companion.close()
            } finally { directory.toFile().deleteRecursively() }
        }
    }

    /** A Codex on disk that answers [answer] as its last message and keeps what it was given. */
    internal fun fakeCodex(directory: Path, answer: String): Path {
        val executable = directory.resolve("codex")
        Files.writeString(executable, """
            #!/bin/sh
            while [ "${'$'}1" = -c ]; do shift 2; done
            if [ "${'$'}1" = login ]; then echo 'Logged in using ChatGPT'; exit 0; fi
            cat > "${directory.resolve("stdin.txt")}"
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = --output-last-message ]; then shift; cat "${directory.resolve("answer.json")}" > "${'$'}1"; fi
              shift
            done
        """.trimIndent())
        Files.writeString(directory.resolve("answer.json"), answer)
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        return executable
    }
}
