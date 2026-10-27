package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.CodexCliProvider
import org.formulagen.wrapper.companion.ExecutionJournal
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class EmberPetTest {
    private val taskId = UUID.randomUUID().toString()

    private fun snapshot(
        online: Boolean = true,
        provider: String? = "ready",
        tasks: List<EmberTaskProgress> = emptyList(),
    ) = EmberCompanionSnapshot(online, provider, tasks)

    @Test fun activeTaskStatesMapToRunning() {
        for (state in listOf("queued", "claimed", "running", "cloud-accepted")) {
            assertEquals(EmberView("running"), emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, state)))))
        }
    }

    @Test fun succeededBecomesReadyThenASayOfTheResult() {
        val view = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "succeeded", "Inspected the module."))))
        assertEquals("ready", view.state)
        assertEquals(EmberSay("Inspected the module.", taskId, null), view.say)
    }

    @Test fun failedAndReconciliationBecomeBlockedWithAShortSay() {
        val failed = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "failed", "sandbox refused the read"))))
        assertEquals("blocked", failed.state)
        assertEquals("sandbox refused the read", failed.say?.text)
        assertEquals(taskId, failed.say?.taskId)
        val uncertain = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "reconciliation-required", "Inspect this computer"))))
        assertEquals("blocked", uncertain.state)
        assertEquals("Inspect this computer", uncertain.say?.text)
    }

    @Test fun missingFailureTextStillSaysSomethingShort() {
        val failed = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "failed", null))))
        assertEquals("blocked", failed.state)
        assertEquals("Codex task failed.", failed.say?.text)
        assertNull(failed.say?.full)
        val uncertain = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "reconciliation-required", "  "))))
        assertEquals("This task needs reconciliation on this computer.", uncertain.say?.text)
    }

    @Test fun blankSuccessStillAnnouncesCompletion() {
        val view = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "succeeded", "  "))))
        assertEquals("ready", view.state)
        assertEquals("Codex finished.", view.say?.text)
        assertNull(view.say?.full)
    }

    @Test fun authRequiredIsNeedsInputWhenNothingIsActive() {
        assertEquals(EmberView("needs_input"), emberView(snapshot(provider = "auth-required")))
    }

    @Test fun unavailableOrOfflineIsBlockedWithoutASay() {
        assertEquals(EmberView("blocked"), emberView(snapshot(provider = "unavailable")))
        assertEquals(EmberView("blocked"), emberView(snapshot(online = false, provider = "ready", tasks = listOf(EmberTaskProgress(taskId, "running")))))
    }

    @Test fun nothingActiveIsIdle() {
        assertEquals(EmberView("none"), emberView(snapshot(provider = "ready")))
        assertEquals(EmberView("none"), emberView(snapshot(provider = null)))
    }

    @Test fun anActiveTaskOutranksLoginAndOlderOutcomes() {
        val tasks = listOf(
            EmberTaskProgress(taskId, "succeeded", "earlier"),
            EmberTaskProgress(UUID.randomUUID().toString(), "queued"),
        )
        assertEquals(EmberView("running"), emberView(snapshot(provider = "auth-required", tasks = tasks)))
    }

    @Test fun aFailureOutranksALaterSuccessAndLogin() {
        val failure = EmberTaskProgress(taskId, "failed", "disk full")
        val success = EmberTaskProgress(UUID.randomUUID().toString(), "succeeded", "ok")
        val view = emberView(snapshot(provider = "auth-required", tasks = listOf(success, failure)))
        assertEquals("blocked", view.state)
        assertEquals(taskId, view.say?.taskId)
        assertEquals("disk full", view.say?.text)
    }

    @Test fun successOutranksIdleLogin() {
        val view = emberView(snapshot(provider = "auth-required", tasks = listOf(EmberTaskProgress(taskId, "succeeded", "done"))))
        assertEquals("ready", view.state)
        assertEquals("done", view.say?.text)
    }

    @Test fun sayTextStaysWithin240CharactersAndKeepsTheFullResult() {
        val exact = "a".repeat(EMBER_SAY_LIMIT)
        assertEquals(EmberSummary(exact, null), emberSummary(exact))

        val unbroken = "b".repeat(EMBER_SAY_LIMIT + 1)
        val cut = emberSummary(unbroken)
        assertEquals(EMBER_SAY_LIMIT, cut.text.length)
        assertTrue(cut.text.endsWith("…"))
        assertEquals(unbroken, cut.full)

        val paragraph = "Ports are closed.\n\n" + "detail ".repeat(80)
        val summarized = emberSummary(paragraph)
        assertEquals("Ports are closed.", summarized.text)
        assertEquals(paragraph.trim(), summarized.full)

        val words = "word ".repeat(60).trimEnd()
        val wrapped = emberSummary(words)
        assertTrue(wrapped.text.length <= EMBER_SAY_LIMIT)
        assertTrue(wrapped.text.endsWith("…"))
        assertFalse(wrapped.text.dropLast(1).trimEnd().endsWith("wor"))
        assertEquals(words, wrapped.full)

        assertEquals(EmberSummary("one\ntwo", null), emberSummary("  one\r\ntwo\r"))
    }

    @Test fun longCodexResultIsAShortSayWithTheFullTextBesideIt() {
        val result = "Answer.\n\n" + "x".repeat(400)
        val view = emberView(snapshot(tasks = listOf(EmberTaskProgress(taskId, "succeeded", result))))
        assertEquals("ready", view.state)
        assertEquals("Answer.", view.say?.text)
        assertTrue((view.say?.text?.length ?: 0) <= EMBER_SAY_LIMIT)
        assertEquals(result, view.say?.full)
        val encoded = Json.parseToJsonElement(emberSayJson(view.say!!)).jsonObject
        assertEquals("say", encoded.getValue("type").jsonPrimitive.content)
        assertEquals("Answer.", encoded.getValue("text").jsonPrimitive.content)
        assertEquals(taskId, encoded.getValue("taskId").jsonPrimitive.content)
        assertEquals(result, encoded.getValue("full").jsonPrimitive.content)
        assertFalse(emberSayJson(EmberSay("short", null, null)).contains("full"))
        assertFalse(emberSayJson(EmberSay("short", null, null)).contains("taskId"))
    }

    @Test fun tokenMustBeTheOnlyQueryParameter() {
        val token = "ab".repeat(32)
        assertTrue(emberTokenAccepted("/?token=$token", token))
        assertFalse(emberTokenAccepted("/?token=wrong", token))
        assertFalse(emberTokenAccepted("/", token))
        assertFalse(emberTokenAccepted("/?token=", token))
        assertFalse(emberTokenAccepted("/?token=$token&x=1", token))
        assertFalse(emberTokenAccepted("/other?token=$token", token))
        assertFalse(emberTokenAccepted("/?token=$token&token=$token", token))
        assertFalse(emberTokenAccepted("/?Token=$token", token))
        assertFalse(emberTokenAccepted("", token))
    }

    @Test fun petMessageIsAcknowledgedUnsupportedAndDoesNotCreateATask() {
        val id = UUID.randomUUID().toString()
        val seen = mutableListOf<Pair<String, String>>()
        val sink = EmberMessageSink { messageId, text ->
            seen += messageId to text
            UnsupportedEmberMessageSink().onMessage(messageId, text)
        }
        val reply = emberHandleClientMessage("""{"type":"message","id":"$id","text":"ship the notes"}""", sink) { error("must not log") }
        assertNotNull(reply)
        val ack = Json.parseToJsonElement(reply).jsonObject
        assertEquals("message_ack", ack.getValue("type").jsonPrimitive.content)
        assertEquals(id, ack.getValue("id").jsonPrimitive.content)
        assertEquals("unsupported", ack.getValue("status").jsonPrimitive.content)
        assertEquals(EMBER_UNSUPPORTED_REASON, ack.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf(id to "ship the notes"), seen)
        val direct = UnsupportedEmberMessageSink().onMessage(id, "ship the notes")
        assertEquals(EmberMessageAck(id, "unsupported", EMBER_UNSUPPORTED_REASON), direct)
    }

    @Test fun unknownPetMessagesAreIgnoredAndMalformedJsonIsLoggedWithoutThePayload() {
        val sink = EmberMessageSink { _, _ -> error("sink") }
        val log = mutableListOf<String>()
        val secret = "sk-live-should-not-be-logged"
        assertNull(emberHandleClientMessage("""{"type":"hello","pet":"ember"}""", sink) { log += it })
        assertNull(emberHandleClientMessage("""{"type":"dance"}""", sink) { log += it })
        assertNull(emberHandleClientMessage("{$secret", sink) { log += it })
        assertNull(emberHandleClientMessage("""{"type":"message","id":"not-a-uuid","text":"x"}""", sink) { log += it })
        assertTrue(log.isNotEmpty())
        assertTrue(log.all { secret !in it && "not-a-uuid" !in it && "dance" !in it })
    }

    @Test fun heartbeatTasksKeepResultAndErrorText() {
        val failed = UUID.randomUUID().toString()
        val data = buildJsonObject {
            putJsonArray("tasks") {
                add(buildJsonObject {
                    put("request", buildJsonObject { put("id", taskId) })
                    put("progress", buildJsonObject { put("state", "succeeded"); put("result", "All green") })
                })
                add(buildJsonObject {
                    put("request", buildJsonObject { put("id", failed) })
                    put("progress", buildJsonObject { put("state", "failed"); put("error", "exit 4") })
                })
                add(buildJsonObject {
                    put("request", buildJsonObject { put("id", UUID.randomUUID().toString()) })
                    put("progress", buildJsonObject { put("state", "not-a-state") })
                })
            }
        }
        assertEquals(
            listOf(EmberTaskProgress(taskId, "succeeded", "All green"), EmberTaskProgress(failed, "failed", "exit 4")),
            emberTasksFromHeartbeat(data),
        )
        assertEquals(emptyList(), emberTasksFromHeartbeat(buildJsonObject {}))
    }

    @Test fun aStaleHeartbeatCannotEraseALocalOutcome() {
        val local = EmberTaskProgress(taskId, "succeeded", "full result " + "y".repeat(300))
        val stale = mergeEmberTasks(listOf(EmberTaskProgress(taskId, "running", null)), local, heartbeatStale = true)
        assertEquals(listOf(local), stale.tasks)
        assertEquals(local, stale.sticky)
        val agreed = mergeEmberTasks(listOf(EmberTaskProgress(taskId, "succeeded", "full")), local, heartbeatStale = false)
        assertEquals(local.text, agreed.tasks.single().text)
        assertNull(agreed.sticky)
        val dropped = mergeEmberTasks(emptyList(), local, heartbeatStale = false)
        assertEquals(emptyList(), dropped.tasks)
        assertNull(dropped.sticky)
        val raced = mergeEmberTasks(emptyList(), local, heartbeatStale = true)
        assertEquals(listOf(local), raced.tasks)
        assertEquals(local, raced.sticky)
    }

    @Test fun thePetProcessLosesApiKeysAndGainsOnlyTheBridgeUrl() {
        val url = "ws://127.0.0.1:9/?token=" + "cd".repeat(32)
        val env = mutableMapOf(
            "PATH" to "/usr/bin",
            "OPENAI_API_KEY" to "sk-test",
            "OPENAI_BASE_URL" to "https://example.test",
            "CODEX_API_KEY" to "sk-codex",
            "HOME" to "/Users/me",
        )
        applyEmberPetEnvironment(env, url)
        assertEquals("/usr/bin", env["PATH"])
        assertEquals("/Users/me", env["HOME"])
        assertEquals(url, env["EMBER_PET_WS"])
        assertTrue(env.keys.none { it.startsWith("OPENAI_") || it == "CODEX_API_KEY" })
        assertFalse(env.values.any { it.contains("sk-") })
    }

    @Test fun theSocketAcceptsOnlyItsTokenAndSpeaksTheCurrentOutcome() {
        val directory = Files.createTempDirectory("ember-bridge")
        val bridge = EmberPetBridge(directory)
        try {
            bridge.start()
            val file = directory.resolve(EMBER_DISCOVERY_NAME)
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file),
            )
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(directory),
            )
            val discovery = Json.parseToJsonElement(Files.readString(file)).jsonObject
            assertEquals(setOf("url", "pid", "version"), discovery.keys)
            assertEquals(1, discovery.getValue("version").jsonPrimitive.int)
            assertEquals(ProcessHandle.current().pid(), discovery.getValue("pid").jsonPrimitive.long)
            val url = discovery.getValue("url").jsonPrimitive.content
            assertTrue(url.startsWith("ws://127.0.0.1:"))
            assertTrue(url.contains("?token="))
            assertFalse(url.contains("OPENAI") || url.contains("sk-"))
            assertLoopback(URI(url).port)
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            assertFails {
                client.newWebSocketBuilder().buildAsync(URI(url.substringBefore("?") + "?token=nope"), CollectingPet()).join()
            }
            val pet = CollectingPet()
            val socket = client.newWebSocketBuilder().buildAsync(URI(url), pet).join()
            waitFor { pet.messages.any { it.contains("\"state\":\"none\"") } }
            val result = "Answer.\n\n" + "x".repeat(400)
            val task = UUID.randomUUID().toString()
            bridge.update(EmberCompanionSnapshot(true, "ready", listOf(EmberTaskProgress(task, "succeeded", result))))
            waitFor { pet.messages.any { it.contains("\"type\":\"say\"") } }
            val say = Json.parseToJsonElement(pet.messages.first { it.contains("\"type\":\"say\"") }).jsonObject
            assertEquals("Answer.", say.getValue("text").jsonPrimitive.content)
            assertEquals(result, say.getValue("full").jsonPrimitive.content)
            assertTrue(say.getValue("text").jsonPrimitive.content.length <= EMBER_SAY_LIMIT)
            val readyAt = pet.messages.indexOfFirst { it.contains("\"state\":\"ready\"") }
            val sayAt = pet.messages.indexOfFirst { it.contains("\"type\":\"say\"") }
            assertTrue(readyAt >= 0 && sayAt > readyAt)
            val state = Json.parseToJsonElement(pet.messages.elementAt(readyAt)).jsonObject
            assertEquals("ready", state.getValue("state").jsonPrimitive.content)
            val late = CollectingPet()
            client.newWebSocketBuilder().buildAsync(URI(url), late).join()
            waitFor { late.messages.any { it.contains("\"state\":\"ready\"") } && late.messages.any { it.contains("\"type\":\"say\"") } }
            val id = UUID.randomUUID().toString()
            socket.sendText("""{"type":"message","id":"$id","text":"from the pet"}""", true).join()
            waitFor { pet.messages.any { it.contains("message_ack") } }
            val ack = Json.parseToJsonElement(pet.messages.first { it.contains("message_ack") }).jsonObject
            assertEquals(id, ack.getValue("id").jsonPrimitive.content)
            assertEquals("unsupported", ack.getValue("status").jsonPrimitive.content)
            assertEquals(EMBER_UNSUPPORTED_REASON, ack.getValue("reason").jsonPrimitive.content)
            socket.sendText("{", true).join()
            socket.sendText("""{"type":"hello","pet":"ember"}""", true).join()
            val second = UUID.randomUUID().toString()
            socket.sendText("""{"type":"message","id":"$second","text":"still here"}""", true).join()
            waitFor { pet.messages.any { it.contains(second) } }
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").join()
        } finally {
            bridge.close()
            assertFalse(Files.exists(directory.resolve(EMBER_DISCOVERY_NAME)))
        }
    }

    @Test fun closingDoesNotDeleteAnotherCompanionsDiscoveryFile() {
        val directory = Files.createTempDirectory("ember-foreign")
        val bridge = EmberPetBridge(directory)
        bridge.start()
        val file = directory.resolve(EMBER_DISCOVERY_NAME)
        val foreign = """{"url":"ws://127.0.0.1:1/?token=${"ff".repeat(32)}","pid":1,"version":1}"""
        Files.writeString(file, foreign)
        bridge.close()
        assertEquals(foreign, Files.readString(file))
    }

    @Test fun launchedPetSeesTheBridgeUrlAndNotApiKeys() {
        val directory = Files.createTempDirectory("ember-pet-proc")
        val envFile = directory.resolve("env")
        val argFile = directory.resolve("args")
        val script = directory.resolve("pet")
        Files.writeString(script, """
            #!/bin/sh
            printf '%s\n' "${'$'}0" "${'$'}@" > '${argFile.toAbsolutePath()}.tmp'
            mv '${argFile.toAbsolutePath()}.tmp' '${argFile.toAbsolutePath()}'
            env > '${envFile.toAbsolutePath()}.tmp'
            mv '${envFile.toAbsolutePath()}.tmp' '${envFile.toAbsolutePath()}'
            sleep 30
        """.trimIndent())
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        val url = "ws://127.0.0.1:9/?token=" + "ab".repeat(32)
        val process = startEmberPet(script, url, mutableMapOf(
            "PATH" to (System.getenv("PATH") ?: "/usr/bin"),
            "OPENAI_API_KEY" to "sk-test",
            "OPENAI_BASE_URL" to "https://example.test",
            "CODEX_API_KEY" to "sk-codex",
        ))
        try {
            waitFor { Files.exists(envFile) && Files.exists(argFile) }
            val env = Files.readString(envFile)
            val args = Files.readString(argFile)
            assertTrue(env.lines().any { it == "EMBER_PET_WS=$url" })
            assertFalse(env.contains("OPENAI_"))
            assertFalse(env.contains("CODEX_API_KEY"))
            assertFalse(env.contains("sk-"))
            assertFalse(args.contains("ws://") || args.contains("token="))
        } finally {
            process.destroyForcibly()
        }
    }

    @Test fun sessionPublishesTheCodexResultAndThenGoesOffline() {
        val directory = Files.createTempDirectory("ember-session")
        val executable = directory.resolve("codex")
        Files.writeString(executable, """
            #!/bin/sh
            if [ "${'$'}1" = login ]; then printf '%s\n' 'Logged in using ChatGPT'; exit 0; fi
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = --output-last-message ]; then shift; printf 'fixture result' > "${'$'}1"; fi
              shift
            done
            cat >/dev/null
            exit 0
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val host = UUID.randomUUID().toString()
        val taskId = UUID.randomUUID().toString()
        var revision = 0
        var current = sessionTask(host, taskId, buildJsonObject { put("state", "queued") }, revision)
        val transport = HovelTransport { route, body ->
            when (route) {
                "heartbeat" -> buildJsonObject { putJsonArray("tasks") { add(current) } }
                "claim" -> {
                    revision = 1
                    current = sessionTask(host, taskId, buildJsonObject {
                        put("state", "claimed"); put("claimId", body.getValue("claimId").jsonPrimitive.content)
                    }, revision)
                    current
                }
                "progress" -> {
                    revision += 1
                    current = sessionTask(host, taskId, body.getValue("progress").jsonObject, revision)
                    current
                }
                else -> error(route)
            }
        }
        val seen = CopyOnWriteArrayList<EmberCompanionSnapshot>()
        val session = HovelHostSession(
            host,
            ExecutionJournal(directory.resolve("journal"), host),
            CodexCliProvider(executable, directory, directory.resolve("output")),
            transport,
            {},
            { seen.add(it) },
        )
        try {
            session.start()
            waitFor(8_000) {
                seen.any { snap -> snap.tasks.any { it.id == taskId && it.state == "succeeded" && it.text == "fixture result" } }
            }
        } finally {
            session.close()
        }
        assertFalse(seen.last().online)
        assertEquals("blocked", emberView(seen.last()).state)
    }

    @Test fun sessionReportsAuthRequiredWhenCodexIsNotSignedIn() {
        val directory = Files.createTempDirectory("ember-auth")
        val executable = directory.resolve("codex")
        Files.writeString(executable, "#!/bin/sh\nexit 1\n")
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        val seen = CopyOnWriteArrayList<EmberCompanionSnapshot>()
        val host = UUID.randomUUID().toString()
        val session = HovelHostSession(
            host,
            ExecutionJournal(directory.resolve("journal"), host),
            CodexCliProvider(executable, directory, directory.resolve("output")),
            { _, _ -> buildJsonObject { put("tasks", JsonArray(emptyList())) } },
            {},
            { seen.add(it) },
        )
        try {
            session.start()
            waitFor { seen.any { it.providerState == "auth-required" && it.tasks.isEmpty() } }
            assertEquals("needs_input", emberView(seen.last { it.online }).state)
        } finally {
            session.close()
        }
    }

    private fun sessionTask(host: String, taskId: String, progress: JsonObject, revision: Int) = buildJsonObject {
        put("ownerId", UUID.randomUUID().toString())
        put("characterMemory", "Ada")
        put("revision", revision)
        put("progress", progress)
        put("request", buildJsonObject {
            put("id", taskId); put("hostId", host); put("mode", "local"); put("prompt", "Inspect")
        })
    }

    private fun assertLoopback(port: Int) {
        val portHex = "%04X".format(port)
        // A dual-stack JDK records an IPv4 loopback listen under /proc/net/tcp6 as ::ffff:127.0.0.1.
        val listening = listOf("/proc/net/tcp", "/proc/net/tcp6").any { table ->
            Files.readAllLines(Path.of(table)).any { line ->
                val local = line.trim().split(Regex("\\s+")).getOrNull(1)?.split(":") ?: return@any false
                local.size == 2 && local[1].equals(portHex, true) &&
                    (local[0].equals("0100007F", true) || local[0].uppercase().endsWith("0100007F"))
            }
        }
        assertTrue(listening)
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(condition())
    }

    private class CollectingPet : WebSocket.Listener {
        val messages = ConcurrentLinkedQueue<String>()
        private val buffer = StringBuilder()
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }
        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            buffer.append(data)
            if (last) {
                messages.add(buffer.toString())
                buffer.clear()
            }
            webSocket.request(1)
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }
    }
}
