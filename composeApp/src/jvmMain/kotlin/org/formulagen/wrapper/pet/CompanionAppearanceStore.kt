package org.formulagen.wrapper.pet

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.PetCompanion
import org.formulagen.wrapper.bridge.PetMethods
import org.formulagen.wrapper.bridge.bridgeJson

/** Own the fixed active-appearance file, never a path supplied by the browser. */
internal class CompanionAppearanceStore(val path: Path) {
    /** Reject malformed profiles before writing any bytes or touching a running pet. */
    fun save(companion: PetCompanion) {
        require(PetMethods.isValid(companion)) { "Invalid companion profile" }
        val profile = bridgeJson.encodeToJsonElement(PetCompanion.serializer(), companion).jsonObject
        val document = buildJsonObject { put("version", 1); profile.forEach { (key, value) -> put(key, value) } }
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".companion-", ".json")
        try {
            Files.writeString(temp, document.toString())
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    /** The saved companion, checked by the same contract as page requests; corrupt disk data must not break Show or Hide. */
    fun saved(): PetCompanion? = runCatching {
        if (Files.size(path) > 4096) return null
        val document = Json.parseToJsonElement(Files.readString(path)).jsonObject
        require(document["version"] == JsonPrimitive(1)) { "Unsupported companion version" }
        bridgeJson.decodeFromJsonElement(PetCompanion.serializer(), JsonObject(document - "version")).takeIf(PetMethods::isValid)
    }.getOrNull()
}
