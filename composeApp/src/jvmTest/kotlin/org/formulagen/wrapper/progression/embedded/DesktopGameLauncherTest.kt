package org.formulagen.wrapper.progression.embedded

import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopGameLauncherTest {
    /** Only the installed ID crosses the bridge; paths and command fields are rejected. */
    @Test fun validatesInstalledGameSelection() {
        assertTrue(DesktopGameLauncher.accepts(buildJsonObject { put("gameId", "hovel-companions") }))
        for (raw in listOf("{}", "{\"gameId\":\"../../other\"}", "{\"gameId\":1}",
            "{\"gameId\":\"hovel-companions\",\"path\":\"/tmp/game\"}")) {
            assertFalse(DesktopGameLauncher.accepts(Json.parseToJsonElement(raw).jsonObject))
        }
    }

    /** A missing native bridge is a clear launch failure, before any window or game process starts. */
    @Test fun requiresTheGameAndItsEmbedLibraryBeforeLaunching() {
        val root = java.nio.file.Files.createTempDirectory("embedded-install")
        try {
            val project = root.resolve("ember-tower")
            java.nio.file.Files.createDirectories(project.resolve("companions"))
            java.nio.file.Files.createFile(project.resolve("companions/main.tscn"))
            val library = java.nio.file.Files.createFile(root.resolve("libhovel_embed.dylib"))
            val game = mapOf("HOVEL_GAME_PROJECT" to project.toString())
            assertEquals("Companion game is not installed", assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(emptyMap()) }.message)
            for (missing in listOf(game, game + ("HOVEL_EMBED_LIBRARY" to root.resolve("absent.dylib").toString()),
                game + ("HOVEL_EMBED_LIBRARY" to "libhovel_embed.dylib"))) {
                assertContains(assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(missing) }.message.orEmpty(), "build-embedded-macos.sh")
            }
            assertEquals(project, DesktopGameLauncher.installedGame(game + ("HOVEL_EMBED_LIBRARY" to library.toString())))
        } finally { root.toFile().deleteRecursively() }
    }
}
