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
import org.formulagen.wrapper.isAllowedWebUrl
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
 * The Hovel web app URL when this launch is for Hovel, else null. Release builds accept HTTPS only.
 * A [debuggable] build also accepts http on loopback, the shared [isAllowedWebUrl] rule, for local
 * play and `tools/android-smoke.sh`: `adb reverse` forwards the device's port to the developer's
 * machine, and the debug network security config allows cleartext to loopback only.
 */
internal fun hovelWebUrl(product: String?, webUrl: String?, debuggable: Boolean = false): String? {
    if (product?.trim() != "hovel") return null
    val uri = runCatching { URI(webUrl?.trim().orEmpty()) }.getOrNull() ?: return null
    if (!isAllowedWebUrl(uri.scheme, uri.host, uri.userInfo != null, uri.fragment != null)) return null
    if (!debuggable && !uri.scheme.equals("https", ignoreCase = true)) return null
    return uri.toASCIIString()
}

/**
 * Reads the `gamero_product` and `gamero_web_url` string resources. The launcher activity is
 * exported, so launch extras (`adb shell am start -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL …`)
 * override them only in debuggable builds; otherwise any app could point Hovel at its own page.
 */
internal fun hovelWebUrl(intent: Intent?, context: Context): String? {
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    val extras = intent.takeIf { debuggable }
    return hovelWebUrl(
        product = extras?.getStringExtra(PRODUCT_KEY) ?: context.getString(R.string.gamero_product),
        webUrl = extras?.getStringExtra(WEB_URL_KEY) ?: context.getString(R.string.gamero_web_url),
        debuggable = debuggable,
    )
}

/** The [AUTO_ENTER_KEY] launch extra, honored only in a debuggable build, as the URL extras are. */
internal fun hovelAutoEnter(debuggable: Boolean, extra: String?): Boolean = debuggable && autoEnterRequested(extra)

/** Reads [hovelAutoEnter]'s inputs from the launch intent and the app. */
internal fun hovelAutoEnter(intent: Intent?, context: Context): Boolean =
    hovelAutoEnter((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0, intent?.getStringExtra(AUTO_ENTER_KEY))

/** Hovel's capability packs for the host bridge. Android has no companion, so its methods answer `unsupported`. */
internal fun hovelBridgePacks(): List<CapabilityPack> = listOf(CompanionMethods.pack(Capability.Unsupported))

/** Plays the Hovel launch and start screen, then opens [url] once the player steps inside. */
@Composable
fun HovelAndroidApp(
    url: String,
    onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> },
    /** Steps inside without a tap, for `tools/android-smoke.sh`. Debug builds only (see [hovelAutoEnter]). */
    autoEnter: Boolean = false,
) {
    var entered by rememberSaveable { mutableStateOf(false) }
    if (!entered) {
        HovelLaunchScreen(HovelStartState.Ready(url), onContinue = { entered = true }, autoEnter = autoEnter)
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
