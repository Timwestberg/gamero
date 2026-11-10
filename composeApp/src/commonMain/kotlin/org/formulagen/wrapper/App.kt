package org.formulagen.wrapper

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.formulagen.wrapper.managers.WebViewManager
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.PlatformWebViewParams
import com.multiplatform.webview.web.defaultWebViewFactory
import org.jetbrains.compose.ui.tooling.preview.Preview



@Composable
@Preview
fun App(url: String = "https://web.worldwinner.com", enableLegacyBridge: Boolean = true, onWebViewCreated: (NativeWebView) -> Unit = {}, webViewFactory: (WebViewFactoryParam) -> NativeWebView = ::defaultWebViewFactory, onPageLoaded: (url: String, title: String?) -> Unit = { _, _ -> }, platformWebViewParams: PlatformWebViewParams? = null, modifier: Modifier = Modifier) {
    MaterialTheme {
        WebViewManager(url = url, modifier = modifier, enableLegacyBridge = enableLegacyBridge, onWebViewCreated = onWebViewCreated, webViewFactory = webViewFactory, onPageLoaded = onPageLoaded, platformWebViewParams = platformWebViewParams)
    }
}