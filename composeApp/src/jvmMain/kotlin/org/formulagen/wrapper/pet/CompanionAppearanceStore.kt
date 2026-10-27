package org.formulagen.wrapper.pet

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.serialization.json.*

/** Own the fixed active-appearance file, never a path supplied by the browser. */
internal class CompanionAppearanceStore(val path: Path) {
    private val colors = setOf("shell_color", "frame_color", "face_color", "eye_color")

    /** Reject malformed profiles before writing any bytes or touching a running pet. */
    fun save(args: JsonObject): JsonObject {
        require(args.keys == setOf("companion")) { "Expected one companion" }
        val companion = args["companion"] as? JsonObject ?: error("Expected a companion profile")
        validate(companion)
        val document = buildJsonObject { put("version", 1); companion.forEach { (key, value) -> put(key, value) } }
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".companion-", ".json")
        try {
            Files.writeString(temp, document.toString())
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return document
    }

    /** Use the same contract for browser requests and untrusted saved data. */
    private fun validate(companion: JsonObject) {
        require(companion.keys == setOf("id", "name", "appearance")) { "Unknown companion field" }
        val id = companion["id"] as? JsonPrimitive
        require(id?.isString == true && runCatching { UUID.fromString(id.content).toString() == id.content.lowercase() }.getOrDefault(false)) { "Invalid companion ID" }
        val name = companion["name"] as? JsonPrimitive
        require(name?.isString == true && name.content == name.content.trim() && name.content.length in 1..40 &&
            name.content.none { it.code < 32 || it.code == 127 }) { "Invalid companion name" }
        val appearance = companion["appearance"] as? JsonObject ?: error("Expected appearance")
        require(appearance.keys == colors && appearance.values.all {
            it is JsonPrimitive && it.isString && it.content.matches(Regex("#[0-9a-fA-F]{6}"))
        }) { "Invalid companion colors" }
    }

    /** Read only display identity; corrupt disk data must not break Show or Hide. */
    fun identity(): JsonObject? = runCatching {
        if (Files.size(path) > 4096) return null
        val document = Json.parseToJsonElement(Files.readString(path)).jsonObject
        require(document["version"] == JsonPrimitive(1)) { "Unsupported companion version" }
        validate(JsonObject(document - "version"))
        document
    }.getOrNull()
}
