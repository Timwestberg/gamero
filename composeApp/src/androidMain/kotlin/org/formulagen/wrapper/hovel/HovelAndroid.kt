package org.formulagen.wrapper.hovel

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.multiplatform.webview.web.PlatformWebViewParams
import java.net.URI
import org.formulagen.wrapper.App
import org.formulagen.wrapper.R
import org.formulagen.wrapper.bridge.AndroidHostBridge
import org.formulagen.wrapper.bridge.AndroidMediaRequests
import org.formulagen.wrapper.bridge.ApprovalCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceCapability
import org.formulagen.wrapper.bridge.ApprovalDeviceMethods
import org.formulagen.wrapper.bridge.ApprovalMethods
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.CompanionMethods
import org.formulagen.wrapper.bridge.HostBridge
import org.formulagen.wrapper.bridge.MediaChromeClient
import org.formulagen.wrapper.bridge.TrustedOrigin
import org.formulagen.wrapper.bridge.androidHostCapabilities
import org.formulagen.wrapper.links.rememberLinkedPage
import org.formulagen.wrapper.phone.androidPhoneApprovals

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

/**
 * Hovel's capability packs for the host bridge. Android has none of the desktop product capabilities, so their methods
 * answer `unsupported`. Phone approvals are Android's own: [approvals] shows an approval natively and [devices]
 * registers this phone as an approval device (both `PhoneApprovals` in the app); without them they answer `unsupported`.
 */
internal fun hovelBridgePacks(approvals: ApprovalCapability? = null, devices: ApprovalDeviceCapability? = null): List<CapabilityPack> = listOf(
    CompanionMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ProviderMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.PetMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.GameMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ProjectMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ProjectWorkspaceMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.VoiceInputMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.VoicePlaybackMethods.pack(Capability.Unsupported),
    // Reminders stay in the page on Android; only the iPhone schedules them itself.
    org.formulagen.wrapper.bridge.NotificationMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ConversationVoiceMethods.pack(Capability.Unsupported),
    ApprovalMethods.pack(approvals?.let { Capability.Supported(it) } ?: Capability.Unsupported),
    ApprovalDeviceMethods.pack(devices?.let { Capability.Supported(it) } ?: Capability.Unsupported),
    // Only a Mac confirms a phone.
    org.formulagen.wrapper.bridge.PhonePairingMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.ListsLocalMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.HostContractMethods.pack(Capability.Unsupported),
)

/**
 * Opens [url] as soon as the app starts, as the iPhone host does. The page draws its own launch screen, the mark
 * and a loading bar, and then the sign-in page or the person's page. There is no start screen to tap.
 *
 * [firstLink] is the link that started the app, when one did: a sign-in code's link from the camera. It becomes the
 * first page when it is one of the web app's own (`incomingLinkTarget`), and so does each link that arrives later.
 */
@Composable
fun HovelAndroidApp(url: String, onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> }, firstLink: String? = null) {
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
    }
    val attached = remember { arrayOfNulls<AndroidHostBridge>(1) }
    val webViews = remember { arrayOfNulls<WebView>(1) }
    // One approval device for the activity's lifetime, so a dialog outlives a page reload.
    val approvals = remember(context) { context.activity()?.let { androidPhoneApprovals(it, url) } }
    // Camera and microphone go only to the trusted page's main frame (MediaGrant), after Android's own prompt.
    val media = remember(context, url) {
        AndroidMediaRequests(TrustedOrigin(url), isGranted = { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
    }
    val prompt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { media.answered() }
    DisposableEffect(media, prompt) {
        media.ask = { permissions -> prompt.launch(permissions) }
        onDispose { media.close() }
    }
    val webViewParams = remember(media) { PlatformWebViewParams(chromeClient = MediaChromeClient(media) { webViews[0]?.url }) }
    DisposableEffect(Unit) {
        onDispose {
            attached[0]?.close()
            approvals?.close()
        }
    }
    val page = rememberLinkedPage(url, firstLink, reopen = { link -> webViews[0]?.loadUrl(link) })
    // Like the desktop host, Hovel talks to the host bridge, not the legacy native bridge.
    App(page.value, enableLegacyBridge = false, onPageLoaded = onPageLoaded, platformWebViewParams = webViewParams, onWebViewCreated = { webView ->
        webViews[0] = webView
        attached[0]?.close()
        // a registration an earlier page prepared is never finished by this one
        approvals?.invalidatePending()
        val packs = hovelBridgePacks(approvals, approvals)
        attached[0] = AndroidHostBridge.attach(webView, HostBridge(HOVEL_PRODUCT, url, androidHostCapabilities(appVersion), packs))
    })
}

/** The activity this context belongs to, which shows the approval dialogs. */
private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
