package org.formulagen.wrapper.conversations

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.hovel.HovelHttpTransport
import org.formulagen.wrapper.hovel.hovelHostFeatures
import org.formulagen.wrapper.workspace.FolderPicker

/**
 * The whole Mac stack (runner, engine, transport) against hovel-service's own native route table and payloads, as
 * `CompanionContractFixtures` carries them from server-platform: the server answers only the routes in that table,
 * with payloads its zod schemas accepted, and every body the Mac sends must have the shape of a payload the same
 * schemas accepted. A drift on either side fails here before it fails against a running server.
 */
class ServerContractRunTest {
    private val fixtures = CompanionContractFixtures.PAYLOADS.mapValues { Json.parseToJsonElement(it.value).jsonObject }

    private fun value(name: String) = fixtures.getValue(name).getValue("value").jsonObject

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

    @Test fun aMessageRunsEndToEndOnTheServersRoutesAndShapes() {
        val owner = id()
        val host = value("claim.claimed").getValue("claim").jsonObject.getValue("project").jsonObject.getValue("computerId").jsonPrimitive.content
        val token = "c".repeat(64)
        val input = value("checkpoint.textArtifact").getValue("checkpoint").jsonObject.getValue("artifact").jsonObject
        val claimed = value("claim.claimed").getValue("claim").jsonObject
        val message = claimed.getValue("message").jsonObject
        val conversationId = claimed.getValue("conversation").jsonObject.getValue("id").jsonPrimitive.content
        // The served routes, from the server's own table: method, then the path as a pattern.
        val routes = value("routes.native").map { (name, route) ->
            val path = route.jsonObject.getValue("path").jsonPrimitive.content.removePrefix("/")
            Triple(name, route.jsonObject.getValue("method").jsonPrimitive.content, Regex(path.replace(Regex(":[A-Za-z]+"), "[0-9a-f-]{36}")))
        }
        val seen = CopyOnWriteArrayList<Pair<String, JsonObject?>>()
        val violations = CopyOnWriteArrayList<String>()
        val claims = AtomicInteger()
        val done = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/native/$owner/$host/")
            val route = routes.firstOrNull { it.second == exchange.requestMethod && it.third.matches(path) }?.first
            val text = exchange.requestBody.bufferedReader().readText()
            val body = text.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject }
            if (exchange.requestHeaders.getFirst("X-Hovel-Host-Token") != token ||
                exchange.requestHeaders.getFirst(HovelContract.HEADER) != HovelContract.CURRENT.toString()) violations += "headers on $path"
            if (route == null) {
                violations += "${exchange.requestMethod} $path is not a served route"
                exchange.sendResponseHeaders(404, -1); exchange.close(); return@createContext
            }
            seen += route to body
            if (route == "stream") {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                // The server's SSE hub frame: its event name and envelope, with a hostStreamEvent as the payload.
                val payload = value("hostEvent.work.available")
                val envelope = buildJsonObject {
                    put("occurredAt", AT); put("payload", payload); put("sequence", "1"); put("type", "hovel.host.notified"); put("version", 1)
                }
                runCatching {
                    exchange.responseBody.write("id: 1\nevent: hovel.host.notified\ndata: $envelope\n\n".toByteArray())
                    exchange.responseBody.flush()
                    done.await(10, TimeUnit.SECONDS)
                }
                exchange.close()
                return@createContext
            }
            // Its top-level fields are those of an accepted payload, and it sends no field the accepted payloads lack.
            fun expect(schema: String, kind: String? = null) {
                val shapes = accepted(schema, kind)
                val sent = body?.let(::shape).orEmpty()
                fun top(paths: Set<String>) = paths.filterTo(HashSet()) { it.count { c -> c == '.' } == 1 }
                val fits = body != null && shapes.any { top(it) == top(sent) } && sent.all { path -> shapes.any { path in it } }
                if (!fits) violations += "$route sent a body no accepted $schema ${kind.orEmpty()} has: $body"
            }
            val data: JsonElement = when (route) {
                "heartbeat" -> { expect("heartbeatInput"); buildJsonObject {} }
                "conversationClaim" -> {
                    expect("claimRequest")
                    if (claims.getAndIncrement() > 0) value("claim.empty") else buildJsonObject {
                        put("status", "claimed")
                        put("claim", JsonObject(claimed + mapOf(
                            "claimId" to body!!.getValue("claimId"),
                            "message" to JsonObject(message + ("inputs" to JsonArray(listOf(input))))
                        )))
                    }
                }
                "messageProgress" -> {
                    expect("messageProgress")
                    JsonObject(message + mapOf("state" to body!!.getValue("state"),
                        "revision" to JsonPrimitive(body.getValue("expectedRevision").jsonPrimitive.long + 1)))
                }
                "messageCheckpoint" -> {
                    val kind = body?.get("checkpoint")?.jsonObject?.get("kind")?.jsonPrimitive?.content
                    expect("messageCheckpointRequest", kind)
                    buildJsonObject {}
                }
                "messageDelta" -> { expect("replyDeltaRequest"); buildJsonObject { put("relayed", true) } }
                "artifactText" -> if (exchange.requestURI.query == "cursor=0") value("artifactText.page") else value("artifactText.last")
                "projectRead" -> value("project")
                "projectFolders" -> { expect("projectFoldersSave"); value("project") }
                "approvalRead" -> value("approval.answeredByPhone")
                else -> { violations += "$route is not one the Mac should call here"; buildJsonObject {} }
            }
            val bytes = buildJsonObject { put("data", data) }.toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val root = folder("server-contract")
        val account = id()
        val store = ConversationStore(root, account).open()
        try {
            val transport = HovelHttpTransport(URI("http://127.0.0.1:${server.address.port}"), owner, host, token)
            val api = HttpConversationApi(transport, transport::openStream)
            val dialogs = NativeDialogs(Screen())
            val approvals = RemoteApprovals(dialogs, api, host)
            val chat = ScriptedRuntime(RuntimeId.CODEX) { "{\"kind\":\"answer\"}\nSign-in is on its way." }
            val turns = ProviderTurns(listOf(chat), emptyList(), ConnectedProviders(), approvals)
            val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
            val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(dialogs), api)
            val directory = HostDirectory(ConnectedProviders(), account, host, setOf(RuntimeId.CODEX), emptySet(),
                hovelHostFeatures(tasks = true, memory = true), taskProviderState = { "ready" })
            ConversationRunner(api, store, engine, approvals, folders, ProjectFolderPublisher(folders, api, account, host) { emptyList() },
                directory::heartbeat).use { runner ->
                runner.start()
                eventually("the message settled and the lane looked again") {
                    claims.get() >= 2 && seen.any { (route, body) -> route == "messageProgress" && body?.get("state")?.jsonPrimitive?.content == "answered" }
                }
            }
            assertEquals(emptyList(), violations)
            val called = seen.map { it.first }.toSet()
            assertTrue(called.containsAll(setOf("heartbeat", "stream", "conversationClaim", "messageProgress", "messageCheckpoint",
                "messageDelta", "artifactText", "projectFolders")), "routes called: $called")
            val reply = seen.mapNotNull { (route, body) -> body?.takeIf { route == "messageCheckpoint" }?.getValue("checkpoint")?.jsonObject }
                .last { it.getValue("kind").jsonPrimitive.content == "message.appended" }
            assertEquals("Sign-in is on its way.", reply.getValue("message").jsonObject.getValue("text").jsonPrimitive.content)
            assertEquals(conversationId, value("hostEvent.work.available").getValue("conversationId").jsonPrimitive.content)
            val stored = store.read(conversationId).brain.run!!.artifacts.single { it.id == input.getValue("id").jsonPrimitive.content }
            assertEquals("Sign-in added.", store.bytes(conversationId, stored).decodeToString(), "the input's whole text, read in two pages")
        } finally {
            done.countDown()
            store.close()
            server.stop(0)
        }
    }
}
