package org.formulagen.wrapper.runtimes.claude

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.conversation.ActivityKind.*
import org.formulagen.wrapper.conversation.ActivityLimits
import org.formulagen.wrapper.conversation.ActivityStatus.*
import org.formulagen.wrapper.conversation.ActivityStep
import kotlin.test.*

/** Claude Code's host control protocol and activity, checked on lines recorded in the abilities spike (trimmed, paths replaced). */
class ClaudeControlTest {
    private val folder = Path.of("/private/tmp/hovel-fixture/project")
    private val opus = TeamMember(RuntimeId.CLAUDE_CODE, "opus")

    private fun fixture(name: String): List<JsonObject> =
        checkNotNull(javaClass.getResource("/claude/work/$name")).readText().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }
    private fun json(text: String) = Json.parseToJsonElement(text)
    private fun permission(line: JsonObject) = (ClaudeControl.decode(line) as ClaudeControlMessage.CanUseTool).permission

    @Test fun hovelOpensAndInterruptsWithControlRequests() {
        assertEquals(json("""{"type":"control_request","request_id":"host-1","request":{"subtype":"initialize"}}"""), json(ClaudeControl.initialize("host-1")))
        assertEquals(json("""{"type":"control_request","request_id":"host-2","request":{"subtype":"interrupt"}}"""), json(ClaudeControl.interrupt("host-2")))
    }

    @Test fun everyRecordedDecisionIsAnsweredExactlyAsClaudeCodeAcceptedIt() {
        val records = fixture("decisions.jsonl")
        assertEquals(setOf("allow_once", "allow_session", "deny"), records.map { it.getValue("decision").jsonPrimitive.content }.toSet())
        for (record in records) {
            val permission = permission(record.getValue("request").jsonObject)
            val decision = when (record.getValue("decision").jsonPrimitive.content) {
                "allow_once" -> ApprovalDecision.ALLOW_ONCE
                "allow_session" -> ApprovalDecision.ALLOW_FOR_CONVERSATION
                else -> ApprovalDecision.DENY
            }
            assertEquals(record.getValue("response"), json(ClaudeControl.answer(permission, decision)), "${permission.toolName} $decision")
        }
    }

    @Test fun aConversationGrantKeepsClaudesSuggestionsButOnlyForTheSession() {
        val bash = permission(fixture("decisions.jsonl")[5].getValue("request").jsonObject)
        assertEquals("Bash", bash.toolName)
        assertEquals("localSettings", bash.suggestions[0].jsonObject["destination"]?.jsonPrimitive?.content, "Claude suggests the player's settings file")
        val granted = json(ClaudeControl.allowForSession(bash)).jsonObject.getValue("response").jsonObject.getValue("response").jsonObject
        val updated = granted.getValue("updatedPermissions").jsonArray.map { it.jsonObject }
        assertTrue(updated.all { it["destination"] == JsonPrimitive("session") })
        assertEquals(bash.suggestions.map { (it.jsonObject - "destination") }, updated.map { it - "destination" })
        assertEquals(JsonPrimitive("user_permanent"), granted["decisionClassification"])
        // Nothing to grant for the session is answered as allow once.
        val bare = bash.copy(suggestions = JsonArray(emptyList()))
        assertEquals(json(ClaudeControl.allowOnce(bare)), json(ClaudeControl.answer(bare, ApprovalDecision.ALLOW_FOR_CONVERSATION)))
    }

    @Test fun theDialogShowsExactlyWhatClaudeAsksAndThePageOnlyItsSummary() {
        val records = fixture("decisions.jsonl").map { permission(it.getValue("request").jsonObject) }
        val edit = ClaudeControl.approvalRequest(records[0], "Ember", opus, folder)
        assertEquals(ApprovalKind.EDIT, edit.kind)
        assertEquals("Edit /private/tmp/hovel-fixture/project/notes.txt", edit.title)
        assertEquals("- first note line\n+ first note line\nhello from Ember", edit.detail)
        assertEquals("Allow all edits in project for this conversation", edit.sessionLabel)
        assertEquals(listOf("Ember", opus, folder), listOf(edit.companionName, edit.member, edit.folder))
        val command = ClaudeControl.approvalRequest(records[5], "Ember", opus, folder)
        assertEquals(ApprovalKind.COMMAND, command.kind)
        assertEquals("echo 'from echo' >> notes.txt", command.title)
        assertEquals("Append 'from echo' to notes.txt\nNeeds access to /private/tmp/hovel-fixture/project/notes.txt", command.detail)
        // The project folder Claude would add is already the working folder, so only the command is named.
        assertEquals("Allow `echo 'from echo' >> notes.txt` for this conversation", command.sessionLabel)
        // A command that reaches outside the project appears verbatim in the dialog, its grant too.
        val outside = permission(json("""{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool","tool_name":"Bash",
            "input":{"command":"cp notes.txt /Users/player/Desktop/notes.txt"},"blocked_path":"/Users/player/Desktop/notes.txt","tool_use_id":"t",
            "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"cp notes.txt /Users/player/Desktop/notes.txt"}],"behavior":"allow","destination":"localSettings"},
            {"type":"addDirectories","directories":["/Users/player/Desktop"],"destination":"session"}]}}""").jsonObject)
        val asked = ClaudeControl.approvalRequest(outside, "Ember", opus, folder)
        assertEquals("cp notes.txt /Users/player/Desktop/notes.txt", asked.title)
        assertEquals("Needs access to /Users/player/Desktop/notes.txt", asked.detail)
        assertEquals("Allow `cp notes.txt /Users/player/Desktop/notes.txt` and access to /Users/player/Desktop for this conversation", asked.sessionLabel)
    }

    @Test fun connectorsAndOtherToolsAreNamedAndUnknownGrantsAreNeverOffered() {
        val connector = permission(json("""{"type":"control_request","request_id":"r","request":{"subtype":"can_use_tool","tool_name":"mcp__blender__execute_code",
            "display_name":"execute_code","input":{"code":"print(1)"},"mcp_server":"blender","tool_use_id":"t",
            "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"mcp__blender__execute_code"}],"behavior":"allow","destination":"localSettings"}]}}""").jsonObject)
        val asked = ClaudeControl.approvalRequest(connector, "Ember", opus, folder)
        assertEquals(ApprovalKind.CONNECTOR, asked.kind)
        assertEquals("blender · execute_code", asked.title)
        assertEquals("""{"code":"print(1)"}""", asked.detail)
        assertEquals("Allow blender · execute_code for this conversation", asked.sessionLabel)
        assertEquals(ApprovalKind.OTHER, ClaudeControl.kindOf("WebFetch"))
        assertEquals(ApprovalKind.EDIT, ClaudeControl.kindOf("NotebookEdit"))
        assertEquals(ApprovalKind.CONNECTOR, ClaudeControl.kindOf("Fetch", mcpServer = "web"))
        fun label(suggestions: String) = ClaudeControl.sessionLabel(json(suggestions).jsonArray, folder)
        assertNull(label("[]"))
        assertNull(label("""[{"type":"removeRules","rules":[],"destination":"session"}]"""), "A grant Hovel cannot describe is not offered")
        assertNull(label("""[{"type":"setMode","mode":"bypassPermissions","destination":"session"}]"""))
        assertEquals("Allow `npm test …` and access to /Users/player/Downloads for this conversation", label("""[
            {"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test:*"}],"behavior":"allow","destination":"localSettings"},
            {"type":"addDirectories","directories":["/Users/player/Downloads","/private/tmp/hovel-fixture/project/src"],"destination":"session"}]"""))
    }

    @Test fun otherRequestsAreRefusedUnreadableOnesDeniedAndCancelsRead() {
        assertEquals(ClaudeControlMessage.Unsupported("r1", "hook_callback"),
            ClaudeControl.decode(json("""{"type":"control_request","request_id":"r1","request":{"subtype":"hook_callback"}}""").jsonObject))
        assertEquals(json("""{"type":"control_response","response":{"subtype":"error","request_id":"r1","error":"Hovel does not handle this request."}}"""),
            json(ClaudeControl.error("r1")))
        assertEquals(ClaudeControlMessage.Unreadable("r2", "t2"),
            ClaudeControl.decode(json("""{"type":"control_request","request_id":"r2","request":{"subtype":"can_use_tool","tool_name":"Edit","input":"not an object","tool_use_id":"t2"}}""").jsonObject))
        assertEquals(json("""{"type":"control_response","response":{"subtype":"success","request_id":"r2","response":{"behavior":"deny",
            "message":"${ClaudeControl.UNREADABLE}","toolUseID":"t2","decisionClassification":"user_reject"}}}"""),
            json(ClaudeControl.deny("r2", "t2", ClaudeControl.UNREADABLE)))
        assertNull(ClaudeControl.decode(json("""{"type":"control_request","request":{"subtype":"can_use_tool"}}""").jsonObject), "Nothing to answer")
        assertEquals(ClaudeControlMessage.Cancel("r3"), ClaudeControl.decode(json("""{"type":"control_cancel_request","request_id":"r3"}""").jsonObject))
        assertEquals(ClaudeControlMessage.Response("host-1", true, buildJsonObject { put("ok", true) }),
            ClaudeControl.decode(json("""{"type":"control_response","response":{"subtype":"success","request_id":"host-1","response":{"ok":true}}}""").jsonObject))
        assertEquals(ClaudeControlMessage.Response("host-2", false, null),
            ClaudeControl.decode(json("""{"type":"control_response","response":{"subtype":"error","request_id":"host-2","error":"no"}}""").jsonObject))
        assertNull(ClaudeControl.decode(json("""{"type":"assistant","message":{}}""").jsonObject))
    }

    private fun activity(name: String) = ClaudeActivity(folder).also { mapper -> fixture(name).forEach { mapper.receive(it) } }

    @Test fun activityFollowsReadsEditsCommandsAndDenialsAsRecorded() {
        val mapper = activity("turn-edit-and-denials.ndjson")
        assertEquals(listOf(
            ActivityStep(READ, "notes.txt", DONE, "2 lines"),
            ActivityStep(EDIT, "notes.txt", DONE, "+1 -0"),
            ActivityStep(COMMAND, "echo 'from echo' >> notes.txt && ls", DENIED, "The player chose Deny in Hovel for this action."),
            ActivityStep(EDIT, "notes.txt", DENIED, "The player chose Deny in Hovel for this action."),
            ActivityStep(EDIT, "notes.txt", DONE, "+1 -0"),
            ActivityStep(EDIT, "notes.txt", DONE, "+1 -0"),
        ), mapper.activity().steps)
        assertFalse(mapper.activity().truncated)
        assertFalse(mapper.deniedWithoutAsking)
        val session = activity("turn-session-command.ndjson").activity().steps
        assertEquals(listOf(ActivityStep(EDIT, "notes.txt", DONE, "+1 -0"), ActivityStep(COMMAND, "echo 'from echo' >> notes.txt", DONE, "(no output)"),
            ActivityStep(COMMAND, "echo 'from echo' >> notes.txt", DONE, "(no output)")), session)
    }

    @Test fun aDenialClaudeMadeWithoutAskingIsSeenAndPathsNeverLeaveTheProject() {
        val mapper = activity("turn-without-host.ndjson")
        assertTrue(mapper.deniedWithoutAsking)
        val steps = mapper.activity().steps
        assertEquals(listOf(READ, COMMAND, COMMAND, EDIT, COMMAND), steps.map { it.kind })
        assertEquals(listOf(DONE, DENIED, DONE, DENIED, DONE), steps.map { it.status })
        assertEquals("printf 'hello from Ember\\n' >> ./notes.txt && cat -A ./notes.txt", steps[1].label)
        assertEquals("ls -la .", steps[2].label)
        assertTrue(steps.none { "/private/tmp" in it.label || "/private/tmp" in it.detail.orEmpty() }, steps.toString())
    }

    @Test fun aDenialOnlyTheResultReportsIsAddedOnceAndSubagentStepsAreNot() {
        val mapper = ClaudeActivity(folder)
        mapper.receive(json("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a","name":"Write","input":{"file_path":"/elsewhere/secret.txt"}}]},"parent_tool_use_id":null}""").jsonObject)
        mapper.receive(json("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"sub","name":"Read","input":{"file_path":"x"}}]},"parent_tool_use_id":"task-1"}""").jsonObject)
        mapper.receive(json("""{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"sub","content":"x"}]},"parent_tool_use_id":"task-1"}""").jsonObject)
        val result = json("""{"type":"result","permission_denials":[{"tool_name":"Write","tool_use_id":"a","tool_input":{"file_path":"/elsewhere/secret.txt"}}]}""").jsonObject
        assertTrue(mapper.receive(result)); assertFalse(mapper.receive(result))
        assertEquals(listOf(ActivityStep(EDIT, "…/secret.txt", DENIED)), mapper.activity().steps)
    }

    @Test fun activityStaysWithinItsLimits() {
        val mapper = ClaudeActivity(folder)
        repeat(ActivityLimits.STEPS + 5) { i ->
            mapper.receive(json("""{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t$i","name":"Bash","input":{"command":"${"x".repeat(300)}"}}]}}""").jsonObject)
            mapper.receive(buildJsonObject {
                put("type", "user")
                putJsonObject("message") { putJsonArray("content") { addJsonObject { put("type", "tool_result"); put("tool_use_id", "t$i"); put("content", "y") } } }
                putJsonObject("tool_use_result") { put("stdout", "z".repeat(1000)); put("stderr", "") }
            })
        }
        val activity = mapper.activity()
        assertEquals(ActivityLimits.STEPS, activity.steps.size)
        assertTrue(activity.truncated)
        assertTrue(activity.steps.all { it.label.length == ActivityLimits.LABEL && it.detail!!.length == ActivityLimits.DETAIL && it.detail!!.endsWith("…") })
        assertEquals(listOf(OTHER, WEB, CONNECTOR, SEARCH), listOf("TodoWrite", "WebSearch", "mcp__x__y", "Grep").map(ClaudeActivity::kindOf))
    }
}
