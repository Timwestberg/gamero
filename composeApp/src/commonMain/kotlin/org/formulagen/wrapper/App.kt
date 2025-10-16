package org.formulagen.wrapper

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import org.formulagen.wrapper.managers.WebViewManager
import org.jetbrains.compose.ui.tooling.preview.Preview



@Composable
@Preview
fun App() {
    MaterialTheme {
        WebViewManager(url = "https://web.worldwinner.com")
    }
}