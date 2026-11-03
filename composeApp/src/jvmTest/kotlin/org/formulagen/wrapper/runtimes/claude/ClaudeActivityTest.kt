package org.formulagen.wrapper.runtimes.claude

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.runtimes.EditListener

/** What a working Claude turn reports to its thread's edit record, from `stream-json` lines shaped as Claude Code 2.1.304 writes them. */
class ClaudeActivityTest {
    private val folder = Path.of("/project")
    private val reported = mutableListOf<Path?>()
    private val activity = ClaudeActivity(folder, EditListener { reported += it })

    private fun use(id: String, name: String, input: JsonObject, parent: String? = null) = buildJsonObject {
        put("type", "assistant"); put("parent_tool_use_id", parent?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("message") { putJsonArray("content") { addJsonObject {
            put("type", "tool_use"); put("id", id); put("name", name); put("input", input)
        } } }
    }

    /** A tool's result: [decision] is its permission decision, [error] its `is_error`, [rejected] a main-agent `non_execution_kind`. */
    private fun result(id: String, parent: String? = null, decision: String? = "accept", error: Boolean = false, rejected: String? = null) = buildJsonObject {
        put("type", "user"); put("parent_tool_use_id", parent?.let(::JsonPrimitive) ?: JsonNull)
        putJsonObject("message") { putJsonArray("content") { addJsonObject {
            put("type", "tool_result"); put("tool_use_id", id); put("content", "ok"); if (error) put("is_error", true)
        } } }
        putJsonArray("tool_result_meta") { addJsonObject {
            put("id", id); rejected?.let { put("non_execution_kind", it) }
            decision?.let { putJsonObject("permission_decision") { put("decision", it); put("source", "user_temporary") } }
        } }
    }

    private fun file(path: String) = buildJsonObject { put("file_path", path) }

    private fun finished(id: String, name: String, input: JsonObject, parent: String? = null, decision: String? = "accept", error: Boolean = false,
        rejected: String? = null) {
        activity.receive(use(id, name, input, parent))
        activity.receive(result(id, parent, decision, error, rejected))
    }

    @Test fun eachEditToolThatFinishedReportsItsFile() {
        finished("1", "Edit", file("/project/src/app.ts"))
        finished("2", "Write", file("notes.md"))
        finished("3", "MultiEdit", file("/project/src/lib.ts"))
        finished("4", "NotebookEdit", buildJsonObject { put("notebook_path", "/project/analysis.ipynb") })
        finished("5", "Read", file("/project/README.md"))
        finished("6", "Bash", buildJsonObject { put("command", "sed -i '' s/a/b/ src/app.ts") })
        finished("7", "Edit", buildJsonObject { put("old_string", "a") })
        assertEquals(listOf(Path.of("/project/src/app.ts"), Path.of("notes.md"), Path.of("/project/src/lib.ts"), Path.of("/project/analysis.ipynb"), null), reported,
            "reads and commands are no edits; an edit naming no file reports null")
    }

    @Test fun deniedAndFailedEditsAreNotReported() {
        finished("denied", "Edit", file("/project/a.ts"), decision = "reject", error = true)
        finished("rule", "Edit", file("/project/b.ts"), decision = null, error = true, rejected = "permission-rule")
        finished("failed", "Edit", file("/project/c.ts"), error = true)
        // A denial only the turn's result names.
        activity.receive(use("late", "Write", file("/project/d.ts")))
        activity.receive(buildJsonObject {
            put("type", "result")
            putJsonArray("permission_denials") { addJsonObject { put("tool_name", "Write"); put("tool_use_id", "late"); put("tool_input", file("/project/d.ts")) } }
        })
        // An edit still running when the turn ends.
        activity.receive(use("running", "Edit", file("/project/e.ts")))
        assertEquals(emptyList(), reported)
        assertEquals(listOf(ActivityStatus.DENIED, ActivityStatus.DENIED, ActivityStatus.FAILED, ActivityStatus.DENIED), activity.activity().steps.map { it.status })
        // A result repeated for a finished edit reports nothing more.
        finished("ok", "Edit", file("/project/f.ts"))
        activity.receive(result("ok"))
        assertEquals(listOf(Path.of("/project/f.ts")), reported)
    }

    @Test fun aSubagentsFinishedEditsAreReportedButNeverShownAsSteps() {
        activity.receive(use("agent", "Task", buildJsonObject { put("description", "Fix the tests") }))
        finished("s1", "Edit", file("/project/test/a.test.ts"), parent = "agent")
        finished("s2", "Write", file("/project/test/b.test.ts"), parent = "agent", decision = "reject", error = true)
        finished("s3", "Edit", file("/project/test/c.test.ts"), parent = "agent", error = true)
        finished("s4", "Read", file("/project/test/d.test.ts"), parent = "agent")
        finished("s5", "NotebookEdit", buildJsonObject { put("notebook_path", "/project/n.ipynb") }, parent = "agent")
        activity.receive(result("s1", parent = "agent"))
        activity.receive(result("agent"))
        assertEquals(listOf(Path.of("/project/test/a.test.ts"), Path.of("/project/n.ipynb")), reported)
        assertEquals(listOf(ActivityKind.OTHER to "Task: Fix the tests"), activity.activity().steps.map { it.kind to it.label }, "only the subagent's own call is a step")
    }

    @Test fun aSubagentsUnfinishedEditsAreForgottenOldestFirst() {
        repeat(300) { activity.receive(use("s$it", "Edit", file("/project/f$it.ts"), parent = "agent")) }
        activity.receive(result("s0", parent = "agent"))
        activity.receive(result("s299", parent = "agent"))
        assertEquals(listOf(Path.of("/project/f299.ts")), reported)
    }

    @Test fun editsPastThePagesStepLimitAreStillReported() {
        repeat(60) { finished("read-$it", "Read", file("/project/r$it.ts")) }
        finished("edit", "Edit", file("/project/late.ts"))
        assertTrue(activity.activity().truncated)
        assertEquals(listOf(Path.of("/project/late.ts")), reported)
    }
}
