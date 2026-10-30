package org.formulagen.wrapper.progression.embedded

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.progression.GodotGame
import kotlin.test.*

class DesktopGameLauncherTest {
    private val ember = PetCompanion(PetAppearance("#ffde9c", "#ed8f40", "#856b4e", "#b3a185"), "10000000-0000-4000-8000-000000000001", "Ember")
    private val emberJson = """{"appearance":{"eye_color":"#ffde9c","face_color":"#ed8f40","frame_color":"#856b4e","shell_color":"#b3a185"},""" +
        """"id":"10000000-0000-4000-8000-000000000001","name":"Ember"}"""

    private fun decode(raw: String) = GameMethods.LAUNCH.decodeArgs(Json.parseToJsonElement(raw).jsonObject)

    /** Only an installed ID, and optionally a companion, cross the bridge; paths and command fields are rejected. */
    @Test fun validatesInstalledGameSelection() {
        assertEquals(GameLaunchArgs(GameId.HOVEL_COMPANIONS), decode("""{"gameId":"hovel-companions"}"""))
        assertEquals(GameLaunchArgs(GameId.EMBER_TOWER), decode("""{"gameId":"ember-tower"}"""))
        assertEquals(GameLaunchArgs(GameId.EMBER_OVERCLOCK), decode("""{"gameId":"ember-overclock"}"""))
        for (raw in listOf("{}", "{\"gameId\":\"../../other\"}", "{\"gameId\":1}",
            "{\"gameId\":\"hovel-companions\",\"path\":\"/tmp/game\"}")) {
            assertNull(decode(raw), raw)
        }
        assertEquals("""{"gameId":"ember-tower","started":true}""", GameMethods.LAUNCH.encodeResult(GameLaunchResult(GameId.EMBER_TOWER)).toString())
    }

    /** A companion is held to the pet's rules, and is left out rather than sent as null. */
    @Test fun aLaunchedCompanionFollowsThePetContract() {
        assertEquals(GameLaunchArgs(GameId.EMBER_TOWER, ember), decode("""{"companion":$emberJson,"gameId":"ember-tower"}"""))
        val upper = emberJson.replace("#ffde9c", "#FFDE9C")
        assertEquals("#FFDE9C", decode("""{"companion":$upper,"gameId":"ember-tower"}""")?.companion?.appearance?.eyeColor)
        for (bad in listOf(
            """{"companion":null,"gameId":"ember-tower"}""",
            """{"companion":${emberJson.replace("#ffde9c", "warm")},"gameId":"ember-tower"}""",
            """{"companion":${emberJson.replace("10000000-0000-4000-8000-000000000001", "../../escape")},"gameId":"ember-tower"}""",
            """{"companion":${emberJson.replace("\"Ember\"", "\" Ember\"")},"gameId":"ember-tower"}""",
            """{"companion":${emberJson.replace("\"name\"", "\"revision\":1,\"name\"")},"gameId":"ember-tower"}""",
            """{"companion":$emberJson}""",
        )) assertNull(decode(bad), bad)
    }

    /** Every game has a row; only the companions game presents companion screens, and Tower's two games share a project. */
    @Test fun theTableNamesEachGamesProjectAndScene() {
        assertEquals(GameId.entries.toSet(), DESKTOP_GAMES.keys)
        assertEquals(DesktopGame("ember-tower", "res://companions/main.tscn", presentation = true), DESKTOP_GAMES[GameId.HOVEL_COMPANIONS])
        assertEquals(DesktopGame("ember-tower", "res://game/main.tscn", presentation = false), DESKTOP_GAMES[GameId.EMBER_TOWER])
        assertEquals(DesktopGame("ember-overclock", "res://game/main.tscn", presentation = false), DESKTOP_GAMES[GameId.EMBER_OVERCLOCK])
        assertNull(desktopGame(emptyMap(), GameId.EMBER_TOWER))
        assertNull(desktopGame(mapOf(GAMES_ROOT_VARIABLE to " "), GameId.EMBER_TOWER))
        val root = Path.of("/games")
        assertEquals(GodotGame(root.resolve("ember-tower/scripts/godot.sh"), root.resolve("ember-overclock"), "res://game/main.tscn", false),
            desktopGame(mapOf(GAMES_ROOT_VARIABLE to "/games"), GameId.EMBER_OVERCLOCK))
        assertEquals(GodotGame.companions(root.resolve("ember-tower")), desktopGame(mapOf(GAMES_ROOT_VARIABLE to "/games"), GameId.HOVEL_COMPANIONS))
    }

    /** A missing game, engine or native bridge is a clear launch failure, before any window or game process starts. */
    @Test fun requiresTheGameItsEngineAndTheEmbedLibraryBeforeLaunching() {
        val root = Files.createTempDirectory("embedded-install")
        try {
            val games = root.resolve("games")
            val library = Files.createFile(root.resolve("libhovel_embed.dylib"))
            val installed = mapOf(GAMES_ROOT_VARIABLE to games.toString(), "HOVEL_EMBED_LIBRARY" to library.toString())
            for (id in GameId.entries) {
                assertContains(assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(emptyMap(), id) }.message.orEmpty(), "not installed")
            }
            Files.createDirectories(games.resolve("ember-tower/companions"))
            Files.createFile(games.resolve("ember-tower/companions/main.tscn"))
            // The scene alone is not enough: the engine script runs it.
            assertContains(assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(installed, GameId.HOVEL_COMPANIONS) }.message.orEmpty(), "not installed")
            Files.createDirectories(games.resolve("ember-tower/scripts"))
            Files.createFile(games.resolve("ember-tower/scripts/godot.sh"))
            assertEquals(desktopGame(installed, GameId.HOVEL_COMPANIONS), DesktopGameLauncher.installedGame(installed, GameId.HOVEL_COMPANIONS))
            // Each game needs its own scene.
            for (id in listOf(GameId.EMBER_TOWER, GameId.EMBER_OVERCLOCK)) {
                assertContains(assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(installed, id) }.message.orEmpty(), id.name)
            }
            Files.createDirectories(games.resolve("ember-overclock/game"))
            Files.createFile(games.resolve("ember-overclock/game/main.tscn"))
            assertEquals(games.resolve("ember-overclock"), DesktopGameLauncher.installedGame(installed, GameId.EMBER_OVERCLOCK).project)
            val game = mapOf(GAMES_ROOT_VARIABLE to games.toString())
            for (missing in listOf(game, game + ("HOVEL_EMBED_LIBRARY" to root.resolve("absent.dylib").toString()),
                game + ("HOVEL_EMBED_LIBRARY" to "libhovel_embed.dylib"))) {
                assertContains(assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(missing, GameId.HOVEL_COMPANIONS) }.message.orEmpty(), "build-embedded-macos.sh")
            }
        } finally { root.toFile().deleteRecursively() }
    }

    /**
     * Every game is embedded; only the companions game keeps a save; a companion's profile reaches only the launch
     * that names it, and neither variable leaks in from the app's own environment.
     */
    @Test fun eachLaunchGetsOnlyItsOwnSaveAndProfile() {
        val app = mapOf("HOME" to "/Users/player", "HOVEL_COMPANION_SAVE" to "/saves/app.json", "HOVEL_EMBER_PROFILE" to "/stale.json")
        val save = Path.of("/saves/save.json")
        val profile = Path.of("/profiles/ember.json")
        val companions = GodotGame.companions(Path.of("/games/ember-tower"))
        val tower = GodotGame(Path.of("/games/ember-tower/scripts/godot.sh"), Path.of("/games/ember-tower"), "res://game/main.tscn", false)
        assertEquals(mapOf("HOME" to "/Users/player", "HOVEL_EMBEDDED" to "1", "HOVEL_COMPANION_SAVE" to "/saves/save.json"),
            DesktopGameLauncher.launchEnvironment(app, companions, save, null))
        assertEquals(mapOf("HOME" to "/Users/player", "HOVEL_EMBEDDED" to "1", "HOVEL_COMPANION_SAVE" to "/saves/save.json",
            "HOVEL_EMBER_PROFILE" to "/profiles/ember.json"), DesktopGameLauncher.launchEnvironment(app, companions, save, profile))
        assertEquals(mapOf("HOME" to "/Users/player", "HOVEL_EMBEDDED" to "1", "HOVEL_EMBER_PROFILE" to "/profiles/ember.json"),
            DesktopGameLauncher.launchEnvironment(app, tower, save, profile))
        assertEquals(mapOf("HOME" to "/Users/player", "HOVEL_EMBEDDED" to "1"), DesktopGameLauncher.launchEnvironment(app, tower, save, null))
    }
}
