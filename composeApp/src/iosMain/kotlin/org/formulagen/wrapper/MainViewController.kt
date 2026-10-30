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
import com.multiplatform.webview.web.NativeWebView
import org.formulagen.wrapper.bridge.HostBridge
import org.formulagen.wrapper.bridge.IosHostBridge
import org.formulagen.wrapper.bridge.iosHostCapabilities
import org.formulagen.wrapper.bridge.iosHovelBridgePacks
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelIosApp
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.phone.iosPhoneApprovals
import platform.Foundation.NSBundle

private const val TAG = "MainViewController"

fun MainViewController() = ComposeUIViewController {
    val url = remember { resolveIosWebUrl() }
    val product = remember { resolveIosProduct() }
    if (url == null) {
        LaunchedEffect(Unit) { log(LogLevel.Error, TAG, "GAMERO_WEB_URL is missing or invalid") }
        MaterialTheme {
            Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.safeDrawingPadding().padding(24.dp))
        }
    } else {
        val hovel = product == "hovel"
        val attached = remember { arrayOfNulls<IosHostBridge>(1) }
        // One approval device for the app's lifetime, so a dialog outlives a page reload.
        val approvals = remember { if (hovel) iosPhoneApprovals(url) else null }
        DisposableEffect(Unit) {
            onDispose {
                attached[0]?.close()
                approvals?.close()
            }
        }
        val attach = { webView: NativeWebView ->
            attached[0]?.close()
            // a registration an earlier page prepared is never finished by this one
            approvals?.invalidatePending()
            // Only Hovel adds its packs, so another product's host answers unknown-method for companion.*.
            val products = approvals?.let { iosHovelBridgePacks(it, it) } ?: emptyList()
            attached[0] = IosHostBridge(webView, HostBridge(if (hovel) HOVEL_PRODUCT else GAMERO_PRODUCT_NAME, url, iosHostCapabilities(appVersion()), products))
        }
        if (hovel) HovelIosApp(url, onPageLoaded = ::logPageLoaded, onWebViewCreated = attach)
        else App(url, onWebViewCreated = attach, onPageLoaded = ::logPageLoaded)
    }
}

/** Smoke-test marker; mirrors desktop's GAMERO_JCEF_INITIALIZED. */
private fun logPageLoaded(loadedUrl: String, title: String?) {
    log(LogLevel.Info, TAG, "GAMERO_PAGE_LOADED origin=${originOf(loadedUrl)} title=$title")
}

private fun appVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() } ?: "dev"
