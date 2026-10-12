package org.formulagen.wrapper

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import org.formulagen.wrapper.managers.WebViewManager
import org.jetbrains.compose.ui.tooling.preview.Preview



@Composable
@Preview
fun App(url: String = "https://web.worldwinner.com", enableLegacyBridge: Boolean = true, onWebViewCreated: (com.multiplatform.webview.web.NativeWebView) -> Unit = {}) {
    MaterialTheme {
        WebViewManager(url = url, enableLegacyBridge = enableLegacyBridge, onWebViewCreated = onWebViewCreated)
    }
}