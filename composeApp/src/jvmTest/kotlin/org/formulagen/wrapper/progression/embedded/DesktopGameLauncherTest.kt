package org.formulagen.wrapper.progression.embedded

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.progression.GAMES_ROOT_VARIABLE
import org.formulagen.wrapper.progression.desktopGame
import kotlin.test.*

class DesktopGameLauncherTest {
    private val ember = PetCompanion(
        PetAppearance("#ffde9c", "#ed8f40", "#856b4e", "#b3a185"), "10000000-0000-4000-8000-000000000001", "Ember",
    )
    private val emberJson = bridgeJson.encodeToString(PetCompanion.serializer(), ember)

    private fun decode(raw: String) = GameMethods.LAUNCH.decodeArgs(Json.parseToJsonElement(raw).jsonObject)

    private fun launch(companion: String) = """{"companion":$companion,"gameId":"ember-tower"}"""

    /** Only an installed ID, and optionally a companion, cross the bridge; paths and command fields are rejected. */
    @Test fun validatesInstalledGameSelection() {
        assertEquals(GameLaunchArgs(GameId.HOVEL_COMPANIONS), decode("""{"gameId":"hovel-companions"}"""))
        assertEquals(GameLaunchArgs(GameId.EMBER_TOWER), decode("""{"gameId":"ember-tower"}"""))
        assertEquals(GameLaunchArgs(GameId.EMBER_OVERCLOCK), decode("""{"gameId":"ember-overclock"}"""))
        for (raw in listOf("{}", "{\"gameId\":\"../../other\"}", "{\"gameId\":1}",
            "{\"gameId\":\"hovel-companions\",\"path\":\"/tmp/game\"}")) {
            assertNull(decode(raw), raw)
        }
        val started = GameMethods.LAUNCH.encodeResult(GameLaunchResult(GameId.EMBER_TOWER)).toString()
        assertEquals("""{"gameId":"ember-tower","started":true}""", started)
    }

    /** A companion is held to the pet's rules, and is left out rather than sent as null. */
    @Test fun aLaunchedCompanionFollowsThePetContract() {
        assertEquals(GameLaunchArgs(GameId.EMBER_TOWER, ember), decode(launch(emberJson)))
        val upper = decode(launch(emberJson.replace("#ffde9c", "#FFDE9C")))
        assertEquals("#FFDE9C", upper?.companion?.appearance?.eyeColor, "the contract takes either case")
        for (bad in listOf(
            launch("null"),
            launch(emberJson.replace("#ffde9c", "warm")),
            launch(emberJson.replace("10000000-0000-4000-8000-000000000001", "../../escape")),
            launch(emberJson.replace("\"Ember\"", "\" Ember\"")),
            launch(emberJson.replace("\"name\"", "\"revision\":1,\"name\"")),
            """{"companion":$emberJson}""",
        )) assertNull(decode(bad), bad)
    }

    /** A missing game, engine or native bridge, or a relative root, fails before any window or game process starts. */
    @Test fun requiresTheGameItsEngineAndTheEmbedLibraryBeforeLaunching() {
        val root = Files.createTempDirectory("embedded-install")
        fun refusal(environment: Map<String, String>, id: GameId) =
            assertFailsWith<IllegalStateException> { DesktopGameLauncher.installedGame(environment, id) }.message.orEmpty()
        try {
            val games = root.resolve("games")
            val library = Files.createFile(root.resolve("libhovel_embed.dylib"))
            val installed = mapOf(GAMES_ROOT_VARIABLE to games.toString(), "HOVEL_EMBED_LIBRARY" to library.toString())
            for (id in GameId.entries) assertContains(refusal(emptyMap(), id), "not installed")
            Files.createDirectories(games.resolve("ember-tower/companions"))
            Files.createFile(games.resolve("ember-tower/companions/main.tscn"))
            // The scene alone is not enough: the engine script runs it.
            assertContains(refusal(installed, GameId.HOVEL_COMPANIONS), "not installed")
            Files.createDirectories(games.resolve("ember-tower/scripts"))
            Files.createFile(games.resolve("ember-tower/scripts/godot.sh"))
            val companions = DesktopGameLauncher.installedGame(installed, GameId.HOVEL_COMPANIONS)
            assertEquals(desktopGame(installed, GameId.HOVEL_COMPANIONS), companions)
            // A relative root would name another folder once the game runs in its project, even one that names
            // this install from here.
            val relative = Path.of("").toAbsolutePath().relativize(games)
            assertTrue(relative.resolve("ember-tower/companions/main.tscn").toFile().isFile)
            assertContains(refusal(installed + (GAMES_ROOT_VARIABLE to relative.toString()), GameId.HOVEL_COMPANIONS), "absolute")
            // Each game needs its own scene.
            for (id in listOf(GameId.EMBER_TOWER, GameId.EMBER_OVERCLOCK)) assertContains(refusal(installed, id), id.name)
            Files.createDirectories(games.resolve("ember-overclock/game"))
            Files.createFile(games.resolve("ember-overclock/game/main.tscn"))
            val overclock = DesktopGameLauncher.installedGame(installed, GameId.EMBER_OVERCLOCK)
            assertEquals(games.resolve("ember-overclock"), overclock.project)
            val game = mapOf(GAMES_ROOT_VARIABLE to games.toString())
            for (missing in listOf(game, game + ("HOVEL_EMBED_LIBRARY" to root.resolve("absent.dylib").toString()),
                game + ("HOVEL_EMBED_LIBRARY" to "libhovel_embed.dylib"))) {
                assertContains(refusal(missing, GameId.HOVEL_COMPANIONS), "build-embedded-macos.sh")
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
