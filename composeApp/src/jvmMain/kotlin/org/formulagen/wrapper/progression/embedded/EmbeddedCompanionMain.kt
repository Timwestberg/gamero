package org.formulagen.wrapper.progression.embedded

import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.SwingUtilities
import org.formulagen.wrapper.bridge.GameId
import org.formulagen.wrapper.progression.SAVE_VARIABLE
import org.formulagen.wrapper.progression.launchEnvironment

/**
 * Development entry point: the companions game in one native window, with Compose above it. Every run gets a
 * disposable save unless `HOVEL_COMPANION_SAVE` names one.
 */
fun main() {
    require(System.getProperty("os.name").contains("Mac")) { "This embedding prototype requires macOS" }
    val environment = System.getenv()
    val game = DesktopGameLauncher.installedGame(environment, GameId.HOVEL_COMPANIONS)
    val save = environment[SAVE_VARIABLE]?.let(Path::of) ?: Files.createTempDirectory("hovel-embedded-").resolve("save.json")
    val launch = launchEnvironment(environment, game, save, profile = null)
    SwingUtilities.invokeLater { EmbeddedGameWindow(game, JFrame("Hovel · Embedded companion prototype"), launch).show() }
}
