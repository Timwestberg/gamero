package org.formulagen.wrapper.bridge

import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureState
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWindowFeatures
import platform.darwin.NSObject

private const val TAG = "IosMediaGrant"

/** The kinds that one WebKit capture type asks for. */
internal fun mediaKindsOf(type: WKMediaCaptureType): Set<MediaKind> = when (type) {
    WKMediaCaptureType.WKMediaCaptureTypeCamera -> setOf(MediaKind.Camera)
    WKMediaCaptureType.WKMediaCaptureTypeMicrophone -> setOf(MediaKind.Microphone)
    WKMediaCaptureType.WKMediaCaptureTypeCameraAndMicrophone -> setOf(MediaKind.Camera, MediaKind.Microphone)
}

/** [MediaGrant]'s answer for one capture request. With no trusted [origin] (a page that is not Hovel), every request is denied. */
internal fun mediaCaptureDecision(origin: TrustedOrigin?, frameOrigin: String, mainFrame: Boolean, type: WKMediaCaptureType): MediaGrant.Decision =
    origin?.let { MediaGrant.decide(it, frameOrigin, mainFrame, mediaKindsOf(type)) } ?: MediaGrant.Decision.Deny(MediaGrant.Reason.OtherOrigin)

/** True while [webView] captures the camera or microphone, muted or not: the page is in a meeting. */
internal fun inMeeting(webView: WKWebView): Boolean =
    webView.microphoneCaptureState != WKMediaCaptureState.WKMediaCaptureStateNone || webView.cameraCaptureState != WKMediaCaptureState.WKMediaCaptureStateNone

/**
 * The WKWebView's UI delegate. It answers camera and microphone requests with [MediaGrant], from the
 * requesting frame's facts, as the bridge reads them. Grant means iOS then shows its own prompt once.
 * It also answers a link that asks for a new window with [newWindowTarget]: the web view has one window, so
 * without an answer a `target="_blank"` link does nothing. [openOutside] takes a link that leaves the app.
 * WKWebView keeps its UI delegate weakly, so the caller must keep this object.
 */
class MediaUIDelegate(
    private val origin: TrustedOrigin?,
    private val openOutside: suspend (String) -> Unit = ::openOutsideApp,
) : NSObject(), WKUIDelegateProtocol {
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Never makes a second web view. Hovel's own page loads in this one; another site opens outside the app. */
    override fun webView(
        webView: WKWebView,
        createWebViewWithConfiguration: WKWebViewConfiguration,
        forNavigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures,
    ): WKWebView? {
        val url = forNavigationAction.request.URL?.absoluteString
        when (newWindowTarget(origin, originOf(forNavigationAction.sourceFrame), url)) {
            NewWindowTarget.HERE -> webView.loadRequest(forNavigationAction.request)
            NewWindowTarget.OUTSIDE -> scope.launch {
                runCatching { openOutside(checkNotNull(url)) }.onFailure { log(LogLevel.Warn, TAG, "A link could not open outside the app", it) }
            }
            NewWindowTarget.IGNORE -> log(LogLevel.Info, TAG, "A new-window link was not opened")
        }
        return null
    }

    override fun webView(
        webView: WKWebView,
        requestMediaCapturePermissionForOrigin: WKSecurityOrigin,
        initiatedByFrame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: (WKPermissionDecision) -> Unit,
    ) {
        val frameOrigin = originOf(initiatedByFrame)
        when (val decision = mediaCaptureDecision(origin, frameOrigin, initiatedByFrame.mainFrame, type)) {
            is MediaGrant.Decision.Grant -> decisionHandler(WKPermissionDecision.WKPermissionDecisionGrant)
            is MediaGrant.Decision.Deny -> {
                log(LogLevel.Info, TAG, "Media request from $frameOrigin denied: ${decision.reason}")
                decisionHandler(WKPermissionDecision.WKPermissionDecisionDeny)
            }
        }
    }
}

/**
 * The WebView factory for `App`: the library's WKWebView with [delegate] as its UI delegate. It turns on
 * inline playback, which the library also does, because without it a camera shows black (WebKit bug 220184).
 * With [edgeToEdge] the page fills the screen ([keepViewportFit], [layOutToTheEdges]).
 */
fun mediaWebViewFactory(delegate: MediaUIDelegate, appBound: Boolean = false, edgeToEdge: Boolean = false): (WebViewFactoryParam) -> NativeWebView = { param ->
    param.config.allowsInlineMediaPlayback = true
    param.config.limitsNavigationsToAppBoundDomains = appBound
    if (edgeToEdge) param.config.keepViewportFit()
    defaultWebViewFactory(param).apply {
        UIDelegate = delegate
        if (edgeToEdge) layOutToTheEdges()
    }
}

/**
 * Lays the page out on the whole screen. Left to itself the scroll view insets the page by the safe area, and WebKit
 * sizes the page's layout to what is left: the page ends above the home indicator with a bar below it, however the
 * page styles itself. WebKit lifts the inset by itself for a `viewport-fit=cover` page, but only when it sees that
 * at the first layout; here the tag arrives late ([KEEP_VIEWPORT_FIT_SCRIPT]), and the layout size it computed
 * before stays. Saying it outright leaves nothing to that timing. The page keeps clear of the notch and the home
 * indicator itself, with `env(safe-area-inset-*)`.
 */
internal fun WKWebView.layOutToTheEdges() {
    scrollView.contentInsetAdjustmentBehavior = UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
}

/**
 * Runs in every page before its own scripts. The web view library sets zoom by adding a second
 * `<meta name="viewport">` after the page's (with zoom on or off alike), and the last one wins: the page's
 * `viewport-fit=cover` is lost, and iOS then answers zero for `env(safe-area-inset-*)`, so nothing the page does for
 * the notch and the home indicator takes effect. This puts `viewport-fit=cover` back on the tag that wins whenever
 * the page asked for it, and leaves the rest of that tag, the library's zoom setting included, as it is.
 */
internal const val KEEP_VIEWPORT_FIT_SCRIPT = """
(() => {
  const keep = () => {
    const all = [...document.querySelectorAll('meta[name="viewport"]')];
    const last = all[all.length - 1];
    if (last && !/viewport-fit/.test(last.content) && all.some((meta) => /viewport-fit\s*=\s*cover/.test(meta.content))) {
      last.content = last.content + ', viewport-fit=cover';
    }
  };
  const watchHead = () => {
    if (!document.head) return false;
    new MutationObserver(keep).observe(document.head, { childList: true });
    keep();
    return true;
  };
  if (!watchHead()) {
    const wait = new MutationObserver(() => { if (watchHead()) wait.disconnect(); });
    wait.observe(document.documentElement, { childList: true });
  }
})();
"""

/** Adds [KEEP_VIEWPORT_FIT_SCRIPT] to the main frame of every page this configuration loads. */
internal fun WKWebViewConfiguration.keepViewportFit() {
    userContentController.addUserScript(
        WKUserScript(source = KEEP_VIEWPORT_FIT_SCRIPT, injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart, forMainFrameOnly = true),
    )
}
