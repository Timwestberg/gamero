package org.formulagen.wrapper

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import org.formulagen.wrapper.hovel.HovelIosApp
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

private const val TAG = "MainViewController"

fun MainViewController() = ComposeUIViewController {
    val url = remember { resolveIosWebUrl() }
    val product = remember { resolveIosProduct() }
    if (url == null) {
        LaunchedEffect(Unit) { log(LogLevel.Error, TAG, "GAMERO_WEB_URL is missing or invalid") }
        MaterialTheme {
            Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.safeDrawingPadding().padding(24.dp))
        }
    } else if (product == "hovel") {
        HovelIosApp(url, onPageLoaded = ::logPageLoaded)
    } else {
        App(url, onPageLoaded = ::logPageLoaded)
    }
}

/** Smoke-test marker; mirrors desktop's GAMERO_JCEF_INITIALIZED. */
private fun logPageLoaded(loadedUrl: String, title: String?) {
    log(LogLevel.Info, TAG, "GAMERO_PAGE_LOADED origin=${originOf(loadedUrl)} title=$title")
}
