package org.formulagen.wrapper.progression.embedded

import java.nio.file.Path
import javax.swing.JFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.GameCapability
import org.formulagen.wrapper.bridge.GameId
import org.formulagen.wrapper.bridge.GameLaunchResult
import org.formulagen.wrapper.bridge.PetCompanion
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.progression.GAMES_ROOT_VARIABLE
import org.formulagen.wrapper.progression.GodotGame
import org.formulagen.wrapper.progression.configuredCompanionSave
import org.formulagen.wrapper.progression.desktopGame
import org.formulagen.wrapper.progression.launchEnvironment

/**
 * The `games` capability. The page selects an installed game ID and, optionally, the companion to play as; native
 * configuration owns all paths (`DESKTOP_GAMES` under `HOVEL_GAMES_ROOT`). [support] is the app's support folder,
 * which holds the companions game's default progression save and the launched companions' profiles. When the
 * player returns from a launch that opened, every [observe] listener hears which game closed, so the router can
 * send `games.closed`.
 */
internal class DesktopGameLauncher(
    private val frame: JFrame,
    private val environment: Map<String, String>,
    private val support: Path,
) : GameCapability, AutoCloseable {
    private var active: EmbeddedGameWindow? = null
    private val closedListeners = GameClosedListeners()
    private val profiles = EmberProfileStore(support.resolve("companions").resolve("profiles"))

    /** The router has accepted only a known game ID and a valid companion; one game is open at a time. */
    override suspend fun launch(gameId: GameId, companion: PetCompanion?): GameLaunchResult = withContext(Dispatchers.Main) {
        if (active != null) throw BridgeBusyException()
        check(System.getProperty("os.name").contains("Mac")) { "Native adventures require macOS" }
        // The page sees only `failed`, so each refusal names the missing piece in the log.
        val game = logged { installedGame(environment, gameId) }
        val save = logged {
            if (game.presentation) {
                (configuredCompanionSave(environment) ?: support.resolve("companions").resolve("save.json"))
                    .also { java.nio.file.Files.createDirectories(it.parent) }
            } else null
        }
        val profile = companion?.let { logged { profiles.save(it) } }
        closedListeners.track(gameId) { returned ->
            val launch = EmbeddedGameWindow(game, frame, launchEnvironment(environment, game, save, profile)) {
                active = null
                returned()
            }
            active = launch
            // A failed show closes the surface, whose return then reports nothing: the page gets `failed`.
            try { launch.show() }
            catch (error: Exception) { launch.close(); throw error }
        }
        GameLaunchResult(gameId)
    }

    override fun observe(onClosed: (GameId) -> Unit): AutoCloseable = closedListeners.observe(onClosed)

    /** The caller closes the launch surface on the AWT event thread. */
    override fun close() { active?.close(); active = null }

    /** Runs [step], logging a missing install or a failed profile write before the page gets its plain `failed`. */
    private fun <T> logged(step: () -> T): T = try { step() } catch (error: Exception) {
        log(LogLevel.Warn, "games", error.message ?: error.toString())
        throw error
    }

    companion object {
        /**
         * [gameId]'s project, scene and engine, and the native layer bridge, must all exist before any window
         * changes: loading a missing bridge later fails on the UI thread, where it would end the app.
         */
        internal fun installedGame(environment: Map<String, String>, gameId: GameId): GodotGame {
            val game = desktopGame(environment, gameId)
            check(game != null && game.sceneFile().toFile().isFile && game.engine.toFile().isFile) {
                "Game ${gameId.name} is not installed: $GAMES_ROOT_VARIABLE must be an absolute folder with its scene and engine script"
            }
            val library = environment["HOVEL_EMBED_LIBRARY"]?.let(Path::of)
            check(library != null && library.isAbsolute && library.toFile().isFile) {
                "The embedded game bridge is not built. Run scripts/build-embedded-macos.sh and set HOVEL_EMBED_LIBRARY."
            }
            return game
        }
    }
}
