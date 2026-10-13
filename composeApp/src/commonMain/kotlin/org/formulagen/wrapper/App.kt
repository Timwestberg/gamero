package org.formulagen.wrapper

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import org.formulagen.wrapper.managers.WebViewManager
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.defaultWebViewFactory
import org.jetbrains.compose.ui.tooling.preview.Preview



@Composable
@Preview
fun App(url: String = "https://web.worldwinner.com", enableLegacyBridge: Boolean = true, onWebViewCreated: (NativeWebView) -> Unit = {}, webViewFactory: (WebViewFactoryParam) -> NativeWebView = ::defaultWebViewFactory) {
    MaterialTheme {
        WebViewManager(url = url, enableLegacyBridge = enableLegacyBridge, onWebViewCreated = onWebViewCreated, webViewFactory = webViewFactory)
    }
}