package org.formulagen.wrapper.progression.embedded

import java.nio.file.Files
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.PetAppearance
import org.formulagen.wrapper.bridge.PetCompanion
import kotlin.test.*

class EmberProfileStoreTest {
    private val wren = PetCompanion(PetAppearance("#53E7F3", "#ed8f40", "#856b4e", "#5C6B73"), "74A0000B-0000-4000-8000-000000000001", "Wren")

    /** The keys `ember.profile.schema.json` requires, every one of which a game may read. */
    private val required = setOf("schema", "schema_version", "revision", "ember_id", "owner_account_id", "name", "rig", "parts",
        "palettes", "decals", "title", "cosmetic_unlocks")

    private fun <T> withStore(block: (EmberProfileStore) -> T): T {
        val root = Files.createTempDirectory("ember-profiles")
        try { return block(EmberProfileStore(root.resolve("companions/profiles"))) }
        finally { root.toFile().deleteRecursively() }
    }

    /** One EmberProfile v1 per companion, named by its id, with the companion's name and lower-case colors by channel. */
    @Test fun writesTheCompanionsProfile() = withStore { store ->
        val path = store.save(wren)
        assertEquals(store.folder.resolve("74a0000b-0000-4000-8000-000000000001.json"), path)
        val profile = Json.parseToJsonElement(Files.readString(path)).jsonObject
        assertEquals(required, profile.keys)
        assertEquals(buildJsonObject {
            put("ember_id", "74a0000b-0000-4000-8000-000000000001")
            put("name", "Wren")
            putJsonObject("palettes") { put("shell", "#5c6b73"); put("frame", "#856b4e"); put("face", "#ed8f40"); put("eyes", "#53e7f3") }
            put("schema", "ember.profile")
            put("schema_version", 1)
            put("revision", 0)
            put("owner_account_id", JsonNull)
            put("rig", "ember_robot_v1")
            putJsonObject("parts") {}
            putJsonArray("decals") {}
            put("title", JsonNull)
            putJsonArray("cosmetic_unlocks") {}
        }, profile)
        assertEquals(listOf(path), Files.list(store.folder).use { it.toList() }, "no temporary file is left behind")
    }

    /** A later launch of the same companion replaces its profile; another companion gets its own. */
    @Test fun replacesAProfileAndKeepsOthers() = withStore { store ->
        val first = store.save(wren)
        val renamed = store.save(wren.copy(name = "Wren II", appearance = wren.appearance.copy(eyeColor = "#ffffff")))
        assertEquals(first, renamed)
        val profile = Json.parseToJsonElement(Files.readString(renamed)).jsonObject
        assertEquals("Wren II", profile.getValue("name").jsonPrimitive.content)
        assertEquals("#ffffff", profile.getValue("palettes").jsonObject.getValue("eyes").jsonPrimitive.content)
        val other = store.save(wren.copy(id = "74a0000b-0000-4000-8000-000000000002"))
        assertNotEquals(first, other)
        assertEquals(2, Files.list(store.folder).use { it.count() })
    }

    /** The file name is a checked id, never a path the page chose; a bad companion writes nothing. */
    @Test fun refusesAnInvalidCompanionBeforeWriting() = withStore { store ->
        for (bad in listOf(wren.copy(id = "../../escape"), wren.copy(id = "74a0000b-0000-4000-8000-000000000001/x"),
            wren.copy(name = " Wren"), wren.copy(appearance = wren.appearance.copy(faceColor = "amber")))) {
            assertFailsWith<IllegalArgumentException> { store.save(bad) }
        }
        assertFalse(Files.exists(store.folder))
    }
}
