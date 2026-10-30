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
import org.formulagen.wrapper.progression.GodotGame

/**
 * The `games` capability. The page selects an installed game ID and, optionally, the companion to play as; native
 * configuration owns all paths ([DESKTOP_GAMES] under `HOVEL_GAMES_ROOT`). [support] is the app's support folder,
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
        val game = try { installedGame(environment, gameId) } catch (error: IllegalStateException) {
            // The page sees only `failed`, so name the missing piece here.
            log(LogLevel.Warn, "games", error.message ?: "${gameId.name} is not installed")
            throw error
        }
        val save = environment[SAVE_VARIABLE]?.let(Path::of) ?: support.resolve("companions").resolve("save.json")
        if (game.presentation) save.parent.toFile().mkdirs()
        val launchEnvironment = launchEnvironment(environment, game, save, companion?.let(profiles::save))
        closedListeners.track(gameId) { returned ->
            val launch = EmbeddedGameWindow(game, frame, launchEnvironment) {
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

    companion object {
        /** The companions game's progression save; only that game gets one. */
        internal const val SAVE_VARIABLE = "HOVEL_COMPANION_SAVE"

        /** The launched companion's `EmberProfile`; a game without it plays its own Ember. */
        internal const val PROFILE_VARIABLE = "HOVEL_EMBER_PROFILE"

        /**
         * [gameId]'s project, scene and engine, and the native layer bridge, must all exist before any window
         * changes: loading a missing bridge later fails on the UI thread, where it would end the app.
         */
        internal fun installedGame(environment: Map<String, String>, gameId: GameId): GodotGame {
            val game = desktopGame(environment, gameId)
            check(game != null && game.sceneFile().toFile().isFile && game.engine.toFile().isFile) {
                "Game ${gameId.name} is not installed under $GAMES_ROOT_VARIABLE"
            }
            val library = environment["HOVEL_EMBED_LIBRARY"]?.let(Path::of)
            check(library != null && library.isAbsolute && library.toFile().isFile) {
                "The embedded game bridge is not built. Run scripts/build-embedded-macos.sh and set HOVEL_EMBED_LIBRARY."
            }
            return game
        }

        /**
         * What [game] starts with besides what the host sets: every game is embedded; only a presentation game gets
         * [save]; a game launched as a companion gets [profile], and any other plays its own Ember, so neither
         * variable leaks in from the app's own environment.
         */
        internal fun launchEnvironment(environment: Map<String, String>, game: GodotGame, save: Path, profile: Path?): Map<String, String> =
            environment - setOf(SAVE_VARIABLE, PROFILE_VARIABLE) + buildMap {
                put("HOVEL_EMBEDDED", "1")
                if (game.presentation) put(SAVE_VARIABLE, save.toString())
                if (profile != null) put(PROFILE_VARIABLE, profile.toString())
            }
    }
}
