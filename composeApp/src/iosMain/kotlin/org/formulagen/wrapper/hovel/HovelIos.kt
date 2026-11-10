package org.formulagen.wrapper.hovel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.formulagen.wrapper.App
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory

/** The web app's boot colour, shown behind the page while the keyboard's edge moves. */
private val HOVEL_PAGE_INK = Color(0xFF071013)

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
        // The page ends where the keyboard begins. Left under the keyboard, the page stays the height of the screen
        // and iOS only pans it far enough to show the caret: a header that sticks to the top slides off the screen,
        // and the bar the field sits in is half covered by the keyboard's own bar. Resized, the page's top and
        // bottom are the visible top and bottom, so both stay where the page puts them.
        Box(Modifier.fillMaxSize().background(HOVEL_PAGE_INK)) {
            App(url, enableLegacyBridge = false, onWebViewCreated = onWebViewCreated, webViewFactory = webViewFactory, onPageLoaded = onPageLoaded, modifier = Modifier.imePadding())
        }
    } else {
        HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
    }
}
