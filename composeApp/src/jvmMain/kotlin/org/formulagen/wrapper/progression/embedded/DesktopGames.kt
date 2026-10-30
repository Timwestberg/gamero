package org.formulagen.wrapper.progression.embedded

import java.nio.file.Path
import org.formulagen.wrapper.bridge.GameId
import org.formulagen.wrapper.progression.GodotGame

/**
 * One game this Mac can launch: its Godot [project] folder under `HOVEL_GAMES_ROOT` and the [scene] it opens.
 * Only a [presentation] game opens the companion presentation socket and keeps a progression save.
 */
internal data class DesktopGame(val project: String, val scene: String, val presentation: Boolean)

/** The games this Mac can launch, under `HOVEL_GAMES_ROOT` (`scripts/dev-env.sh`: `<siblings>/game-platform/games`). */
internal val DESKTOP_GAMES: Map<GameId, DesktopGame> = mapOf(
    GameId.HOVEL_COMPANIONS to DesktopGame("ember-tower", GodotGame.COMPANIONS_SCENE, presentation = true),
    GameId.EMBER_TOWER to DesktopGame("ember-tower", "res://game/main.tscn", presentation = false),
    GameId.EMBER_OVERCLOCK to DesktopGame("ember-overclock", "res://game/main.tscn", presentation = false),
)

/**
 * The script every game runs through, under `HOVEL_GAMES_ROOT`: it resolves the pinned Godot 4.8-beta1 and refuses
 * any other version. Tower's is the only game script that does, so Overclock runs through it too.
 */
internal const val DESKTOP_GAME_ENGINE = "ember-tower/scripts/godot.sh"

/** The folder that holds the game projects, from native configuration only. */
internal const val GAMES_ROOT_VARIABLE = "HOVEL_GAMES_ROOT"

/**
 * [gameId]'s project, scene and engine under `HOVEL_GAMES_ROOT`, or null when it is unset. Nothing is checked on
 * disk; [DesktopGameLauncher.installedGame] does that before a launch.
 */
internal fun desktopGame(environment: Map<String, String>, gameId: GameId): GodotGame? {
    val root = environment[GAMES_ROOT_VARIABLE]?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
    val game = DESKTOP_GAMES.getValue(gameId)
    return GodotGame(root.resolve(DESKTOP_GAME_ENGINE), root.resolve(game.project), game.scene, game.presentation)
}

/** The file a `res://` scene path names in [GodotGame.project]. */
internal fun GodotGame.sceneFile(): Path = project.resolve(scene.removePrefix("res://"))
