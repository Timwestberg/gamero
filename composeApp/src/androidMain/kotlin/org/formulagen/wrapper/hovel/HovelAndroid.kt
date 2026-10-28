package org.formulagen.wrapper.hovel

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.net.URI
import org.formulagen.wrapper.App
import org.formulagen.wrapper.R
import org.formulagen.wrapper.bridge.AndroidHostBridge
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.CompanionMethods
import org.formulagen.wrapper.bridge.HostBridge
import org.formulagen.wrapper.bridge.androidHostCapabilities

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

/** Hovel's capability packs for the host bridge. Android has none of the desktop product capabilities, so their methods answer `unsupported`. */
internal fun hovelBridgePacks(): List<CapabilityPack> = listOf(
    CompanionMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ChatMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ProviderMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.PetMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.GameMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.WorkspaceMethods.pack(Capability.Unsupported),
)

/** Plays the Hovel launch and start screen, then opens [url] once the player steps inside. */
@Composable
fun HovelAndroidApp(url: String, onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> }) {
    var entered by rememberSaveable { mutableStateOf(false) }
    if (!entered) {
        HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true })
        return
    }
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
    val attached = remember { arrayOfNulls<AndroidHostBridge>(1) }
    DisposableEffect(Unit) { onDispose { attached[0]?.close() } }
    // Like the desktop host, Hovel talks to the host bridge, not the legacy native bridge.
    App(url, enableLegacyBridge = false, onPageLoaded = onPageLoaded, onWebViewCreated = { webView ->
        attached[0]?.close()
        attached[0] = AndroidHostBridge.attach(webView, HostBridge(HOVEL_PRODUCT, url, androidHostCapabilities(appVersion), hovelBridgePacks()))
    })
}
