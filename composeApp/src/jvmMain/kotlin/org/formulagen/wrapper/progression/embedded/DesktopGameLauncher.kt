package org.formulagen.wrapper.progression.embedded

import java.nio.file.Path
import javax.swing.JFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/** The page selects an installed game ID; native configuration owns all paths. */
internal class DesktopGameLauncher(
    private val frame: JFrame,
    private val environment: Map<String, String>,
    private val returned: () -> Unit,
) : AutoCloseable {
    private var active: EmbeddedCompanionWindow? = null

    /** Accept only the configured companion prototype, never page-supplied commands. */
    suspend fun dispatch(method: String, args: JsonObject): JsonObject = withContext(Dispatchers.Main) {
        require(method == "games.launch" && accepts(args)) { "Unknown installed game" }
        check(active == null) { "An adventure is already open" }
        check(System.getProperty("os.name").contains("Mac")) { "Native adventures require macOS" }
        val project = try { installedGame(environment) } catch (error: IllegalStateException) {
            // The v1 bridge reports only a generic failure to the page, so name the missing piece here.
            log(LogLevel.Warn, "games", error.message ?: "Companion game is not installed")
            throw error
        }
        val save = environment["HOVEL_COMPANION_SAVE"] ?: Path.of(System.getProperty("user.home"),
            "Library", "Application Support", "Gamero", "companions", "save.json").toString()
        Path.of(save).parent.toFile().mkdirs()
        val launch = EmbeddedCompanionWindow(frame, environment + ("HOVEL_COMPANION_SAVE" to save)) {
            active = null
            returned()
        }
        active = launch
        try { launch.show() }
        catch (error: Exception) { launch.close(); throw error }
        buildJsonObject { put("started", true); put("gameId", GAME_ID) }
    }

    /** The caller closes the launch surface on the AWT event thread. */
    override fun close() { active?.close(); active = null }

    companion object {
        const val GAME_ID = "hovel-companions"

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

        /** Reject unknown IDs and extra fields at the native trust boundary. */
        internal fun accepts(args: JsonObject): Boolean = args.keys == setOf("gameId") &&
            args["gameId"] == JsonPrimitive(GAME_ID)
    }
}
