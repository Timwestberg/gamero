package org.formulagen.wrapper.hovel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import org.formulagen.wrapper.App

/**
 * Plays the Hovel launch and start screen, then opens [url] once the player steps inside, as
 * [HovelAndroidApp] does. There is no browser runtime to prepare on iOS, so it starts at Ready.
 */
@Composable
fun HovelIosApp(url: String, onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> }) {
    var entered by rememberSaveable { mutableStateOf(false) }
    // Like the desktop and Android hosts, Hovel runs without the legacy native bridge.
    if (entered) {
        App(url, enableLegacyBridge = false, onPageLoaded = onPageLoaded)
    } else {
        HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
    }
}
