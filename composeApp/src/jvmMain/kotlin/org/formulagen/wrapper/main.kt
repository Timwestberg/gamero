package org.formulagen.wrapper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFAcknowledge
import com.jetbrains.cef.JCefAppConfig
import org.cef.CefApp
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import kotlin.system.exitProcess

/** App-independent desktop configuration; product adapters supply the trusted URL. */
internal fun desktopWebUrl(args: Array<String>, environment: String?): String? {
    if (args.size > 1) return null
    val raw = args.singleOrNull()?.removePrefix("--url=") ?: environment ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.host == null || uri.fragment != null) return null
    if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) return null
    return uri.toASCIIString()
}

fun main(args: Array<String>) {
    val lifecycle = BrowserLifecycle(
        dispose = { CefApp.getInstanceIfAny()?.dispose(); KCEF.dispose() },
        awaitTermination = {
            while (CefApp.getState() != CefApp.CefAppState.NONE && CefApp.getState() != CefApp.CefAppState.TERMINATED) delay(20)
        },
    )
    application(exitProcessOnExit = false) {
        val url = remember { desktopWebUrl(args, System.getenv("GAMERO_WEB_URL")) }
        Window(onCloseRequest = ::exitApplication, title = "Gamero") {
            MaterialTheme {
                if (url == null) {
                    Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.padding(24.dp))
                } else {
                    DesktopBrowser(url, lifecycle)
                }
            }
        }
    }
    val cleanShutdown = runBlocking { lifecycle.shutdown() }
    exitProcess(if (cleanShutdown) 0 else 1)
}

@OptIn(KCEFAcknowledge::class)
@Composable
private fun DesktopBrowser(url: String, lifecycle: BrowserLifecycle) {
    var start by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val support = remember {
        val home = File(System.getProperty("user.home"))
        if (System.getProperty("os.name").contains("Mac")) File(home, "Library/Application Support/Gamero")
        else File(home, ".local/share/gamero")
    }
    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        lifecycle.start {
            try {
                val runtime = File(System.getProperty("java.home"))
                check(File(runtime, "lib/${System.mapLibraryName("jcef")}").isFile) {
                    "A verified JetBrains Runtime with JCEF is required"
                }
                val configuration = JCefAppConfig.getInstance()
                // Use the OS keychain; do not install binaries or alter quarantine flags.
                val arguments = configuration.appArgs.filterNot { it == "--use-mock-keychain" }.toTypedArray()
                val settings = configuration.cefSettings.apply { cache_path = File(support, "web-cache").absolutePath }
                check(CefApp.startup(arguments)) { "JCEF startup failed" }
                KCEF.init(CefApp.getInstance(arguments, settings))
                initialized = true
            } catch (error: Throwable) {
                failure = if (error is IllegalStateException) error.message else error.javaClass.simpleName
            }
        }
    }
    when {
        failure != null -> Text("Browser initialization failed: $failure. Close Gamero and retry setup.", Modifier.padding(24.dp))
        initialized -> App(url, enableLegacyBridge = false)
        start -> Text("Preparing browser…", Modifier.padding(24.dp))
        else -> Column(Modifier.padding(24.dp)) {
            Text("Open $url")
            Text("Gamero requires a verified JetBrains Runtime with JCEF. It does not download or modify browser binaries.")
            Button(onClick = { start = true }) { Text("Continue") }
        }
    }
}
