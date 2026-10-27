package org.formulagen.wrapper.progression.embedded

import java.nio.file.Path
import javax.swing.JFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.GameCapability
import org.formulagen.wrapper.bridge.GameId
import org.formulagen.wrapper.bridge.GameLaunchResult
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * The `games.launch` capability. The page selects an installed game ID; native configuration owns
 * all paths. [support] is the app's support folder, which holds the default progression save.
 */
internal class DesktopGameLauncher(
    private val frame: JFrame,
    private val environment: Map<String, String>,
    private val support: Path,
    private val returned: () -> Unit,
) : GameCapability, AutoCloseable {
    private var active: EmbeddedCompanionWindow? = null

    /** The router has accepted only a known game ID; one adventure is open at a time. */
    override suspend fun launch(gameId: GameId): GameLaunchResult = withContext(Dispatchers.Main) {
        if (active != null) throw BridgeBusyException()
        check(System.getProperty("os.name").contains("Mac")) { "Native adventures require macOS" }
        try { installedGame(environment) } catch (error: IllegalStateException) {
            // The page sees only `failed`, so name the missing piece here.
            log(LogLevel.Warn, "games", error.message ?: "Companion game is not installed")
            throw error
        }
        val save = environment["HOVEL_COMPANION_SAVE"] ?: support.resolve("companions").resolve("save.json").toString()
        Path.of(save).parent.toFile().mkdirs()
        val launch = EmbeddedCompanionWindow(frame, environment + ("HOVEL_COMPANION_SAVE" to save)) {
            active = null
            returned()
        }
        active = launch
        try { launch.show() }
        catch (error: Exception) { launch.close(); throw error }
        GameLaunchResult(gameId)
    }

    /** The caller closes the launch surface on the AWT event thread. */
    override fun close() { active?.close(); active = null }

    companion object {
        /**
         * The game project and the native layer bridge must both exist before any window changes:
         * loading a missing bridge later fails on the UI thread, where it would end the app.
         */
        internal fun installedGame(environment: Map<String, String>): Path {
            val project = environment["HOVEL_GAME_PROJECT"]?.let(Path::of)
            check(project != null && project.resolve("companions/main.tscn").toFile().isFile) { "Companion game is not installed" }
            val library = environment["HOVEL_EMBED_LIBRARY"]?.let(Path::of)
            check(library != null && library.isAbsolute && library.toFile().isFile) {
                "The embedded game bridge is not built. Run scripts/build-embedded-macos.sh and set HOVEL_EMBED_LIBRARY."
            }
            return project
        }
    }
}
