package org.formulagen.wrapper.bridge

import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefMediaAccessCallback
import org.cef.callback.CefMediaAccessCallback.MediaPermissionFlags
import org.cef.handler.CefPermissionHandler
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

private const val DEVICE_BITS = MediaPermissionFlags.DEVICE_AUDIO_CAPTURE or MediaPermissionFlags.DEVICE_VIDEO_CAPTURE

/** The kinds that CEF's permission bits ask for: the device microphone and camera only. */
internal fun mediaKindsOf(permissions: Int): Set<MediaKind> = buildSet {
    if ((permissions and MediaPermissionFlags.DEVICE_AUDIO_CAPTURE) != 0) add(MediaKind.Microphone)
    if ((permissions and MediaPermissionFlags.DEVICE_VIDEO_CAPTURE) != 0) add(MediaKind.Camera)
}

/**
 * [MediaGrant]'s answer for one CEF request. Any other bit, such as desktop (screen) capture, is
 * another resource. With no trusted [origin] (a product that is not Hovel), every request is denied.
 */
internal fun jcefMediaDecision(origin: TrustedOrigin?, requestingOrigin: String, mainFrame: Boolean, permissions: Int): MediaGrant.Decision =
    origin?.let { MediaGrant.decide(it, requestingOrigin, mainFrame, mediaKindsOf(permissions), otherResources = (permissions and DEVICE_BITS.inv()) != 0) }
        ?: MediaGrant.Decision.Deny(MediaGrant.Reason.OtherOrigin)

/**
 * The JCEF media access handler. CEF names the requesting frame and that frame's own origin, and
 * this asks [MediaGrant] with them. A grant continues with the requested bits, because CEF needs them
 * to match for `getUserMedia`; anything else cancels. Never `--enable-media-stream`: it grants every page.
 */
class JcefMediaGrant(
    private val origin: TrustedOrigin?,
    /** Gets each denial. */
    private val report: (message: String) -> Unit = { log(LogLevel.Info, "JcefMediaGrant", it) },
) : CefPermissionHandler {
    override fun onRequestMediaAccessPermission(
        browser: CefBrowser?,
        frame: CefFrame?,
        requestingOrigin: String?,
        requestedPermissions: Int,
        callback: CefMediaAccessCallback,
    ): Boolean {
        when (val decision = jcefMediaDecision(origin, requestingOrigin.orEmpty(), frame?.isMain == true, requestedPermissions)) {
            is MediaGrant.Decision.Grant -> callback.Continue(requestedPermissions)
            is MediaGrant.Decision.Deny -> {
                report("Media request from $requestingOrigin denied: ${decision.reason}")
                callback.Cancel()
            }
        }
        return true
    }
}
