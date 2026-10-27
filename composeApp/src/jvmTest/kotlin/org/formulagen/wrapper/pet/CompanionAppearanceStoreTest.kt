package org.formulagen.wrapper.pet

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class CompanionAppearanceStoreTest {
    private fun profile(name: String = "Nova", color: String = "#123456") = buildJsonObject {
        put("id", "20000000-0000-4000-8000-000000000002"); put("name", name)
        putJsonObject("appearance") {
            for (key in listOf("shell_color", "frame_color", "face_color", "eye_color")) put(key, color)
        }
    }
    @Test fun savesVersionedAppearanceAndRejectsMalformedUpdates() {
        val root = Files.createTempDirectory("appearance-store-test")
        try {
            val store = CompanionAppearanceStore(root.resolve("active.json"))
            assertNull(store.identity())
            val valid = buildJsonObject { put("companion", profile()) }
            store.save(valid)
            assertEquals(JsonPrimitive(1), store.identity()?.get("version"))
            val before = Files.readString(store.path)
            for (invalid in listOf(
                buildJsonObject { put("companion", profile(" ")) },
                buildJsonObject { put("companion", profile(color = "javascript:test")) },
                buildJsonObject { put("companion", profile()); put("path", "/tmp/forbidden") },
                buildJsonObject { put("companion", JsonObject(profile() + ("command" to JsonPrimitive("test")))) }
            )) {
                assertFails { store.save(invalid) }
                assertEquals(before, Files.readString(store.path))
            }
            store.save(buildJsonObject { put("companion", profile("Orchid", "#abcdef")) })
            assertEquals("Orchid", store.identity()?.get("name")?.jsonPrimitive?.content)
            Files.writeString(store.path, "{\"version\":1,\"id\":false,\"name\":42}")
            assertNull(store.identity())
        } finally { root.toFile().deleteRecursively() }
    }
    @Test fun configuringDoesNotStartAPet() = runBlocking {
        val root = Files.createTempDirectory("appearance-launch-test")
        val launcher = DesktopPetLauncher(emptyMap(), root.resolve("pet.log"))
        try {
            val result = launcher.dispatch("pet.configure", buildJsonObject { put("companion", profile()) })
            assertEquals("stopped", result["state"]?.jsonPrimitive?.content)
            assertEquals("Nova", result["name"]?.jsonPrimitive?.content)
            assertTrue(Files.isRegularFile(root.resolve("companion-appearance.json")))
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }
}
