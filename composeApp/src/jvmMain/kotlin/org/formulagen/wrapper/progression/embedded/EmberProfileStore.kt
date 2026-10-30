package org.formulagen.wrapper.progression.embedded

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.formulagen.wrapper.bridge.PetAppearance
import org.formulagen.wrapper.bridge.PetCompanion
import org.formulagen.wrapper.bridge.PetMethods

/**
 * An `EmberProfile` v1 (game-platform `ember-rogue-addons/contracts/ember.profile.schema.json`): what every Ember
 * game reads to know which robot it plays. Gamero writes one per launch with a companion; [palettes] holds the
 * companion's `#rrggbb` colors by channel (`shell`, `frame`, `face`, `eyes`), and empty [parts] means every slot's
 * default.
 */
@Serializable
internal data class EmberProfile(
    @SerialName("ember_id") val emberId: String,
    val name: String,
    val palettes: Map<String, String>,
    val schema: String = "ember.profile",
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val revision: Int = 0,
    @SerialName("owner_account_id") val ownerAccountId: String? = null,
    val rig: String = "ember_robot_v1",
    val parts: Map<String, String?> = emptyMap(),
    val decals: List<String> = emptyList(),
    val title: String? = null,
    @SerialName("cosmetic_unlocks") val cosmeticUnlocks: List<JsonObject> = emptyList(),
) {
    companion object {
        /** [companion]'s profile, its colors by channel. */
        fun of(companion: PetCompanion) = with(companion.appearance) {
            EmberProfile(companion.id, companion.name, mapOf("shell" to shellColor, "frame" to frameColor, "face" to faceColor, "eyes" to eyeColor))
        }
    }
}

/**
 * Owns the launched companions' profiles in [folder], one `<companion id>.json` each. The page names a companion,
 * never a path: the file name is the validated id.
 */
internal class EmberProfileStore(val folder: Path) {
    /** Every key the schema requires is written, `null`s included. */
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    /**
     * Writes [companion]'s profile atomically, as `CompanionAppearanceStore` does, and answers its path. The id and
     * colors are lower-cased first, as the account stores them, and then checked by the contract's rules.
     */
    fun save(companion: PetCompanion): Path {
        val lowered = with(companion.appearance) {
            companion.copy(id = companion.id.lowercase(), appearance = PetAppearance(
                eyeColor.lowercase(), faceColor.lowercase(), frameColor.lowercase(), shellColor.lowercase()))
        }
        require(PetMethods.isValid(lowered)) { "Invalid companion" }
        val profile = EmberProfile.of(lowered)
        val path = folder.resolve("${profile.emberId}.json")
        Files.createDirectories(folder)
        val temp = Files.createTempFile(folder, ".profile-", ".json")
        try {
            Files.writeString(temp, json.encodeToString(EmberProfile.serializer(), profile))
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
        return path
    }
}
