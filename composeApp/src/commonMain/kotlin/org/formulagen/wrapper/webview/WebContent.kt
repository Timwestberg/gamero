package org.formulagen.wrapper.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

class WebContent(private val jsCaller: JsCaller){
    fun send(methodName: String, parameters: String = "{}") = jsCaller.call(methodName, parameters)
}

val LocalWebContent: ProvidableCompositionLocal<WebContent?> = compositionLocalOf { null }

@Composable
fun ProvideWebContent(value: WebContent?, content: @Composable () -> Unit ) {
    CompositionLocalProvider(LocalWebContent provides value) { content() }
}