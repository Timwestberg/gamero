package org.formulagen.wrapper.progression.embedded

import javax.swing.SwingUtilities
import org.formulagen.wrapper.bridge.GameId

/** Development entry point: the companions game in one native window, with Compose above it. */
fun main() {
    require(System.getProperty("os.name").contains("Mac")) { "This embedding prototype requires macOS" }
    val game = DesktopGameLauncher.installedGame(System.getenv(), GameId.HOVEL_COMPANIONS)
    SwingUtilities.invokeLater { EmbeddedGameWindow(game).show() }
}
