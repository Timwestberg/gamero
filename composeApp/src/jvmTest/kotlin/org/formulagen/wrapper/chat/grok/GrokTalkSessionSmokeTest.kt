package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GROK_ACCOUNT_METHODS
import org.formulagen.wrapper.providers.grok.GrokAcp
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.GrokLaunch
import org.formulagen.wrapper.providers.grok.GrokRequestRejected
import org.formulagen.wrapper.providers.grok.GrokSmoke
import org.formulagen.wrapper.providers.grok.grokHandshake
import org.formulagen.wrapper.providers.grok.grokInitialize

/** Explicit account opt-in ([GrokSmoke]): no prompts unless model turns are also allowed. */
class GrokTalkSessionSmokeTest {
    @Test fun installedRuntimeExcludesSkillsOnDiskAndRejectsEmptyStoreWithoutInteractiveAuth() {
        val executable = GrokSmoke.executable()
        val root = Files.createTempDirectory("grok-empty-login-smoke")
        val launch = GrokLaunch.create(executable, GrokSmoke.environment(executable) +
            ("GROK_AUTH_PATH" to root.resolve("missing-auth.json").toString()), "Control check")
        var rpc: GrokAcp? = null
        try {
            val home = Path.of(launch.agentBuilder().environment().getValue("GROK_HOME"))
            val skill = Files.createDirectories(home.resolve("skills/hovel-isolation-fixture"))
            Files.writeString(skill.resolve("SKILL.md"), "---\nname: hovel-isolation-fixture\ndescription: Must stay excluded\n---\nUnexpected instructions")
            launch.verify(10.seconds)
            val transport = GrokAcp(launch.agentBuilder(), GROK_ACCOUNT_METHODS, allowReloadResponses = true)
            rpc = transport
            assertEquals(GrokAuthAvailability.SIGNED_OUT, grokHandshake(transport.call("initialize", grokInitialize())).auth)
            val error = assertFailsWith<GrokRequestRejected> {
                transport.call("authenticate", buildJsonObject { put("methodId", "cached_token"); putJsonObject("_meta") { put("headless", true) } })
            }
            assertEquals(-32000, error.code)
            println("GROK_CONTROL_SMOKE: on-disk skill excluded; empty credential store rejected without client requests")
        } finally { launch.closeAfter(rpc); root.toFile().deleteRecursively() }
    }

    @Test fun installedRuntimeEstablishesAnAuthenticatedToolFreeSession() {
        val executable = GrokSmoke.executable()
        val updates = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        GrokTalkSession.open(executable, GrokSmoke.environment(executable),
            "You are the Hovel integration test companion. Answer concisely.", onUpdate = { updates += it }).use { session ->
            assertTrue(session.account.models.isNotEmpty())
            assertTrue(updates.any { it["update"]?.jsonObject?.get("sessionUpdate") == JsonPrimitive("available_commands_update") })
            if (GrokSmoke.turns) {
                val outcome = session.prompt("Reply with exactly HOVEL_GROK_OK.", 60.seconds)
                var state = GrokTurnState(session.sessionId)
                updates.forEach { state = state.update(it) }
                state = state.finish(outcome)
                assertEquals(GrokTurnEnd.COMPLETE, state.end)
                assertEquals("HOVEL_GROK_OK", state.text.trim())
                println("GROK_TALK_SMOKE: authenticated, zero tools, one successful reply")
            } else println("GROK_TALK_SMOKE: authenticated, zero tools, no prompt sent")
        }
    }

    @Test fun installedRuntimeReloadsPrivateConversationAndCancelsAnActivePrompt() {
        val executable = GrokSmoke.turnsExecutable()
        val environment = GrokSmoke.environment(executable)
        val folder = Files.createTempDirectory("grok-resume-smoke")
        val updates = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
        val instructions = "You are the Hovel integration test companion. Follow short exact-output requests."
        try {
            val saved = GrokTalkSession.open(executable, environment, instructions,
                persistentFolder = folder, onUpdate = { updates += it }).use { session ->
                assertEquals(JsonPrimitive("end_turn"), session.prompt("Remember HOVEL_ORCHID. Reply exactly SAVED.")["stopReason"])
                Triple(session.sessionId, session.account.fingerprint, session.model)
            }
            GrokTalkSession.open(executable, environment, instructions, saved.third, persistentFolder = folder,
                resume = saved.first, expectedAccount = saved.second, onUpdate = { updates += it }).use { session ->
                updates.clear()
                val outcome = session.prompt("What word did I ask you to remember? Reply with that word only.")
                var state = GrokTurnState(session.sessionId)
                updates.forEach { state = state.update(it) }
                state = state.finish(outcome)
                assertEquals(GrokTurnEnd.COMPLETE, state.end)
                assertEquals("HOVEL_ORCHID", state.text.trim())
            }
            val active = java.util.concurrent.CountDownLatch(1)
            GrokTalkSession.open(executable, environment, instructions, onUpdate = { params ->
                if (params["update"]?.jsonObject?.get("sessionUpdate") in listOf(JsonPrimitive("agent_message_chunk"), JsonPrimitive("agent_thought_chunk")))
                    active.countDown()
            }).use { session ->
                val reply = java.util.concurrent.CompletableFuture.supplyAsync {
                    session.prompt("Print the integers from 1 to 10000, one per line, with no omissions.")
                }
                assertTrue(active.await(30, java.util.concurrent.TimeUnit.SECONDS), "No active generation observed")
                session.cancel()
                assertEquals(JsonPrimitive("cancelled"), reply.get(5, java.util.concurrent.TimeUnit.SECONDS)["stopReason"])
            }
            println("GROK_LIFECYCLE_SMOKE: private session reloaded, context retained, active prompt cancelled within five seconds")
        } finally { folder.toFile().deleteRecursively() }
    }
}
