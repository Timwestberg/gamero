package org.formulagen.wrapper

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.multiplatform.webview.web.PlatformWebViewParams
import org.formulagen.wrapper.bridge.MediaChromeClient
import org.formulagen.wrapper.hovel.HovelAndroidApp
import org.formulagen.wrapper.hovel.hovelWebUrl
import org.formulagen.wrapper.links.IncomingLinks
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val hovelUrl = hovelWebUrl(intent, this)
        // A sign-in code's link that started the app is its first page. Not when Android rebuilds the activity:
        // the person has moved on from that link by then.
        val firstLink = intent?.dataString.takeIf { savedInstanceState == null }
        setContent {
            // Only Hovel's trusted page may get the camera and microphone, so the other page is denied both.
            if (hovelUrl != null) {
                HovelAndroidApp(hovelUrl, onPageLoaded = ::logPageLoaded, firstLink = firstLink)
            } else {
                App(platformWebViewParams = remember { PlatformWebViewParams(chromeClient = MediaChromeClient(null) { null }) })
            }
        }
    }

    /** A sign-in code's link, handed over by the camera while the app runs. The web view takes it from there. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(IncomingLinks::offer)
    }
}

/** Smoke-test marker, the same on every platform. */
private fun logPageLoaded(url: String, title: String?) {
    log(LogLevel.Info, "MainActivity", SmokeMarkers.pageLoaded(url, title))
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}