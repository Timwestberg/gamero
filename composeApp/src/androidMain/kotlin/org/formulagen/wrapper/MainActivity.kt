package org.formulagen.wrapper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.formulagen.wrapper.hovel.HovelAndroidApp
import org.formulagen.wrapper.hovel.hovelAutoEnter
import org.formulagen.wrapper.hovel.hovelWebUrl
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val hovelUrl = hovelWebUrl(intent, this)
        val autoEnter = hovelAutoEnter(intent, this)
        setContent {
            if (hovelUrl != null) HovelAndroidApp(hovelUrl, onPageLoaded = ::logPageLoaded, autoEnter = autoEnter) else App()
        }
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