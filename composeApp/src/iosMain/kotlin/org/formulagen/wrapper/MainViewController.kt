package org.formulagen.wrapper

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import org.formulagen.wrapper.bridge.HostBridge
import org.formulagen.wrapper.bridge.IosHostBridge
import org.formulagen.wrapper.bridge.iosHostCapabilities
import org.formulagen.wrapper.bridge.iosHovelBridgePacks
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import platform.Foundation.NSBundle

private const val TAG = "MainViewController"

fun MainViewController() = ComposeUIViewController {
    val url = remember { resolveIosWebUrl() }
    if (url == null) {
        LaunchedEffect(Unit) { log(LogLevel.Error, TAG, "GAMERO_WEB_URL is missing or invalid") }
        MaterialTheme {
            Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.safeDrawingPadding().padding(24.dp))
        }
    } else {
        val attached = remember { arrayOfNulls<IosHostBridge>(1) }
        DisposableEffect(Unit) { onDispose { attached[0]?.close() } }
        App(url, onWebViewCreated = { webView ->
            attached[0]?.close()
            // Only Hovel adds its packs, so another product's host answers unknown-method for companion.*.
            val products = if (isIosHovelProduct()) iosHovelBridgePacks() else emptyList()
            attached[0] = IosHostBridge(webView, HostBridge(if (isIosHovelProduct()) HOVEL_PRODUCT else GAMERO_PRODUCT_NAME, url, iosHostCapabilities(appVersion()), products))
        }, onPageLoaded = { loadedUrl, title ->
            // Smoke-test marker; mirrors desktop's GAMERO_JCEF_INITIALIZED.
            log(LogLevel.Info, TAG, "GAMERO_PAGE_LOADED origin=${originOf(loadedUrl)} title=$title")
        })
    }
}

private fun appVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() } ?: "dev"
