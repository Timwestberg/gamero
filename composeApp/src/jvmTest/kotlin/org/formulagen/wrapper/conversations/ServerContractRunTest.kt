package org.formulagen.wrapper.conversations

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.hovel.HovelHttpTransport
import org.formulagen.wrapper.hovel.hovelHostFeatures

/**
 * The whole Mac stack (runner, engine, transport) against hovel-service's own native route table and payloads, as
 * `CompanionContractFixtures` carries them from server-platform: the server answers only the routes in that table,
 * with payloads its zod schemas accepted, and every body the Mac sends must have the shape of a payload the same
 * schemas accepted. A drift on either side fails here before it fails against a running server.
 */
class ServerContractRunTest {
    private val fixtures = CompanionContractFixtures.PAYLOADS.mapValues { Json.parseToJsonElement(it.value).jsonObject }

    private fun value(name: String) = fixtures.getValue(name).getValue("value").jsonObject

    private val claimed = value("claim.claimed").getValue("claim").jsonObject
    private val message = claimed.getValue("message").jsonObject
    private val messageId = message.getValue("id").jsonPrimitive.content
    private val conversationId = claimed.getValue("conversation").jsonObject.getValue("id").jsonPrimitive.content
    private val host = claimed.getValue("project").jsonObject.getValue("computerId").jsonPrimitive.content

    /** Every key path in [element], array items merged, so two payloads compare by the fields they carry. */
    private fun shape(element: JsonElement, at: String = ""): Set<String> = when (element) {
        is JsonObject -> element.flatMapTo(HashSet()) { (key, item) -> setOf("$at.$key") + shape(item, "$at.$key") }
        is JsonArray -> element.flatMapTo(HashSet()) { shape(it, "$at[]") }
        else -> emptySet()
    }

    /** The shapes the server's schemas accepted for [schema], by checkpoint kind for checkpoints. */
    private fun accepted(schema: String, kind: String? = null) = fixtures.values
        .filter { it.getValue("schema").jsonPrimitive.content == schema }
        .map { it.getValue("value").jsonObject }
        .filter { kind == null || it["checkpoint"]?.jsonObject?.get("kind")?.jsonPrimitive?.content == kind }
        .map { shape(it) }

    /** The `stopped` settle the server expects for the message a stop answer names, under [claimId]. */
    private fun stopped(claimId: String, revision: Long) = buildJsonObject {
        put("claimId", claimId); put("claimedMessageId", messageId); put("expectedRevision", revision); put("state", "stopped")
    }

    /** A claim route's answer: a `data` payload, or a 409 refusal body. */
    private sealed interface Answer {
        data class Data(val data: JsonElement) : Answer
        data class Refused(val body: JsonObject) : Answer
    }

    /**
     * hovel-service on a local port, serving only the routes in its native table. Its stream sends [event] (a
     * `hostStreamEvent` fixture, or nothing) in the server's SSE envelope on each connect; [claim] answers the n-th
     * claim (from 0) given its body; every other route answers a payload its schemas accepted. Every request must carry
     * the host token and this build's contract, and every body the Mac sends the shape of an accepted payload. While
     * [contract] is older than this build's, every companion route answers as `requireClientContract` does (400
     * `unsupported-contract`, kept in [refused]); hovel-service's own heartbeat still answers.
     */
    private inner class Served(
        private val event: String?,
        private val contract: () -> Int = { HovelContract.CURRENT },
        private val claim: (body: JsonObject, n: Int) -> Answer,
    ) : AutoCloseable {
        val owner = id()
        val token = "c".repeat(64)
        val seen = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
        val violations = CopyOnWriteArrayList<String>()
        val refused = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
        val claims = AtomicInteger()
        private val done = CountDownLatch(1)
        /** The served routes, from the server's own table: method, then the path as a pattern. */
        private val routes = value("routes.native").map { (name, route) ->
            val path = route.jsonObject.getValue("path").jsonPrimitive.content.removePrefix("/")
            Triple(name, route.jsonObject.getValue("method").jsonPrimitive.content, Regex(path.replace(Regex(":[A-Za-z]+"), "[0-9a-f-]{36}")))
        }
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/", this@Served::handle)
            start()
        }
        val transport = HovelHttpTransport(URI("http://127.0.0.1:${server.address.port}"), owner, host, token)

        /** The bodies the Mac sent on [route], in order. */
        fun bodies(route: String) = seen.filter { it.first == route }.mapNotNull { it.second }

        val called: Set<String> get() = seen.map { it.first }.toSet()

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path.removePrefix("/native/$owner/$host/")
            val route = routes.firstOrNull { it.second == exchange.requestMethod && it.third.matches(path) }?.first
            val text = exchange.requestBody.bufferedReader().readText()
            val body = text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
            if (exchange.requestHeaders.getFirst("X-Hovel-Host-Token") != token ||
                exchange.requestHeaders.getFirst(HovelContract.HEADER) != HovelContract.CURRENT.toString()) violations += "headers on $path"
            if (route == null) {
                violations += "${exchange.requestMethod} $path is not a served route"
                exchange.sendResponseHeaders(404, -1); exchange.close(); return
            }
            val serving = contract()
            if (route != "heartbeat" && serving < HovelContract.CURRENT) {
                refused += route to body
                val error = buildJsonObject { put("code", "unsupported-contract"); put("serverContract", serving) }.toString().toByteArray()
                exchange.sendResponseHeaders(400, error.size.toLong())
                exchange.responseBody.use { it.write(error) }
                return
            }
            seen += route to body
            if (route == "stream") {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                runCatching {
                    // The server's SSE hub frame: its event name and envelope, with a hostStreamEvent as the payload.
                    event?.let { name ->
                        val envelope = buildJsonObject {
                            put("occurredAt", AT); put("payload", value(name)); put("sequence", "1"); put("type", "hovel.host.notified"); put("version", 1)
                        }
                        exchange.responseBody.write("id: 1\nevent: hovel.host.notified\ndata: $envelope\n\n".toByteArray())
                        exchange.responseBody.flush()
                    }
                    done.await(10, TimeUnit.SECONDS)
                }
                exchange.close()
                return
            }
            // Its top-level fields are those of an accepted payload, and it sends no field the accepted payloads lack.
            fun expect(schema: String, kind: String? = null) {
                val shapes = accepted(schema, kind)
                val sent = body?.let(::shape).orEmpty()
                fun top(paths: Set<String>) = paths.filterTo(HashSet()) { it.count { c -> c == '.' } == 1 }
                val fits = body != null && shapes.any { top(it) == top(sent) } && sent.all { path -> shapes.any { path in it } }
                if (!fits) violations += "$route sent a body no accepted $schema ${kind.orEmpty()} has: $body"
            }
            val answer: Answer = when (route) {
                "heartbeat" -> { expect("heartbeatInput"); Answer.Data(buildJsonObject {}) }
                "conversationClaim" -> { expect("claimRequest"); claim(body ?: buildJsonObject {}, claims.getAndIncrement()) }
                "messageProgress" -> {
                    expect("messageProgress")
                    Answer.Data(JsonObject(message + mapOf("state" to body!!.getValue("state"),
                        "revision" to JsonPrimitive(body.getValue("expectedRevision").jsonPrimitive.long + 1))))
                }
                "messageCheckpoint" -> {
                    expect("messageCheckpointRequest", body?.get("checkpoint")?.jsonObject?.get("kind")?.jsonPrimitive?.content)
                    Answer.Data(buildJsonObject {})
                }
                "messageDelta" -> { expect("replyDeltaRequest"); Answer.Data(buildJsonObject { put("relayed", true) }) }
                "messageSend" -> { expect("messageSend"); Answer.Data(value("message.queued")) }
                "artifactText" -> Answer.Data(if (exchange.requestURI.query == "cursor=0") value("artifactText.page") else value("artifactText.last"))
                "projectRead" -> Answer.Data(value("project"))
                "projectFolders" -> { expect("projectFoldersSave"); Answer.Data(value("project")) }
                "approvalRead" -> Answer.Data(value("approval.answeredByPhone"))
                else -> { violations += "$route is not one the Mac should call here"; Answer.Data(buildJsonObject {}) }
            }
            val (status, bytes) = when (answer) {
                is Answer.Data -> 200 to buildJsonObject { put("data", answer.data) }.toString().toByteArray()
                is Answer.Refused -> 409 to answer.body.toString().toByteArray()
            }
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            done.countDown()
            server.stop(0)
        }
    }

    /** This Mac's whole stack on [served]: runner, coordinator engine, approvals, folders, transport, and its own store. */
    private inner class Mac(served: Served, now: () -> Long = System::nanoTime, heartbeatEvery: Duration = 10.seconds) : AutoCloseable {
        val root = folder("server-contract")
        val account = id()
        val store = ConversationStore(root, account).open()
        val chat = ScriptedRuntime(RuntimeId.CODEX) { "{\"kind\":\"answer\"}\nSign-in is on its way." }
        val statuses = CopyOnWriteArrayList<String>()
        val runner: ConversationRunner
        val api = HttpConversationApi(served.transport, served.transport::openStream)

        init {
            val dialogs = NativeDialogs(Screen())
            val approvals = RemoteApprovals(dialogs, api, host)
            val turns = ProviderTurns(listOf(chat), emptyList(), ConnectedProviders(), approvals)
            val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
            val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(dialogs), api)
            val directory = HostDirectory(ConnectedProviders(), host, setOf(RuntimeId.CODEX), emptySet(),
                hovelHostFeatures(tasks = true, memory = true), taskProviderState = { "ready" })
            runner = ConversationRunner(api, store, engine, approvals, folders, ProjectFolderPublisher(folders, api, account, host) { emptyList() },
                directory::heartbeat, status = { statuses += it }, heartbeatEvery = heartbeatEvery, now = now)
        }

        override fun close() {
            runner.close()
            store.close()
        }
    }

    @Test fun aMessageRunsEndToEndOnTheServersRoutesAndShapes() {
        val input = value("checkpoint.textArtifact").getValue("checkpoint").jsonObject.getValue("artifact").jsonObject
        Served("hostEvent.work.available") { body, n ->
            if (n > 0) Answer.Data(value("claim.empty")) else Answer.Data(buildJsonObject {
                put("status", "claimed")
                put("claim", JsonObject(claimed + mapOf(
                    "claimId" to body.getValue("claimId"),
                    "message" to JsonObject(message + ("inputs" to JsonArray(listOf(input))))
                )))
            })
        }.use { served ->
            Mac(served).use { mac ->
                mac.runner.start()
                eventually("the message settled and the lane looked again") {
                    served.claims.get() >= 2 && served.bodies("messageProgress").any { it["state"]?.jsonPrimitive?.content == "answered" }
                }
                // A heard voice turn goes out on the server's own route, and its conversation is claimed again at once.
                val heard = heardTurns(mac.api, mac.runner)
                heard.send(conversationId, id(), "What time is it?")
                val before = served.claims.get()
                heard.claimNow(conversationId)
                eventually("the heard turn's conversation was claimed at once") { served.claims.get() > before }
                mac.runner.close()
                assertEquals(emptyList(), served.violations)
                assertTrue(served.called.containsAll(setOf("heartbeat", "stream", "conversationClaim", "messageProgress", "messageCheckpoint",
                    "messageDelta", "artifactText", "projectFolders", "messageSend")), "routes called: ${served.called}")
                val reply = served.bodies("messageCheckpoint").map { it.getValue("checkpoint").jsonObject }
                    .last { it.getValue("kind").jsonPrimitive.content == "message.appended" }
                assertEquals("Sign-in is on its way.", reply.getValue("message").jsonObject.getValue("text").jsonPrimitive.content)
                assertEquals(conversationId, value("hostEvent.work.available").getValue("conversationId").jsonPrimitive.content)
                val stored = mac.store.read(conversationId).brain.run!!.artifacts.single { it.id == input.getValue("id").jsonPrimitive.content }
                assertEquals("Sign-in added.", mac.store.bytes(conversationId, stored).decodeToString(), "the input's whole text, read in two pages")
            }
        }
    }

    @Test fun aStopForAClaimThisMacLostTakesTheMessageOverAndSettlesItStopped() {
        Served("hostEvent.stop.requested") { _, n -> Answer.Data(value(if (n == 0) "claim.stopRequestedRunning" else "claim.empty")) }.use { served ->
            Mac(served).use { mac ->
                mac.runner.start()
                eventually("the stop settled and the lane looked again") { served.claims.get() >= 2 }
                mac.runner.close()
                assertEquals(emptyList(), served.violations)
                val claim = served.bodies("conversationClaim").first()
                assertEquals(conversationId, claim.getValue("conversationId").jsonPrimitive.content, "the conversation the stop names")
                assertEquals(listOf(stopped(claim.getValue("claimId").jsonPrimitive.content, 3)), served.bodies("messageProgress"),
                    "stopped under the claim that took it over, at the running message's revision")
                assertEquals(emptySet(), served.called - setOf("heartbeat", "stream", "conversationClaim", "messageProgress"))
                assertEquals(emptyList(), mac.chat.requests, "no provider turn")
                assertEquals(emptyList(), mac.store.pendingClaims())
            }
        }
    }

    @Test fun aReplayedClaimThatNeverLearnedItsMessageSettlesItStoppedUnderItsJournaledId() {
        Served(null) { _, n -> Answer.Data(value(if (n == 0) "claim.stopRequested" else "claim.empty")) }.use { served ->
            Mac(served).use { mac ->
                val journaled = mac.store.journal(ClaimEntry(id(), conversationId, ClaimPhase.CLAIMING))
                mac.runner.start()
                eventually("the stop settled and the lane looked again") { served.claims.get() >= 2 }
                mac.runner.close()
                assertEquals(emptyList(), served.violations)
                assertEquals(journaled.claimId, served.bodies("conversationClaim").first().getValue("claimId").jsonPrimitive.content)
                assertEquals(listOf(stopped(journaled.claimId, 2)), served.bodies("messageProgress"))
                assertEquals(emptyList(), mac.chat.requests)
                assertEquals(emptyList(), mac.store.pendingClaims())
            }
        }
    }

    @Test fun aServerBeforeContract2RefusingAStoppedReplaySettlesTheClaimTheMacKnows() {
        // hovel-service's `stopRequested()` refusal, as a server before contract 2 answers a stopped claim's replay.
        val refusal = buildJsonObject {
            put("code", "stop-requested"); put("message", "The person asked to stop this message. Settle it as stopped."); put("statusCode", 409)
        }
        Served(null) { _, n -> if (n == 0) Answer.Refused(refusal) else Answer.Data(value("claim.empty")) }.use { served ->
            Mac(served).use { mac ->
                val journaled = mac.store.journal(ClaimEntry(id(), conversationId, ClaimPhase.CLAIMED, messageId, 2))
                mac.runner.start()
                eventually("the stop settled and the lane looked again") { served.claims.get() >= 2 }
                mac.runner.close()
                assertEquals(emptyList(), served.violations)
                assertEquals(listOf(stopped(journaled.claimId, 2)), served.bodies("messageProgress"))
                assertEquals(emptyList(), mac.chat.requests)
                assertEquals(emptyList(), mac.store.pendingClaims())
            }
        }
    }

    @Test fun aServiceOlderThanThisMacLosesNoJournaledWorkAndRecoversOnceItAnswersContract2() {
        val serving = AtomicInteger(1)
        Served("hostEvent.work.available", serving::get) { body, n ->
            if (n > 0) Answer.Data(value("claim.empty"))
            else Answer.Data(buildJsonObject { put("status", "claimed"); put("claim", JsonObject(claimed + ("claimId" to body.getValue("claimId")))) })
        }.use { served ->
            Mac(served, heartbeatEvery = 100.milliseconds).use { mac ->
                val journaled = mac.store.journal(ClaimEntry(id(), conversationId, ClaimPhase.CLAIMING))
                mac.runner.start()
                eventually {
                    ConversationRunner.SERVICE_OLDER in mac.statuses && "heartbeat" in served.called &&
                        served.refused.map { it.first }.containsAll(listOf("stream", "conversationClaim"))
                }
                assertEquals(listOf(journaled), mac.store.pendingClaims(), "nothing journaled is dropped as final")
                assertEquals(setOf("heartbeat"), served.called, "an older service answers only its own heartbeat")
                assertFalse(ConversationRunner.UPDATE_REQUIRED in mac.statuses)
                // The service updates: the journaled claim is asked again with its id and runs, and the stream connects.
                serving.set(2)
                eventually("it recovered") { served.bodies("messageProgress").any { it["state"]?.jsonPrimitive?.content == "answered" } }
                eventually { mac.runner.connected && mac.statuses.last() == ConversationRunner.CONNECTED }
                mac.runner.close()
                assertEquals(emptyList(), served.violations)
                // Every claim the older service refused, and the one that ran, used the journaled id; only looks after it are fresh.
                fun ids(calls: List<Pair<String, JsonObject?>>) = calls.filter { it.first == "conversationClaim" }.map { it.second!!.getValue("claimId").jsonPrimitive.content }
                assertEquals(listOf(journaled.claimId), ids(served.refused).distinct(), "no new claim id while the service was older")
                assertEquals(journaled.claimId, ids(served.seen).first(), "the claim that ran")
                assertEquals(emptyList(), mac.store.pendingClaims())
            }
        }
    }

    @Test fun aClaimAnswerThisBuildCannotReadNeitherCrashesNorSpins() {
        val readable = AtomicBoolean()
        val clock = AtomicLong()
        Served("hostEvent.work.available") { _, _ ->
            Answer.Data(if (readable.get()) value("claim.empty") else buildJsonObject { put("status", "stop-later") })
        }.use { served ->
            Mac(served, clock::get).use { mac ->
                mac.runner.start()
                eventually { served.claims.get() == 1 }
                Thread.sleep(300)
                assertEquals(1, served.claims.get(), "no claim loop while its wait lasts")
                val claimId = mac.store.pendingClaims().single().claimId
                assertEquals(claimId, served.bodies("conversationClaim").single().getValue("claimId").jsonPrimitive.content, "the claim id stays journaled")
                assertFalse(ConversationRunner.UPDATE_REQUIRED in mac.statuses, "an unreadable answer is not a contract answer")
                // Once the server's answer reads, the same claim id is asked again after its wait, and the lane carries on.
                readable.set(true)
                clock.addAndGet(TimeUnit.MINUTES.toNanos(1))
                mac.runner.beat()
                eventually { mac.store.pendingClaims().isEmpty() }
                assertEquals(listOf(claimId, claimId), served.bodies("conversationClaim").map { it.getValue("claimId").jsonPrimitive.content })
                assertEquals(emptyList(), served.violations)
                assertEquals(emptyList(), mac.chat.requests)
            }
        }
    }
}
