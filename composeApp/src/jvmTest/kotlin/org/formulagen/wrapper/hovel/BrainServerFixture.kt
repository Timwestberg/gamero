package org.formulagen.wrapper.hovel

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import kotlin.test.*
import org.formulagen.wrapper.companion.*

/** Opt-in cross-repository fixture entrypoint. The only executable is the shell stub created below. */
object BrainServerFixture {
    @JvmStatic fun main(args: Array<String>) {
        val manifest = Json.parseToJsonElement(Files.readString(Path.of(args.single()))).jsonObject
        val directory = Files.createTempDirectory("brain-server-native-")
        try {
            val host = manifest.getValue("hostId").jsonPrimitive.content
            val owner = manifest.getValue("ownerId").jsonPrimitive.content
            val task = manifest.getValue("taskId").jsonPrimitive.content
            val base = URI(manifest.getValue("baseUrl").jsonPrimitive.content)
            val token = "b".repeat(64) // Existing test-only credential seeded by the server fixture.
            val http = HovelHttpTransport(base, owner, host, token, brainEnabled = true)
            assertFails { BrainHovelRemote(HovelHttpTransport(base, owner, host, token)).work() }
            assertFails { BrainHovelRemote(HovelHttpTransport(base, owner, host, "c".repeat(64), brainEnabled = true)).work() }
            assertFails { BrainHovelRemote(HovelHttpTransport(base, manifest.getValue("otherOwnerId").jsonPrimitive.content,
                host, token, brainEnabled = true)).work() }
            val executable = directory.resolve("fixture-codex")
            Files.writeString(executable, """
                #!/bin/sh
                if [ "${'$'}1" = login ]; then echo 'Logged in using ChatGPT'; exit 0; fi
                cat > input.txt
                printf 'launch\n' >> calls.txt
                while [ "${'$'}#" -gt 0 ]; do
                  if [ "${'$'}1" = --output-last-message ]; then
                    shift
                    if grep -q 'BEGIN SAVED OUTPUT' input.txt; then printf 'Fixture character reply' > "${'$'}1";
                    else printf 'PRIVATE FIXTURE EXECUTOR OUTPUT' > "${'$'}1"; fi
                  fi
                  shift
                done
            """.trimIndent())
            Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
            val provider = CodexCliProvider(executable, directory, directory.resolve("output"))
            fun runSession(transport: HovelTransport, finished: CountDownLatch, expectReconciliation: Boolean = false) {
                val unexpected = java.util.concurrent.atomic.AtomicReference<String?>()
                val execution = ExecutionJournal(directory.resolve("execution"), host)
                val local = manifest.getValue("local").jsonObject
                // This test-only source stands in for an approved native adapter, never a production server pin.
                val source = BrainLocalAccountSource { BrainLocalObservation(
                    local.getValue("localAccountRef").jsonPrimitive.content,
                    local.getValue("accountGeneration").jsonPrimitive.long, 2, true,
                    manifest.getValue("observedAtMillis").jsonPrimitive.long,
                ) }
                val brain = BrainSessionOptions(enabled = true, accountSource = source)
                    .attach(host, directory, execution, provider, transport)
                HovelHostSession(host, execution, provider, transport, brain) { message ->
                    if (message.startsWith("Task needs reconciliation")) {
                        if (!expectReconciliation) unexpected.set(message)
                        finished.countDown()
                    }
                }.use { session ->
                    session.start()
                    assertTrue(finished.await(20, TimeUnit.SECONDS), "Session did not finish its fixture exchange")
                }
                assertNull(unexpected.get(), "Unexpected reconciliation instead of successful fixture exchange")
            }
            // The server commits execution, then the client loses the acknowledgement before presentation.
            val lost = CountDownLatch(1)
            val droppedOutcome = java.util.concurrent.atomic.AtomicBoolean(false)
            runSession(HovelTransport { route, body ->
                val response = try { http.post(route, body) } catch (error: Exception) { System.err.println("Fixture route failed: $route"); error.printStackTrace(); throw error }
                if (route == "brain/outcome") {
                    droppedOutcome.set(true)
                    error("Fixture acknowledgement lost after server commit")
                }
                response
            }, lost, expectReconciliation = true)
            assertTrue(droppedOutcome.get(), "Execution must commit before acknowledgement is lost")
            assertEquals(1, Files.readAllLines(directory.resolve("calls.txt")).size)
            val completed = CountDownLatch(1)
            runSession(HovelTransport { route, body ->
                val response = try { http.post(route, body) } catch (error: Exception) { System.err.println("Fixture route failed: $route"); error.printStackTrace(); throw error }
                if (route == "brain/presentation") completed.countDown()
                response
            }, completed)
            assertEquals(2, Files.readAllLines(directory.resolve("calls.txt")).size)
            val reconnected = CountDownLatch(1)
            runSession(HovelTransport { route, body ->
                val response = try { http.post(route, body) } catch (error: Exception) { System.err.println("Fixture route failed: $route"); error.printStackTrace(); throw error }
                if (route == "brain/work") {
                    assertEquals(0, response.getValue("assignments").jsonArray.size)
                    reconnected.countDown()
                }
                response
            }, reconnected)
            assertEquals(2, Files.readAllLines(directory.resolve("calls.txt")).size)
            ExecutionJournal(directory.resolve("execution"), host).use {
                assertEquals(ExecutionPhase.SUCCEEDED, it.read(task)!!.phase)
                assertEquals("PRIVATE FIXTURE EXECUTOR OUTPUT", it.read(task)!!.result)
            }
            println("Native/server fixture passed: one executor launch, one presentation launch, durable reconnect")
        } finally { directory.toFile().deleteRecursively() }
    }
}
