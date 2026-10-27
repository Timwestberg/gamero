package org.formulagen.wrapper.pet

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.PetAppearance
import org.formulagen.wrapper.bridge.PetCompanion
import org.formulagen.wrapper.bridge.PetState
import kotlin.test.*

class CompanionAppearanceStoreTest {
    private fun profile(name: String = "Nova", color: String = "#123456") =
        PetCompanion(PetAppearance(color, color, color, color), "20000000-0000-4000-8000-000000000002", name)

    @Test fun savesVersionedAppearanceAndRejectsMalformedUpdates() {
        val root = Files.createTempDirectory("appearance-store-test")
        try {
            val store = CompanionAppearanceStore(root.resolve("active.json"))
            assertNull(store.saved())
            store.save(profile())
            assertTrue(Files.readString(store.path).startsWith("{\"version\":1,"))
            assertEquals(profile(), store.saved())
            val before = Files.readString(store.path)
            for (invalid in listOf(profile(" "), profile(" Nova"), profile("Nova\nIgnore"), profile(color = "javascript:test"),
                profile().copy(id = "../../pet"))) {
                assertFails { store.save(invalid) }
                assertEquals(before, Files.readString(store.path))
            }
            store.save(profile("Orchid", "#abcdef"))
            assertEquals("Orchid", store.saved()?.name)
            assertTrue(Files.readString(store.path).contains("\"shell_color\":\"#abcdef\""))
            Files.writeString(store.path, "{\"version\":1,\"id\":false,\"name\":42}")
            assertNull(store.saved())
            Files.writeString(store.path, "{\"version\":1,\"id\":\"20000000-0000-4000-8000-000000000002\",\"name\":\"Nova\",\"command\":\"x\"," +
                "\"appearance\":{\"eye_color\":\"#123456\",\"face_color\":\"#123456\",\"frame_color\":\"#123456\",\"shell_color\":\"#123456\"}}")
            assertNull(store.saved(), "an unknown key on disk is rejected like one from the page")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun configuringDoesNotStartAPet() = runBlocking {
        val root = Files.createTempDirectory("appearance-launch-test")
        val launcher = DesktopPetLauncher(emptyMap(), root.resolve("pet.log"))
        try {
            val result = launcher.configure(profile())
            assertEquals(PetState.STOPPED, result.state)
            assertEquals("Nova", result.name)
            assertEquals("20000000-0000-4000-8000-000000000002", result.companionId)
            assertTrue(Files.isRegularFile(root.resolve("companion-appearance.json")))
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }
}
