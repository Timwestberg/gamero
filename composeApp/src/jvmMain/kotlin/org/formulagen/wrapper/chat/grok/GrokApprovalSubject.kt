package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalLimits
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.approvals.isFormatCharacter

/** Decode the pinned x.ai/tool v1 shape; absent, conflicting or unsupported detail cannot be approved. */
internal fun grokApprovalSubject(tool: JsonObject, folder: Path): ApprovalSubject? {
    val meta = (tool["_meta"] as? JsonObject)?.get("x.ai/tool") as? JsonObject ?: return null
    if (meta["version"] != JsonPrimitive(1) || meta["read_only"] != JsonPrimitive(false)) return null
    if (toolText(meta["name"], 256)?.isNotBlank() != true || toolText(meta["label"], 256) == null) return null
    val input = meta["input"] as? JsonObject ?: return null
    val raw = tool["rawInput"] as? JsonObject ?: return null
    return when (meta["namespace"] to meta["kind"]) {
        JsonPrimitive("grok_build") to JsonPrimitive("execute") -> commandSubject(input, raw)
        JsonPrimitive("grok_build") to JsonPrimitive("edit") -> editSubject(input, raw, folder)
        JsonPrimitive("opencode") to JsonPrimitive("write") -> writeSubject(input, raw, folder)
        else -> null
    }
}

/** Display the exact command and every supplied execution setting, never an agent-authored title. */
private fun commandSubject(input: JsonObject, raw: JsonObject): ApprovalSubject? {
    if (!toolVariant(raw, "Bash") || !input.keys.all { it in setOf("command", "description") } || !raw.keys.all { it in COMMAND_FIELDS }) return null
    val command = toolText(raw["command"], ApprovalLimits.PREVIEW)?.takeIf { it.isNotBlank() } ?: return null
    val description = toolText(raw["description"], 1_000) ?: return null
    if (input["command"] != JsonPrimitive(command) || input["description"] != JsonPrimitive(description)) return null
    if (raw["is_background"] != null && raw["is_background"] != JsonPrimitive(false)) return null
    for (field in listOf("timeout", "block_until_ms")) {
        val value = raw[field]
        if (value != null && value != JsonNull) {
            val millis = (value as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
            if (millis <= 0 || millis > 300_000) return null
        }
    }
    val detail = buildString {
        append(description)
        for (field in listOf("timeout", "block_until_ms", "is_background")) raw[field]?.let { append("\n$field: $it") }
    }
    return ApprovalSubject.Command(command, cwd = null, needs = null, reason = detail)
}

/** Bound the entire edit preview so the native dialog never hides a meaningful replacement. */
private fun editSubject(input: JsonObject, raw: JsonObject, folder: Path): ApprovalSubject? {
    if (!toolVariant(raw, "SearchReplace") || !raw.keys.all { it in EDIT_FIELDS }) return null
    val file = editPath(input, raw, folder) ?: return null
    val before = toolText(raw["old_string"], ApprovalLimits.PREVIEW) ?: return null
    val after = toolText(raw["new_string"], ApprovalLimits.PREVIEW) ?: return null
    val all = raw["replace_all"]
    if (all != null && all != JsonPrimitive(true) && all != JsonPrimitive(false)) return null
    val scope = when {
        before.isEmpty() -> "Create or replace file contents"
        all == JsonPrimitive(true) -> "Replace all matching occurrences"
        else -> "Replace one matching occurrence"
    }
    val preview = "$scope\nBefore:\n$before\nAfter:\n$after"
    if (preview.length > ApprovalLimits.PREVIEW) return null
    return ApprovalSubject.Edit("Edit", listOf(file), preview)
}

/** Full-file writes show their complete content; oversized writes require a richer future dialog. */
private fun writeSubject(input: JsonObject, raw: JsonObject, folder: Path): ApprovalSubject? {
    if (!toolVariant(raw, "Write") || !raw.keys.all { it in setOf("variant", "file_path", "content") }) return null
    val file = editPath(input, raw, folder) ?: return null
    val content = toolText(raw["content"], ApprovalLimits.PREVIEW) ?: return null
    val preview = "Create or replace file contents:\n$content"
    if (preview.length > ApprovalLimits.PREVIEW) return null
    return ApprovalSubject.Edit("Write", listOf(file), preview)
}

/** Resolve a verified raw path for display only; Hovel never executes or opens the requested file. */
private fun editPath(input: JsonObject, raw: JsonObject, folder: Path): Path? {
    if (input.keys != setOf("path") || !folder.isAbsolute) return null
    val path = toolText(raw["file_path"], 4_096)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: return null
    if (input["path"] != JsonPrimitive(path)) return null
    return runCatching { folder.resolve(path).normalize() }.getOrNull()
}

/**
 * Code may contain line breaks and tabs, but not hidden control characters, truncated text, or
 * format characters (bidi overrides, zero-width characters) that could make a dialog show other text.
 */
private fun toolText(value: JsonElement?, limit: Int): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.length <= limit && it.none { char -> char.isISOControl() && char != '\n' && char != '\t' } }
        ?.takeIf { it.codePoints().noneMatch(::isFormatCharacter) }

/** Grok serializes its tagged ToolInput; a supplied variant must agree with the canonical metadata. */
private fun toolVariant(raw: JsonObject, expected: String): Boolean =
    !raw.containsKey("variant") || raw["variant"] == JsonPrimitive(expected)

private val COMMAND_FIELDS = setOf("variant", "command", "description", "timeout", "is_background", "block_until_ms")
private val EDIT_FIELDS = setOf("variant", "file_path", "old_string", "new_string", "replace_all")
