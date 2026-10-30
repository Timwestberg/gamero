package org.formulagen.wrapper.bridge

import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens `link.open` URLs in the system browser through [Desktop.browse]. A host
 * without a desktop browser (headless, or a Linux desktop AWT can't drive)
 * throws, so the call answers `failed`. [browse] is replaceable for tests.
 */
internal class DesktopLinkOpener(private val browse: (URI) -> Unit = ::browseWithDesktop) : LinkOpener {
    override suspend fun open(url: String) {
        val uri = URI(url)
        // Desktop.browse blocks until the browser is asked, so keep it off the router's threads.
        withContext(Dispatchers.IO) { browse(uri) }
    }
}

private fun browseWithDesktop(uri: URI) {
    check(Desktop.isDesktopSupported()) { "java.awt.Desktop is not supported on this host" }
    val desktop = Desktop.getDesktop()
    check(desktop.isSupported(Desktop.Action.BROWSE)) { "java.awt.Desktop cannot browse on this host" }
    desktop.browse(uri)
}
