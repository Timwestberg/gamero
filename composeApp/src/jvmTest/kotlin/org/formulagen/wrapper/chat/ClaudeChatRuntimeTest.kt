package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.FakeClaude
import org.formulagen.wrapper.providers.argv
import org.formulagen.wrapper.providers.env
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

/** [ClaudeChatRuntime] against [FakeClaude], which replays output recorded from the real Claude Code. */
class ClaudeChatRuntimeTest {
    private val persona = Persona.role("Ember", "Coordinator", "Help the user.", working = false)
    private val account = "50000000-0000-4000-8000-000000000005"

    private class Events : TurnEvents {
        val seen = CopyOnWriteArrayList<String>()
        override fun opened(conversation: String) { seen += "opened $conversation" }
        override fun accepted() { seen += "accepted" }
        override fun partial(text: String) { seen += "partial $text" }
        override fun completed(text: String, conversation: String) { seen += "completed $text @ $conversation" }
        override fun failed(failure: ChatFailure) { seen += "failed $failure" }
        fun ended() = seen.any { it.startsWith("completed") || it.startsWith("failed") }
    }

    private fun await(message: String = "Condition timed out", check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!check()) { if (System.nanoTime() > until) fail(message); Thread.sleep(10) }
    }

    /** The runtime with a sign-in reader that checks every time, since tests change the fake's answers. */
    private fun claude(fake: FakeClaude) = fake.runtime().let { ClaudeChatRuntime(it, ClaudeConnection(it, ttl = kotlin.time.Duration.ZERO)) }

    private fun request(conversation: NativeConversation? = null, model: String? = "haiku") =
        TurnRequest(account, "20000000-0000-4000-8000-000000000002", persona, model, conversation, "Hello")

    /** Run one turn to its end against [turn]. */
    private fun turn(fake: FakeClaude, turn: String, exit: Int = 0, request: TurnRequest = request()): Events {
        fake.scenario(turn = FakeClaude.fixture(turn), exit = exit)
        val events = Events()
        claude(fake).use { claude -> claude.startTurn(request, events); await { events.ended() } }
        return events
    }

    @Test fun aNewConversationStreamsItsReplyAndKeepsTheSession() {
        val fake = FakeClaude()
        val events = turn(fake, "turn-success.ndjson", request = request())
        val call = fake.turns().single()
        val argv = call.argv()
        val session = argv[argv.indexOf("--session-id") + 1]
        assertEquals(session, UUID.fromString(session).toString())
        assertEquals(claudeTurnArguments("haiku", persona) + listOf("--session-id", session), argv)
        assertFalse("--resume" in argv)
        assertEquals(listOf(
            "opened $session",
            "accepted",
            "partial You asked me",
            "partial You asked me to say \"hello,\" and my",
            "partial You asked me to say \"hello,\" and my name is Ember.",
            "completed You asked me to say \"hello,\" and my name is Ember. @ $session",
        ), events.seen)
        // One user message, then end of input.
        assertEquals(userMessage("Hello") + "\n", call.getValue("stdin").jsonPrimitive.content)
        val workspace = fake.providers.resolve("$account/claude-code/conversation-workspace")
        assertEquals(workspace.toRealPath().toString(), call.getValue("cwd").jsonPrimitive.content)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(workspace.parent)))
    }

    @Test fun anExistingConversationIsResumedWithTheTurnsModel() {
        val fake = FakeClaude()
        val session = "9d6b5d91-5085-4e72-a576-7d379498daf0"
        val events = turn(fake, "turn-success.ndjson", request = request(NativeConversation(session, persona.hash), model = "opus"))
        val argv = fake.turns().single().argv()
        assertEquals(claudeTurnArguments("opus", persona) + listOf("--resume", session), argv)
        assertFalse("--session-id" in argv)
        assertTrue(events.seen.none { it.startsWith("opened") })
        assertEquals("completed You asked me to say \"hello,\" and my name is Ember. @ $session", events.seen.last())
    }

    @Test fun onlyTheAllowlistedEnvironmentReachesClaudeCode() {
        val fake = FakeClaude()
        val events = turn(fake, "turn-success.ndjson")
        // The fake exits 97 without output if an ANTHROPIC_* or other CLAUDE_* variable reached it.
        assertTrue(events.seen.last().startsWith("completed"), events.seen.toString())
        val env = fake.turns().single().env()
        assertEquals("1", env["CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"]); assertEquals("1", env["CLAUDE_CODE_DISABLE_AUTO_MEMORY"])
        assertEquals("${fake.dir}:/usr/bin:/bin:/usr/sbin:/sbin", env["PATH"])
        assertEquals(System.getProperty("user.home"), env["HOME"]); assertEquals("player", env["USER"])
        assertTrue(env.keys.none { it.startsWith("ANTHROPIC") || it in setOf("CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CONFIG_DIR", "OPENAI_API_KEY", "HOVEL_SECRET") })
        assertEquals(setOf("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "CLAUDE_CODE_DISABLE_AUTO_MEMORY"), env.keys.filter { it.startsWith("CLAUDE") }.toSet())
    }

    @Test fun aTurnThatLoadsSkillsFailsClosedBeforeAnyText() {
        val fake = FakeClaude()
        val events = turn(fake, "turn-lockdown.ndjson")
        assertEquals(listOf("failed Lockdown"), events.seen)
        // A text delta before a verified init is refused too.
        val early = FakeClaude.fixture("turn-success.ndjson").lines().filter { it.isNotBlank() }.let { lines -> lines.drop(1) }.joinToString("\n")
        fake.scenario(turn = early)
        val refused = Events()
        claude(fake).use { it.startTurn(request(), refused); await { refused.ended() } }
        assertEquals(listOf("failed Lockdown"), refused.seen)
        // So is an init for another session than the one asked for.
        fake.scenario(turn = FakeClaude.fixture("turn-success.ndjson").replaceFirst("\"session_id\":\"SESSION_ID\"", "\"session_id\":\"${UUID.randomUUID()}\""))
        val other = Events()
        claude(fake).use { it.startTurn(request(), other); await { other.ended() } }
        assertEquals(listOf("failed Lockdown"), other.seen)
    }

    @Test fun eachErrorMapsToOneFailure() {
        val fake = FakeClaude()
        assertEquals(listOf("failed ModelUnavailable"), turn(fake, "turn-bad-model.ndjson", exit = 1).seen.filterNot { it.startsWith("opened") || it == "accepted" })
        assertEquals("failed SignedOut", turn(fake, "turn-authentication-failed.ndjson", exit = 1).seen.last())
        assertEquals("failed ${ChatFailure.UsageLimit(Instant.ofEpochSecond(1793158800))}", turn(fake, "turn-rate-limit.ndjson", exit = 1).seen.last())
        val lost = turn(fake, "turn-resume-unknown.ndjson", exit = 1, request = request(NativeConversation("ffac2249-90d9-4ca8-8855-cad61ba645ed", persona.hash)))
        assertEquals(listOf("failed ConversationLost"), lost.seen)
        // No synthetic error text is ever shown as the reply.
        assertTrue(turn(fake, "turn-bad-model.ndjson", exit = 1).seen.none { it.startsWith("partial") || it.startsWith("completed") })
        // A process that exits without a result is unexpected.
        fake.scenario(turn = "", exit = 3)
        val crashed = Events()
        claude(fake).use { it.startTurn(request(), crashed); await { crashed.ended() } }
        assertEquals(listOf("failed Unexpected"), crashed.seen)
    }

    @Test fun anotherPersonaOrAnInvalidModelNeverStartsAProcess() {
        val fake = FakeClaude()
        claude(fake).use { claude ->
            val renamed = Events()
            claude.startTurn(request(NativeConversation("9d6b5d91-5085-4e72-a576-7d379498daf0", Persona.role("Pip", "Coordinator", "Help the user.", working = false).hash)), renamed)
            assertEquals(listOf("failed ConversationLost"), renamed.seen)
            val migrated = Events()
            claude.startTurn(request(NativeConversation("9d6b5d91-5085-4e72-a576-7d379498daf0", "")), migrated)
            assertEquals(listOf("failed ConversationLost"), migrated.seen)
            val model = Events()
            claude.startTurn(request(model = "--not-a-model"), model)
            assertEquals(listOf("failed ModelUnavailable"), model.seen)
        }
        assertTrue(fake.turns().isEmpty())
        turn(fake, "turn-success.ndjson", request = request(model = RUNTIME_DEFAULT_MODEL))
        assertEquals("sonnet", fake.turns().single().argv().let { it[it.indexOf("--model") + 1] })
    }

    @Test fun discoveredFullModelIdsReachClaudeWithoutLosingTheContextSuffix() {
        val fake = FakeClaude()
        val model = "claude-fable-5-1[1m]"
        val events = turn(fake, "turn-success.ndjson", request = request(model = model))
        assertTrue(events.seen.any { it.startsWith("completed") })
        assertEquals(model, fake.turns().single().argv().let { it[it.indexOf("--model") + 1] })
        assertEquals("future-alias", claudeModel("future-alias"))
        for (invalid in listOf("claude-fable[2m]", "claude-fable --unsafe", "--model")) assertNull(claudeModel(invalid))
    }

    @Test fun stopInterruptsTheTurnAndKeepsTheTextSoFar() {
        val fake = FakeClaude()
        fake.scenario(turn = FakeClaude.fixture("turn-sigint.ndjson"))
        val events = Events()
        claude(fake).use { claude ->
            val handle = claude.startTurn(request(), events)
            await { events.seen.lastOrNull() == "partial 1. Juicy apple\n2. Sweet banana\n3." }
            handle.stop()
            await("The fake did not receive SIGINT") { fake.interrupted }
            Thread.sleep(300)
        }
        assertEquals("partial 1. Juicy apple\n2. Sweet banana\n3.", events.seen.last())
        assertTrue(events.seen.none { it.startsWith("completed") || it.startsWith("failed") })
    }

    @Test fun releaseEndsARunningTurnWithoutReportingIt() {
        val fake = FakeClaude()
        fake.scenario(turn = FakeClaude.fixture("turn-sigint.ndjson").replace("{\"__fake\":\"wait_for_sigint\"}", "{\"__fake\":\"wait_for_sigint\"}\n{\"__fake\":\"wait_for_sigint\"}"))
        val events = Events()
        claude(fake).use { claude ->
            claude.startTurn(request(), events)
            await { events.seen.any { it.startsWith("partial") } }
            claude.release()
            Thread.sleep(500)
        }
        assertFalse(fake.interrupted)
        assertTrue(events.seen.none { it.startsWith("completed") || it.startsWith("failed") })
    }

    @Test fun aFailureWhileReadingIsReported() {
        val fake = FakeClaude()
        val events = object : TurnEvents {
            val seen = CopyOnWriteArrayList<String>()
            override fun opened(conversation: String) = Unit
            override fun accepted() = Unit
            override fun partial(text: String) { error("The chat could not take this text") }
            override fun completed(text: String, conversation: String) { seen += "completed" }
            override fun failed(failure: ChatFailure) { seen += "failed $failure" }
        }
        claude(fake).use { it.startTurn(request(), events); await { events.seen.isNotEmpty() } }
        assertEquals(listOf("failed Unexpected"), events.seen)
    }

    @Test fun aNewTurnOnASessionWaitsForItsStoppedProcess() {
        val fake = FakeClaude()
        val session = "9d6b5d91-5085-4e72-a576-7d379498daf0"
        fake.scenario(turn = FakeClaude.fixture("turn-sigint.ndjson").replace("{\"__fake\":\"wait_for_sigint\"}", "{\"__fake\":\"wait_for_sigint\"}\n{\"__fake\":\"linger\"}"))
        claude(fake).use { claude ->
            val first = Events()
            val handle = claude.startTurn(request(NativeConversation(session, persona.hash)), first)
            await { first.seen.any { it.startsWith("partial") } }
            handle.stop()
            await { fake.interrupted }
            fake.scenario(turn = FakeClaude.fixture("turn-success.ndjson"))
            val second = Events()
            claude.startTurn(request(NativeConversation(session, persona.hash)), second)
            await { second.ended() }
            assertTrue(second.seen.last().startsWith("completed"), second.seen.toString())
        }
        assertEquals(listOf(false, false), fake.turns().map { it.getValue("overlap").jsonPrimitive.boolean })
    }

    @Test fun aCleanExitWithoutAResultKeepsTheStreamedText() {
        val fake = FakeClaude()
        val withoutResult = FakeClaude.fixture("turn-success.ndjson").lines().filterNot { it.contains("\"type\":\"result\"") }.joinToString("\n")
        fake.scenario(turn = withoutResult)
        val events = Events()
        claude(fake).use { it.startTurn(request(), events); await { events.ended() } }
        assertTrue(events.seen.last().startsWith("completed You asked me to say \"hello,\" and my name is Ember."), events.seen.toString())
        // Without a verified start, or after a failure, the stream is never used.
        fake.scenario(turn = withoutResult, exit = 1)
        val failed = Events()
        claude(fake).use { it.startTurn(request(), failed); await { failed.ended() } }
        assertEquals("failed Unexpected", failed.seen.last())
    }

    @Test fun readinessFollowsTheProviderStatus() {
        val fake = FakeClaude()
        claude(fake).use { claude ->
            assertEquals(Readiness.Ready, claude.readiness(account))
            fake.scenario(auth = FakeClaude.fixture("auth-status-signed-out.json"), authExit = 1)
            assertEquals(Readiness.SignedOut, claude.readiness(account))
            fake.scenario(version = "2.0.0 (Claude Code)")
            assertEquals(Readiness.Unavailable("Update Claude Code to use it here. Hovel needs version 2.1.304 or later."), claude.readiness(account))
        }
        assertTrue(fake.calls().none { "login" in it.argv() || "logout" in it.argv() })
        assertFails { claude(fake).use { it.startTurn(request().copy(accountId = "../x"), Events()) } }
        assertFalse(Files.exists(Path.of(fake.providers.toString(), "..", "x")))
    }
    @Test fun talkingEffortReachesTheCliAndAutoLeavesItUnset() {
        val fake = FakeClaude()
        turn(fake, "turn-success.ndjson", request = request().copy(effort = ModelEffort.XHIGH))
        val argv = fake.turns().single().argv()
        assertEquals("xhigh", argv[argv.indexOf("--effort") + 1])
        assertTrue(claudeEffortArguments(null).isEmpty())
    }

}
