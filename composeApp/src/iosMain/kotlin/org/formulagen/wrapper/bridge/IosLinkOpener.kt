package org.formulagen.wrapper.bridge

import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * Opens `link.open` URLs with `UIApplication.openURL(_:options:completionHandler:)`,
 * which must run on the main thread. When iOS reports that nothing opened the
 * URL, the call answers `failed`.
 */
internal class IosLinkOpener : LinkOpener {
    override suspend fun open(url: String) {
        val nsUrl = checkNotNull(NSURL.URLWithString(url)) { "NSURL rejected the URL" }
        val opened = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>()) { success ->
                    if (continuation.isActive) continuation.resume(success)
                }
            }
        }
        check(opened) { "iOS did not open the URL" }
    }
}
