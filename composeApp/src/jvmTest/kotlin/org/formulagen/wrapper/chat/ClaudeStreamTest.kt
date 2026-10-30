package org.formulagen.wrapper.chat

import java.time.Instant
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL
import org.formulagen.wrapper.providers.FakeClaude
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

/** Parsing, lockdown and classification on trimmed output recorded from Claude Code 2.1.304. */
class ClaudeStreamTest {
    private fun events(fixture: String) = FakeClaude.fixture(fixture).lines().filter { it.isNotBlank() && !it.startsWith("{\"__fake\"") }.map(ClaudeStream::parse)
    private inline fun <reified T : ClaudeEvent> List<ClaudeEvent>.only() = filterIsInstance<T>()

    @Test fun aLockedDownTurnStreamsTextAndEndsWithItsResult() {
        val events = events("turn-success.ndjson")
        val init = events.only<ClaudeEvent.Init>().single()
        assertEquals(emptySet(), ClaudeLockdown.verify(init))
        assertEquals("SESSION_ID", init.sessionId)
        // Sonnet's thinking block streams too, but only text deltas are reply text.
        assertEquals(listOf("You asked me", " to say \"hello,\" and my", " name is Ember."), events.only<ClaudeEvent.TextDelta>().map { it.text })
        assertEquals(ClaudeEvent.RateLimit("allowed", Instant.ofEpochSecond(1793158800)), events.only<ClaudeEvent.RateLimit>().single())
        val result = events.only<ClaudeEvent.Result>().single()
        assertEquals("You asked me to say \"hello,\" and my name is Ember.", result.result)
        assertTrue(ClaudeFailures.succeeded(0, result))
        assertFalse(ClaudeFailures.succeeded(1, result)); assertFalse(ClaudeFailures.succeeded(null, result)); assertFalse(ClaudeFailures.succeeded(0, null))
        assertTrue(events.only<ClaudeEvent.AssistantError>().isEmpty())
    }

    @Test fun anythingBeyondTheBuiltinsBreaksTheLockdown() {
        val loaded = events("turn-lockdown.ndjson").only<ClaudeEvent.Init>().single()
        assertEquals(setOf("skills", "slash_commands"), ClaudeLockdown.verify(loaded))
        val clean = events("turn-success.ndjson").only<ClaudeEvent.Init>().single()
        assertEquals(setOf("tools"), ClaudeLockdown.verify(clean.copy(tools = 1)))
        assertEquals(setOf("mcp_servers"), ClaudeLockdown.verify(clean.copy(mcpServers = null)))
        assertEquals(setOf("plugins"), ClaudeLockdown.verify(clean.copy(plugins = clean.plugins!! + ClaudeEvent.Plugin("cc-plugin-agents-md", "/Users/x/plugins"))))
        assertEquals(setOf("plugins"), ClaudeLockdown.verify(clean.copy(plugins = listOf(ClaudeEvent.Plugin("my-plugin", "builtin")))))
        assertEquals(setOf("plugins"), ClaudeLockdown.verify(clean.copy(plugins = null)))
        assertEquals(setOf("permissionMode", "apiKeySource"), ClaudeLockdown.verify(clean.copy(permissionMode = "bypassPermissions", apiKeySource = "ANTHROPIC_API_KEY")))
        assertEquals(emptySet(), ClaudeLockdown.verify(clean.copy(plugins = emptyList())))
        // A custom agent or output style is someone's configuration, not the companion.
        assertEquals(listOf("claude", "Explore", "general-purpose", "Plan"), clean.agents)
        assertEquals("default", clean.outputStyle)
        assertEquals(setOf("agents"), ClaudeLockdown.verify(clean.copy(agents = clean.agents!! + "my-agent")))
        assertEquals(setOf("agents"), ClaudeLockdown.verify(clean.copy(agents = null)))
        assertEquals(setOf("output_style"), ClaudeLockdown.verify(clean.copy(outputStyle = "Pirate")))
        assertEquals(setOf("output_style"), ClaudeLockdown.verify(clean.copy(outputStyle = null)))
        val missing = ClaudeStream.parse("""{"type":"system","subtype":"init","session_id":"s"}""") as ClaudeEvent.Init
        assertEquals(setOf("tools", "mcp_servers", "skills", "slash_commands", "plugins", "permissionMode", "apiKeySource", "agents", "output_style"),
            ClaudeLockdown.verify(missing))
    }

    @Test fun failuresAreClassifiedByErrorCodeNotSubtype() {
        fun classify(fixture: String): ChatFailure {
            val events = events(fixture)
            val result = events.only<ClaudeEvent.Result>().singleOrNull()
            assertFalse(ClaudeFailures.succeeded(1, result))
            return ClaudeFailures.classify(result, events.only<ClaudeEvent.AssistantError>().lastOrNull()?.error, events.only<ClaudeEvent.RateLimit>().lastOrNull())
        }
        assertEquals(ChatFailure.ModelUnavailable, classify("turn-bad-model.ndjson"))
        assertEquals(ChatFailure.SignedOut, classify("turn-authentication-failed.ndjson"))
        assertEquals(ChatFailure.UsageLimit(Instant.ofEpochSecond(1793158800)), classify("turn-rate-limit.ndjson"))
        assertEquals(ChatFailure.ConversationLost, classify("turn-resume-unknown.ndjson"))
        val stopped = events("turn-sigint.ndjson").only<ClaudeEvent.Result>().single()
        assertFalse(ClaudeFailures.succeeded(0, stopped))
        assertEquals(ChatFailure.Unexpected, ClaudeFailures.classify(stopped, null, null))
        assertEquals(ChatFailure.UsageLimit(null), ClaudeFailures.classify(stopped.copy(terminalReason = "blocking_limit"), null, ClaudeEvent.RateLimit("allowed", Instant.EPOCH)))
        assertEquals(ChatFailure.Temporary, ClaudeFailures.classify(stopped, "overloaded", null))
        assertEquals(ChatFailure.Temporary, ClaudeFailures.classify(stopped.copy(apiErrorStatus = 529), null, null))
        assertEquals(ChatFailure.Unexpected, ClaudeFailures.classify(null, null, null))
        assertEquals(ClaudeEvent.Other, ClaudeStream.parse("not json")); assertEquals(ClaudeEvent.Other, ClaudeStream.parse("[1]"))
        assertEquals(true, (ClaudeStream.parse("""{"type":"result"}""") as ClaudeEvent.Result).isError)
        assertEquals(ClaudeEvent.Other, ClaudeStream.parse("""{"type":"stream_event","parent_tool_use_id":"t","event":{"type":"content_block_delta","delta":{"type":"text_delta","text":"sub"}}}"""))
    }

    @Test fun theTurnCommandIsLockedDownAndAcceptsModelAliasesAndIds() {
        val persona = Persona.role("Ember", "Coordinator", "Help the user.", working = false)
        assertEquals(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--safe-mode", "--disable-slash-commands", "--tools", "", "--disallowedTools", "mcp__*", "--strict-mcp-config",
            "--permission-mode", "dontAsk", "--permission-prompts", "none", "--max-turns", "1",
            "--model", "opus", "--system-prompt", persona.instructions), claudeTurnArguments("opus", persona))
        assertEquals("sonnet", claudeModel(null)); assertEquals("sonnet", claudeModel(RUNTIME_DEFAULT_MODEL))
        assertEquals("haiku", claudeModel("haiku")); assertEquals("claude-opus-5-5", claudeModel("claude-opus-5-5"))
        assertEquals("claude-fable-5-1[1m]", claudeModel("claude-fable-5-1[1m]")); assertNull(claudeModel("--bare"))
        val line = Json.parseToJsonElement(userMessage("Hi \"there\"\nfriend")).jsonObject
        assertEquals(buildJsonObject {
            put("type", "user"); putJsonObject("message") { put("role", "user"); put("content", "Hi \"there\"\nfriend") }; put("parent_tool_use_id", JsonNull)
        }, line)
        assertFalse(userMessage("a\nb").contains('\n'))
    }
}
