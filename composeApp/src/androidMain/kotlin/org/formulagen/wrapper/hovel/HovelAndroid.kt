package org.formulagen.wrapper.hovel

import android.content.Intent
import android.content.res.Resources
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
 * The Hovel web app URL when this launch is for Hovel, else null.
 * Launch extras win over the `gamero_product` and `gamero_web_url` string resources, so a debug
 * launch can pick them with `adb shell am start -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL …`.
 */
internal fun hovelWebUrl(product: String?, webUrl: String?): String? {
    if (product?.trim() != "hovel") return null
    val uri = runCatching { URI(webUrl?.trim().orEmpty()) }.getOrNull() ?: return null
    if (uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) return null
    val loopback = uri.host.lowercase().removePrefix("[").removeSuffix("]") in setOf("localhost", "127.0.0.1", "::1")
    return when (uri.scheme?.lowercase()) {
        "https" -> uri.toASCIIString()
        "http" -> if (loopback) uri.toASCIIString() else null
        else -> null
    }
}

internal fun hovelWebUrl(intent: Intent?, resources: Resources): String? = hovelWebUrl(
    product = intent?.getStringExtra(PRODUCT_KEY) ?: resources.getString(R.string.gamero_product),
    webUrl = intent?.getStringExtra(WEB_URL_KEY) ?: resources.getString(R.string.gamero_web_url),
)

/** Plays the Hovel launch and start screen, then opens [url] once the player steps inside. */
@Composable
fun HovelAndroidApp(url: String) {
    var entered by rememberSaveable { mutableStateOf(false) }
    if (entered) App(url) else HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
}
