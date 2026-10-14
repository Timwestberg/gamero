package org.formulagen.wrapper.managers

import com.multiplatform.webview.web.NativeWebView

internal actual fun webViewUserAgent(webView: NativeWebView): String? =
    withoutLegacyVersionToken(webView.settings.userAgentString)
