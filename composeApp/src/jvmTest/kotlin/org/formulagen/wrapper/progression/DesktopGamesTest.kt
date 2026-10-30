package org.formulagen.wrapper.progression

import java.nio.file.Path
import org.formulagen.wrapper.bridge.GameId
import kotlin.test.*

class DesktopGamesTest {
    private val root = Path.of("/games")
    private val companions = GodotGame(root.resolve("ember-tower/scripts/godot.sh"), root.resolve("ember-tower"),
        "res://companions/main.tscn", presentation = true)
    private val tower = GodotGame(root.resolve("ember-tower/scripts/godot.sh"), root.resolve("ember-tower"),
        "res://game/main.tscn", presentation = false)
    private val overclock = GodotGame(root.resolve("ember-tower/scripts/godot.sh"), root.resolve("ember-overclock"),
        "res://game/main.tscn", presentation = false)

    /** Every game has a row; only the companions game presents companion screens; Tower's two games share a project. */
    @Test fun theTableNamesEachGamesProjectAndScene() {
        assertEquals(GameId.entries.toSet(), DESKTOP_GAMES.keys)
        val games = mapOf(GAMES_ROOT_VARIABLE to "/games")
        assertEquals(companions, desktopGame(games, GameId.HOVEL_COMPANIONS))
        assertEquals(tower, desktopGame(games, GameId.EMBER_TOWER))
        assertEquals(overclock, desktopGame(games, GameId.EMBER_OVERCLOCK))
        assertEquals(root.resolve("ember-overclock/game/main.tscn"), overclock.sceneFile())
    }

    /** No root, a blank one or a relative one names no game: each game runs in its own project folder. */
    @Test fun aGameNeedsAnAbsoluteRoot() {
        for (environment in listOf(emptyMap(), mapOf(GAMES_ROOT_VARIABLE to " "), mapOf(GAMES_ROOT_VARIABLE to "games"))) {
            assertNull(desktopGame(environment, GameId.EMBER_TOWER), environment.toString())
        }
    }

    /**
     * Every game is embedded; only the companions game keeps a save; a companion's profile reaches only the launch
     * that names it; nothing the launch decides comes in from the app's own environment.
     */
    @Test fun eachLaunchGetsOnlyItsOwnSaveAndProfile() {
        val app = mapOf("HOME" to "/Users/player", SAVE_VARIABLE to "/saves/app.json", PROFILE_VARIABLE to "/stale.json",
            PRESENTATION_PORT_VARIABLE to "1", PRESENTATION_SESSION_VARIABLE to "stale")
        val save = Path.of("/saves/save.json")
        val profile = Path.of("/profiles/ember.json")
        val embedded = mapOf("HOME" to "/Users/player", "HOVEL_EMBEDDED" to "1")
        assertEquals(embedded + (SAVE_VARIABLE to "/saves/save.json"), launchEnvironment(app, companions, save, null))
        assertEquals(embedded + (SAVE_VARIABLE to "/saves/save.json") + (PROFILE_VARIABLE to "/profiles/ember.json"),
            launchEnvironment(app, companions, save, profile))
        assertEquals(embedded + (PROFILE_VARIABLE to "/profiles/ember.json"), launchEnvironment(app, tower, null, profile))
        assertEquals(embedded, launchEnvironment(app, overclock, null, null))
        assertEquals(mapOf("HOME" to "/Users/player"), launchEnvironment(app, companions, null, null, embedded = false))
        assertFailsWith<IllegalArgumentException> { launchEnvironment(app, tower, save, null) }
    }

    /**
     * The child's environment replaces what it would inherit: a stale save, profile or session in the app's own
     * environment never reaches a game, and only a presentation game gets this session's port.
     */
    @Test fun aChildInheritsNothingTheLaunchDecides() {
        val inherited = mapOf("PATH" to "/usr/bin", SAVE_VARIABLE to "/saves/app.json", PROFILE_VARIABLE to "/stale.json",
            PRESENTATION_PORT_VARIABLE to "1", PRESENTATION_SESSION_VARIABLE to "stale", "HOVEL_EMBEDDED" to "0")
        val session = mapOf(PRESENTATION_PORT_VARIABLE to "4242", PRESENTATION_SESSION_VARIABLE to "fresh")
        val towerLaunch = launchEnvironment(inherited, tower, null, null)
        assertEquals(mapOf("PATH" to "/usr/bin", "HOVEL_EMBEDDED" to "1"),
            DesktopGameHost.childEnvironment(inherited, towerLaunch, emptyMap()))
        val companionsLaunch = launchEnvironment(inherited, companions, Path.of("/saves/save.json"), Path.of("/p.json"))
        assertEquals(mapOf("PATH" to "/usr/bin", "HOVEL_EMBEDDED" to "1", SAVE_VARIABLE to "/saves/save.json",
            PROFILE_VARIABLE to "/p.json") + session, DesktopGameHost.childEnvironment(inherited, companionsLaunch, session))
        // Nor can the supplied environment name a session: only the host's own counts.
        val claimed = DesktopGameHost.childEnvironment(emptyMap(), mapOf("PATH" to "/usr/bin") + session, emptyMap())
        assertEquals(mapOf("PATH" to "/usr/bin"), claimed)
    }

    /** The presentation test hooks reach the companions game only, so nothing replaces Tower's or Overclock's scene. */
    @Test fun onlyAPresentationGameTakesTheTestHooks() {
        val hooks = mapOf("HOVEL_PRESENTATION_SCENARIO" to "res://companions/tests/scenario.gd", "HOVEL_PRESENTATION_HEADLESS" to "1")
        val engine = "/games/ember-tower/scripts/godot.sh"
        assertEquals(listOf(engine, "--embedded", "--path", "/games/ember-tower", "res://companions/main.tscn"),
            DesktopGameHost.gameCommand(companions, listOf("--embedded"), emptyMap()))
        assertEquals(listOf(engine, "--headless", "--path", "/games/ember-tower", "-s", "res://companions/tests/scenario.gd"),
            DesktopGameHost.gameCommand(companions, emptyList(), hooks))
        assertEquals(listOf(engine, "--path", "/games/ember-tower", "res://game/main.tscn"),
            DesktopGameHost.gameCommand(tower, emptyList(), hooks))
        assertEquals(listOf(engine, "--path", "/games/ember-overclock", "res://game/main.tscn"),
            DesktopGameHost.gameCommand(overclock, emptyList(), hooks))
    }
}
