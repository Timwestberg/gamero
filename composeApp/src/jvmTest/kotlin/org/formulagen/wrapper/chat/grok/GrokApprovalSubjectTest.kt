package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalSubject

/** Source-derived envelopes plus tagged command/edit shapes observed in Grok Build 1.0.70. */
class GrokApprovalSubjectTest {
    private val folder = Path.of("/scratch/project")

    @Test fun commandUsesVerifiedInputAndIgnoresTheDescriptiveTitle() {
        val tool = commandTool()

        assertEquals(ApprovalSubject.Command("echo hello", null, null, "Run the example\ntimeout: 5000\nis_background: false"),
            grokApprovalSubject(tool, folder))
    }

    @Test fun conflictingOrUnversionedToolIdentityIsDenied() {
        val tool = commandTool()
        assertNull(grokApprovalSubject(JsonObject(tool - "_meta"), folder))
        assertNull(grokApprovalSubject(envelope("execute", "mcp", """{"command":"echo hello","description":"Run the example"}""",
            """{"command":"echo hello","description":"Run the example"}"""), folder))
        assertNull(grokApprovalSubject(envelope("execute", "grok_build", """{"command":"echo good","description":"Run the example"}""",
            """{"command":"echo bad","description":"Run the example"}"""), folder))
        assertNull(grokApprovalSubject(Json.parseToJsonElement(tool.toString().replace("\"version\":1", "\"version\":2")).jsonObject, folder))
    }

    @Test fun taggedCommandMustAgreeWithCanonicalOperationIdentity() {
        val tool = commandTool()
        val expected = ApprovalSubject.Command("echo hello", null, null, "Run the example\ntimeout: 5000\nis_background: false")

        assertEquals(expected, grokApprovalSubject(withRaw(tool, "variant", JsonPrimitive("Bash")), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "variant", JsonPrimitive("Write")), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "variant", JsonNull), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "variant", JsonPrimitive(1)), folder))
    }

    @Test fun taggedFileOperationsRequireTheirMatchingVariant() {
        val edit = envelope("edit", "grok_build", """{"path":"notes.txt"}""",
            """{"variant":"SearchReplace","file_path":"notes.txt","old_string":"old","new_string":"new"}""")
        val write = envelope("write", "opencode", """{"path":"notes.txt"}""",
            """{"variant":"Write","file_path":"notes.txt","content":"new"}""")

        assertEquals(ApprovalSubject.Edit("Edit", listOf(folder.resolve("notes.txt")),
            "Replace one matching occurrence\nBefore:\nold\nAfter:\nnew"), grokApprovalSubject(edit, folder))
        assertEquals(ApprovalSubject.Edit("Write", listOf(folder.resolve("notes.txt")),
            "Create or replace file contents:\nnew"), grokApprovalSubject(write, folder))
        assertNull(grokApprovalSubject(withRaw(edit, "variant", JsonPrimitive("Write")), folder))
        assertNull(grokApprovalSubject(withRaw(write, "variant", JsonPrimitive("SearchReplace")), folder))
    }

    @Test fun backgroundUnknownAndMalformedCommandSettingsAreDenied() {
        val tool = commandTool()
        assertNull(grokApprovalSubject(withRaw(tool, "is_background", JsonPrimitive(true)), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "timeout", JsonPrimitive(0)), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "timeout", JsonPrimitive("5000")), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "block_until_ms", JsonPrimitive(0)), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "extra_effect", JsonPrimitive("unknown")), folder))
    }

    @Test fun editsShowExactResolvedPathAndReplacementScope() {
        val tool = envelope("edit", "grok_build", """{"path":"src/main.kt"}""",
            """{"file_path":"src/main.kt","old_string":"before","new_string":"after","replace_all":true}""")

        assertEquals(ApprovalSubject.Edit("Edit", listOf(Path.of("/scratch/project/src/main.kt")),
            "Replace all matching occurrences\nBefore:\nbefore\nAfter:\nafter"), grokApprovalSubject(tool, folder))
    }

    @Test fun emptyOldTextIsPresentedAsPossibleFileReplacement() {
        val tool = envelope("edit", "grok_build", """{"path":"notes.txt"}""",
            """{"file_path":"notes.txt","old_string":"","new_string":"new contents"}""")

        assertEquals(ApprovalSubject.Edit("Edit", listOf(Path.of("/scratch/project/notes.txt")),
            "Create or replace file contents\nBefore:\n\nAfter:\nnew contents"), grokApprovalSubject(tool, folder))
    }

    @Test fun writesShowFullContentAndPreserveAnExplicitOutsideProjectPath() {
        val tool = envelope("write", "opencode", """{"path":"../other.txt"}""",
            """{"file_path":"../other.txt","content":"hello"}""")

        assertEquals(ApprovalSubject.Edit("Write", listOf(Path.of("/scratch/other.txt")),
            "Create or replace file contents:\nhello"), grokApprovalSubject(tool, folder))
    }

    @Test fun incompleteMismatchedOrTruncatedFileOperationsAreDenied() {
        val tool = envelope("write", "opencode", """{"path":"notes.txt"}""",
            """{"file_path":"notes.txt","content":"hello"}""")
        assertNull(grokApprovalSubject(withRaw(tool, "content", JsonPrimitive("x".repeat(4_000))), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "file_path", JsonPrimitive("elsewhere.txt")), folder))
        assertNull(grokApprovalSubject(withRaw(tool, "content", JsonNull), folder))
        assertNull(grokApprovalSubject(tool, Path.of("relative")))
        assertNull(grokApprovalSubject(buildJsonObject { put("title", "Write notes.txt"); put("kind", "edit") }, folder))
    }

    private fun commandTool() = envelope("execute", "grok_build",
        """{"command":"echo hello","description":"Run the example"}""",
        """{"command":"echo hello","description":"Run the example","timeout":5000,"is_background":false}""")

    private fun withRaw(tool: JsonObject, field: String, value: JsonElement) =
        JsonObject(tool + ("rawInput" to JsonObject(tool.getValue("rawInput").jsonObject + (field to value))))

    private fun envelope(kind: String, namespace: String, input: String, raw: String) = buildJsonObject {
        put("title", "An unrelated friendly title")
        put("rawInput", Json.parseToJsonElement(raw))
        putJsonObject("_meta") { putJsonObject("x.ai/tool") {
            put("version", 1); put("name", "opaque-tool-name"); put("kind", kind); put("namespace", namespace)
            put("label", "Tool"); put("read_only", false); put("input", Json.parseToJsonElement(input))
        } }
    }
}
