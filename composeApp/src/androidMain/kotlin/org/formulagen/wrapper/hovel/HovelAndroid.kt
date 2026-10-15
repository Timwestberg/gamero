package org.formulagen.wrapper.hovel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.net.URI
import org.formulagen.wrapper.App
import org.formulagen.wrapper.R

/** Launch extra or string resource that selects the Hovel product, as GAMERO_PRODUCT does on desktop. */
internal const val PRODUCT_KEY = "GAMERO_PRODUCT"

/** Launch extra or string resource with the Hovel web app URL, as GAMERO_WEB_URL does on desktop. */
internal const val WEB_URL_KEY = "GAMERO_WEB_URL"

/**
 * The Hovel web app URL when this launch is for Hovel, else null. Only HTTPS is accepted: the app
 * doesn't allow cleartext traffic, and localhost on a device isn't the developer's machine.
 */
internal fun hovelWebUrl(product: String?, webUrl: String?): String? {
    if (product?.trim() != "hovel") return null
    val uri = runCatching { URI(webUrl?.trim().orEmpty()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) return null
    return uri.toASCIIString()
}

/**
 * Reads the `gamero_product` and `gamero_web_url` string resources. The launcher activity is
 * exported, so launch extras (`adb shell am start -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL …`)
 * override them only in debuggable builds; otherwise any app could point Hovel at its own page.
 */
internal fun hovelWebUrl(intent: Intent?, context: Context): String? {
    val extras = intent.takeIf { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    return hovelWebUrl(
        product = extras?.getStringExtra(PRODUCT_KEY) ?: context.getString(R.string.gamero_product),
        webUrl = extras?.getStringExtra(WEB_URL_KEY) ?: context.getString(R.string.gamero_web_url),
    )
}

/** Plays the Hovel launch and start screen, then opens [url] once the player steps inside. */
@Composable
fun HovelAndroidApp(url: String, onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> }) {
    var entered by rememberSaveable { mutableStateOf(false) }
    // Like the desktop host, Hovel runs without the legacy native bridge.
    if (entered) App(url, enableLegacyBridge = false, onPageLoaded = onPageLoaded) else HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
}
