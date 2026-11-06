package org.formulagen.wrapper.hovel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.formulagen.wrapper.App
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory

/**
 * Plays the Hovel launch and start screen, then opens [url] once the player steps inside, as
 * [HovelAndroidApp] does. There is no browser runtime to prepare on iOS, so it starts at Ready.
 */
@Composable
fun HovelIosApp(
    url: String,
    onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> },
    onWebViewCreated: (NativeWebView) -> Unit = {},
    /** Makes the WKWebView; `mediaWebViewFactory` adds the camera and microphone grant. */
    webViewFactory: (WebViewFactoryParam) -> NativeWebView = ::defaultWebViewFactory,
) {
    var entered by rememberSaveable { mutableStateOf(false) }
    // Like the desktop and Android hosts, Hovel talks to the host bridge, not the legacy native bridge.
    if (entered) {
        App(url, enableLegacyBridge = false, onWebViewCreated = onWebViewCreated, webViewFactory = webViewFactory, onPageLoaded = onPageLoaded)
    } else {
        HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
    }
}
