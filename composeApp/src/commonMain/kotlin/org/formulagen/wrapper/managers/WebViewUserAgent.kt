package org.formulagen.wrapper.managers

import com.multiplatform.webview.web.NativeWebView

/** The user agent this platform's web view should send instead of its default, or null to keep it. */
internal expect fun webViewUserAgent(webView: NativeWebView): String?

/**
 * Drops the legacy `Version/x.y` token from a user agent. Android WebView sends `Version/4.0`
 * next to `Safari/`, which Emscripten's browser check reads as Safari 4.0, so Godot web exports
 * such as Hovel refuse to start. The Chrome token, which the check does need, is kept.
 */
internal fun withoutLegacyVersionToken(userAgent: String): String =
    userAgent.replace(Regex("""Version/\d+(\.\d+)*\s*"""), "")
