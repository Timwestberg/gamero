package org.formulagen.wrapper.bridge

import android.Manifest
import android.webkit.PermissionRequest
import com.multiplatform.webview.web.AccompanistWebChromeClient
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/** One page request for media, without WebView types, so tests can drive [AndroidMediaRequests]. */
internal interface MediaPermissionRequest {
    /** The origin that asks, as WebView reports it. */
    val origin: String

    /** The `PermissionRequest.RESOURCE_*` names it asks for. */
    val resources: List<String>

    /** Grants only [resources]; WebView denies the others. */
    fun grant(resources: List<String>)

    /** Denies the whole request. */
    fun deny()
}

/**
 * The Android half of the media grant. [MediaGrant] decides each request first. Then this asks
 * Android for the runtime permissions that the granted kinds need, one prompt at a time, and
 * grants only the kinds that both allow. WebView and the permission prompt call it on the UI thread.
 */
internal class AndroidMediaRequests(
    private val origin: TrustedOrigin,
    /** True when the app holds an Android permission, such as `RECORD_AUDIO`. */
    private val isGranted: (permission: String) -> Boolean,
    /** Gets each denial and prompt failure. */
    private val report: (message: String, error: Throwable?) -> Unit = { message, error -> log(LogLevel.Info, TAG, message, error) },
) {
    /** Shows Android's prompt for these permissions, then calls [answered]. Null denies each request that needs a prompt. */
    var ask: ((permissions: Array<String>) -> Unit)? = null

    private class Waiting(val request: MediaPermissionRequest, val kinds: Set<MediaKind>) {
        var canceled = false
    }

    private val waiting = ArrayDeque<Waiting>()

    /** The request whose prompt is on screen. Its answer comes back through [answered]. */
    private var prompting: Waiting? = null

    /**
     * Decides [request]. Android does not say which frame asks, so the request counts as the main
     * frame's when the WebView's main document, [mainFrameUrl], is on the trusted origin. A frame of
     * another origin still fails the origin check; a frame of the same origin can already script the main document.
     */
    fun request(request: MediaPermissionRequest, mainFrameUrl: String?) {
        val kinds = request.resources.mapNotNull(::mediaKindOf).toSet()
        val otherResources = request.resources.any { mediaKindOf(it) == null }
        val mainFrame = mainFrameUrl != null && origin.matches(mainFrameUrl)
        when (val decision = MediaGrant.decide(origin, request.origin, mainFrame, kinds, otherResources)) {
            is MediaGrant.Decision.Deny -> {
                report("Media request from ${request.origin} denied: ${decision.reason}", null)
                request.deny()
            }
            is MediaGrant.Decision.Grant -> {
                waiting.addLast(Waiting(request, decision.kinds))
                next()
            }
        }
    }

    /** Android answered the prompt. Finishes the request that asked, then starts the next one. */
    fun answered() {
        val done = prompting ?: return next()
        prompting = null
        if (!done.canceled) finish(done)
        next()
    }

    /** WebView cancelled [request], for example on a navigation. It gets no answer. */
    fun canceled(request: MediaPermissionRequest) {
        prompting?.takeIf { it.request == request }?.canceled = true
        waiting.removeAll { it.request == request }
    }

    /** The WebView is gone: denies every open request and stops prompting. */
    fun close() {
        ask = null
        (listOfNotNull(prompting) + waiting).filterNot { it.canceled }.forEach { it.request.deny() }
        prompting = null
        waiting.clear()
    }

    /** Answers waiting requests in order until one needs a prompt. */
    private fun next() {
        while (prompting == null) {
            val head = waiting.removeFirstOrNull() ?: return
            val missing = head.kinds.map { MEDIA.getValue(it).permission }.filterNot(isGranted)
            if (missing.isEmpty()) {
                finish(head)
                continue
            }
            val ask = ask
            if (ask == null) {
                report("Media request from ${head.request.origin} denied: no permission prompt", null)
                head.request.deny()
                continue
            }
            prompting = head
            runCatching { ask(missing.toTypedArray()) }.onFailure { error ->
                report("The media permission prompt did not open", error)
                prompting = null
                head.request.deny()
            }
        }
    }

    /** Grants the kinds whose Android permission the app now holds, and denies the request if there are none. */
    private fun finish(done: Waiting) {
        val allowed = done.kinds.filter { isGranted(MEDIA.getValue(it).permission) }
        if (allowed.isEmpty()) done.request.deny() else done.request.grant(allowed.map { MEDIA.getValue(it).resource })
    }

    /** The WebView resource and the Android permission of one kind. */
    private class Media(val resource: String, val permission: String)

    companion object {
        private const val TAG = "AndroidMediaGrant"

        private val MEDIA = mapOf(
            MediaKind.Microphone to Media(PermissionRequest.RESOURCE_AUDIO_CAPTURE, Manifest.permission.RECORD_AUDIO),
            MediaKind.Camera to Media(PermissionRequest.RESOURCE_VIDEO_CAPTURE, Manifest.permission.CAMERA),
        )

        /** The kind a WebView resource names, or null for any other resource. */
        internal fun mediaKindOf(resource: String): MediaKind? = MEDIA.entries.firstOrNull { it.value.resource == resource }?.key
    }
}

/**
 * compose-webview-multiplatform's chrome client with one change: [media] answers permission
 * requests. The library's own `onPermissionRequest` grants any page and frame what the app holds.
 * Without [media] (a page that is not Hovel) every request is denied. [mainFrameUrl] reads the WebView's URL.
 */
internal class MediaChromeClient(
    private val media: AndroidMediaRequests?,
    private val mainFrameUrl: () -> String?,
) : AccompanistWebChromeClient() {
    override fun onPermissionRequest(request: PermissionRequest) {
        val media = media ?: return request.deny()
        media.request(WebViewMediaRequest(request), mainFrameUrl())
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        media?.canceled(WebViewMediaRequest(request))
    }

    /** A WebView request; equal to another wrapper of the same request, so a cancel finds it. */
    private data class WebViewMediaRequest(val request: PermissionRequest) : MediaPermissionRequest {
        override val origin: String get() = request.origin.toString()
        override val resources: List<String> get() = request.resources.toList()
        override fun grant(resources: List<String>) = request.grant(resources.toTypedArray())
        override fun deny() = request.deny()
    }
}
