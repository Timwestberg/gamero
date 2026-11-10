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
import org.formulagen.wrapper.bridge.IosVoice
import org.formulagen.wrapper.bridge.forgettingOnLock
import org.formulagen.wrapper.bridge.inMeeting
import org.formulagen.wrapper.bridge.iosNotifications
import org.formulagen.wrapper.bridge.IosHostBridge
import org.formulagen.wrapper.bridge.MediaUIDelegate
import org.formulagen.wrapper.bridge.TrustedOrigin
import org.formulagen.wrapper.bridge.iosHostCapabilities
import org.formulagen.wrapper.bridge.iosHovelBridgePacks
import org.formulagen.wrapper.bridge.mediaWebViewFactory
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelIosApp
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.lists.IosListsReplica
import org.formulagen.wrapper.phone.iosPhoneApprovals
import platform.Foundation.NSBundle
import platform.Foundation.NSURL

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
        val voice = remember { if (hovel) IosVoice() else null }
        // Reminders and Focus phase ends, scheduled on the phone. Only Hovel has them.
        val notifications = remember { if (hovel) iosNotifications() else null }
        val attached = remember { arrayOfNulls<IosHostBridge>(1) }
        // One approval device for the app's lifetime, so a dialog outlives a page reload.
        val lists = remember { if (hovel) IosListsReplica(url) else null }
        // A phone whose Lists account locked or changed hands must not show the last account's reminders.
        val listsStore = remember { lists?.forgettingOnLock { notifications?.clear() } }
        val approvals = remember { if (hovel) iosPhoneApprovals(url) else null }
        DisposableEffect(Unit) {
            onDispose {
                attached[0]?.close()
                approvals?.close()
                voice?.close()
            }
        }
        val attach = { webView: NativeWebView ->
            attached[0]?.close()
            // a registration an earlier page prepared is never finished by this one
            approvals?.invalidatePending()
            lists?.invalidatePending()
            voice?.invalidate()
            // Dictation and the companion's voice leave the audio session alone while this page is in a meeting.
            voice?.audio?.meeting = { inMeeting(webView) }
            // Only Hovel adds its packs, so another product's host answers unknown-method for companion.*.
            val products = approvals?.let { iosHovelBridgePacks(it, it, checkNotNull(listsStore), voice?.input, voice?.playback, notifications) } ?: emptyList()
            attached[0] = IosHostBridge(webView, HostBridge(if (hovel) HOVEL_PRODUCT else GAMERO_PRODUCT_NAME, url, iosHostCapabilities(appVersion()), products), onDocumentEnded = { voice?.invalidate() })
        }
        // Only Hovel's page gets the camera and microphone. WKWebView keeps its UI delegate weakly, so it lives here.
        val media = remember { MediaUIDelegate(if (hovel) TrustedOrigin(url) else null) }
        val boundDomains = NSBundle.mainBundle.objectForInfoDictionaryKey("WKAppBoundDomains") as? List<*>
        val appBound = hovel && boundDomains?.contains(NSURL.URLWithString(url)?.host) == true
        // Hovel's pages declare viewport-fit=cover and pad themselves for the notch and the home indicator.
        val webViewFactory = remember(media, appBound) { mediaWebViewFactory(media, appBound, edgeToEdge = hovel) }
        if (hovel) HovelIosApp(url, onPageLoaded = ::logPageLoaded, onWebViewCreated = attach, webViewFactory = webViewFactory)
        else App(url, onWebViewCreated = attach, webViewFactory = webViewFactory, onPageLoaded = ::logPageLoaded)
    }
}

/** Smoke-test marker; mirrors desktop's GAMERO_JCEF_INITIALIZED. */
private fun logPageLoaded(loadedUrl: String, title: String?) {
    log(LogLevel.Info, TAG, "GAMERO_PAGE_LOADED origin=${originOf(loadedUrl)} title=$title")
}

private fun appVersion(): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String)?.takeIf { it.isNotBlank() } ?: "dev"
