package org.formulagen.wrapper.bridge

import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import platform.WebKit.WKFrameInfo
import platform.WebKit.WKMediaCaptureType
import platform.WebKit.WKPermissionDecision
import platform.WebKit.WKSecurityOrigin
import platform.WebKit.WKUIDelegateProtocol
import platform.WebKit.WKWebView
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

/**
 * The WKWebView's UI delegate. It answers camera and microphone requests with [MediaGrant], from the
 * requesting frame's facts, as the bridge reads them. Grant means iOS then shows its own prompt once.
 * WKWebView keeps its UI delegate weakly, so the caller must keep this object.
 */
class MediaUIDelegate(private val origin: TrustedOrigin?) : NSObject(), WKUIDelegateProtocol {
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
 */
fun mediaWebViewFactory(delegate: MediaUIDelegate): (WebViewFactoryParam) -> NativeWebView = { param ->
    param.config.allowsInlineMediaPlayback = true
    defaultWebViewFactory(param).apply { UIDelegate = delegate }
}
