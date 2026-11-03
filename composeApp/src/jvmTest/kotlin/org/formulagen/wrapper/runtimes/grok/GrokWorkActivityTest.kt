package org.formulagen.wrapper.runtimes.grok

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.ActivityLimits
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.workspace.EditListener

class GrokWorkActivityTest {
    @Test fun aLongWorkTurnCanRunAnyNumberOfToolsWithBoundedMemoryAndActivity() {
        val activity = GrokWorkActivity("current", Path.of("/project"))
        fun update(id: String, vararg fields: Pair<String, JsonElement>) = buildJsonObject {
            put("sessionId", "current"); put("update", JsonObject(mapOf("sessionUpdate" to JsonPrimitive("tool_call_update"),
                "toolCallId" to JsonPrimitive(id), "kind" to JsonPrimitive("read")) + fields))
        }
        var last: Activity? = null
        repeat(1_000) { index ->
            assertNull(activity.update(update("call-$index")))
            last = activity.update(update("call-$index", "status" to JsonPrimitive("completed"))) ?: last
        }
        assertNull(activity.state.end)
        assertEquals(ActivityLimits.STEPS, last?.steps?.size)
        assertEquals(true, last?.truncated)
        // A repeated final update for a recently finished call adds nothing.
        assertNull(activity.update(update("call-999", "status" to JsonPrimitive("completed"))))
        // Many calls that never finish are forgotten oldest first, never fatal.
        repeat(1_000) { assertNull(activity.update(update("open-$it"))) }
        assertNull(activity.state.end)
        // A malformed id is still a protocol violation.
        activity.update(update(""))
        assertEquals(GrokTurnEnd.INVALID, activity.state.end)
    }

    @Test fun aCompletedEditReportsItsFileOrNullAndNothingElseIsAnEdit() {
        val reported = mutableListOf<Path?>()
        val activity = GrokWorkActivity("current", Path.of("/project"), EditListener { reported += it })
        fun update(id: String, vararg fields: Pair<String, JsonElement>) = activity.update(buildJsonObject {
            put("sessionId", "current"); put("update", JsonObject(mapOf("sessionUpdate" to JsonPrimitive("tool_call_update"),
                "toolCallId" to JsonPrimitive(id)) + fields))
        })
        val edit = "kind" to JsonPrimitive("edit")
        fun input(path: String) = "rawInput" to buildJsonObject { put("file_path", path); put("old_string", "a"); put("new_string", "b") }
        val completed = "status" to JsonPrimitive("completed")
        // Sparse: the kind and input first, the status later.
        update("sparse", edit, input("/project/src/app.ts"))
        update("sparse", completed)
        update("whole", edit, input("notes.md"), completed)
        update("unnamed", edit, completed)
        update("failed", edit, input("/project/failed.ts"), "status" to JsonPrimitive("failed"))
        update("running", edit, input("/project/running.ts"), "status" to JsonPrimitive("in_progress"))
        update("read", "kind" to JsonPrimitive("read"), input("/project/README.md"), completed)
        update("command", "kind" to JsonPrimitive("execute"), "rawInput" to buildJsonObject { put("command", "sed -i '' s/a/b/ x") }, completed)
        // A repeated final update for a finished edit.
        update("whole", edit, input("notes.md"), completed)
        assertEquals(listOf(Path.of("/project/src/app.ts"), Path.of("notes.md"), null), reported)
    }

    @Test fun workActivityCorrelatesSparseUpdatesAndDoesNotLeakToolOutput() {
        val activity = GrokWorkActivity("current", Path.of("/project"))
        fun update(session: String = "current", vararg fields: Pair<String, JsonElement>) = buildJsonObject {
            put("sessionId", session); put("update", JsonObject(mapOf("sessionUpdate" to JsonPrimitive("tool_call_update"),
                "toolCallId" to JsonPrimitive("private-id")) + fields))
        }
        assertNull(activity.update(update("foreign", "status" to JsonPrimitive("completed"))))
        assertFalse(activity.state.accepted)
        assertNull(activity.update(update(fields = arrayOf("kind" to JsonPrimitive("execute"), "rawInput" to buildJsonObject { put("command", "SECRET") }))))
        assertTrue(activity.state.accepted)
        val result = activity.update(update(fields = arrayOf("status" to JsonPrimitive("completed"), "rawOutput" to JsonPrimitive("SECRET"))))
        assertEquals("Run command", result?.steps?.single()?.label)
        assertEquals(ActivityStatus.DONE, result?.steps?.single()?.status)
        assertNull(result?.steps?.single()?.detail)
        assertNull(activity.update(update(fields = arrayOf("status" to JsonPrimitive("completed")))))
        assertEquals(GrokTurnEnd.COMPLETE, activity.finish(buildJsonObject { put("stopReason", "end_turn") }).end)
    }
}
