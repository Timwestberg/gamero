package org.formulagen.wrapper.progression

import java.nio.file.Path
import org.formulagen.wrapper.bridge.GameId

/**
 * A game a desktop host starts: the [engine] script that resolves the pinned Godot, the [project] it runs and the
 * [scene] it opens. Only a [presentation] game talks to the host over the companion presentation socket and keeps a
 * progression save. [desktopGame] builds one from [DESKTOP_GAMES]; tests may build one directly.
 */
data class GodotGame(val engine: Path, val project: Path, val scene: String, val presentation: Boolean) {
    /** The file the `res://` [scene] names in [project]. */
    fun sceneFile(): Path = project.resolve(scene.removePrefix("res://"))
}

/** One game this Mac can launch: its Godot [project] folder under `HOVEL_GAMES_ROOT`, its [scene], and [presentation]. */
internal data class DesktopGame(val project: String, val scene: String, val presentation: Boolean)

/** The games this Mac can launch, under `HOVEL_GAMES_ROOT` (`scripts/dev-env.sh`: `<siblings>/game-platform/games`). */
internal val DESKTOP_GAMES: Map<GameId, DesktopGame> = mapOf(
    GameId.HOVEL_COMPANIONS to DesktopGame("ember-tower", "res://companions/main.tscn", presentation = true),
    GameId.EMBER_TOWER to DesktopGame("ember-tower", "res://game/main.tscn", presentation = false),
    GameId.EMBER_OVERCLOCK to DesktopGame("ember-overclock", "res://game/main.tscn", presentation = false),
)

/**
 * The script every game runs through, under `HOVEL_GAMES_ROOT`: it resolves the pinned Godot 4.8-beta1 and refuses
 * any other version. Tower's is the only game script that does, so Overclock runs through it too.
 */
internal const val DESKTOP_GAME_ENGINE = "ember-tower/scripts/godot.sh"

/** The absolute folder that holds the game projects, from native configuration only. */
internal const val GAMES_ROOT_VARIABLE = "HOVEL_GAMES_ROOT"

/**
 * [gameId]'s engine, project and scene under `HOVEL_GAMES_ROOT`, or null when it is unset or not absolute: the game
 * runs in its project folder, where a relative path would name something else. Nothing is checked on disk; the
 * launcher's install check does that before a launch.
 */
internal fun desktopGame(environment: Map<String, String>, gameId: GameId): GodotGame? {
    val root = environment[GAMES_ROOT_VARIABLE]?.takeIf(String::isNotBlank)?.let(Path::of)?.takeIf(Path::isAbsolute) ?: return null
    val game = DESKTOP_GAMES.getValue(gameId)
    return GodotGame(root.resolve(DESKTOP_GAME_ENGINE), root.resolve(game.project), game.scene, game.presentation)
}

/** The companions game's progression save, which the game owns; only a presentation game gets one. */
internal const val SAVE_VARIABLE = "HOVEL_COMPANION_SAVE"

/** The launched companion's `EmberProfile` v1; a game without it plays its own Ember. */
internal const val PROFILE_VARIABLE = "HOVEL_EMBER_PROFILE"

/** The loopback port and session a presentation game connects with; the host sets them per launch. */
internal const val PRESENTATION_PORT_VARIABLE = "HOVEL_PRESENTATION_PORT"
internal const val PRESENTATION_SESSION_VARIABLE = "HOVEL_PRESENTATION_SESSION"
internal val PRESENTATION_VARIABLES = setOf(PRESENTATION_PORT_VARIABLE, PRESENTATION_SESSION_VARIABLE)

/** What a launch decides for its game; a child never inherits these from the app's own environment. */
internal val HOST_CONTROLLED_VARIABLES = setOf(SAVE_VARIABLE, PROFILE_VARIABLE) + PRESENTATION_VARIABLES

/**
 * The one place a launch's environment is built: [environment] (the app's) without the variables a launch decides,
 * then `HOVEL_EMBEDDED` for an embedded game, [save] for a presentation game that has one, and [profile] for a game
 * launched as a companion. The host adds the presentation session itself.
 */
internal fun launchEnvironment(
    environment: Map<String, String>,
    game: GodotGame,
    save: Path?,
    profile: Path?,
    embedded: Boolean = true,
): Map<String, String> {
    require(save == null || game.presentation) { "Only a presentation game keeps a progression save" }
    return environment - HOST_CONTROLLED_VARIABLES + buildMap {
        if (embedded) put("HOVEL_EMBEDDED", "1")
        if (save != null) put(SAVE_VARIABLE, save.toString())
        if (profile != null) put(PROFILE_VARIABLE, profile.toString())
    }
}
