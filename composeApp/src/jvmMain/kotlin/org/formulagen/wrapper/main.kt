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
import kotlinx.coroutines.*
import java.io.File
import java.net.URI

/** App-independent desktop configuration; product adapters supply the trusted URL. */
internal fun desktopWebUrl(args: Array<String>, environment: String?): String? {
    if (args.size > 1) return null
    val raw = args.singleOrNull()?.removePrefix("--url=") ?: environment ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.host == null || uri.fragment != null) return null
    if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) return null
    return uri.toASCIIString()
}

fun main(args: Array<String>) = application {
    val url = remember { desktopWebUrl(args, System.getenv("GAMERO_WEB_URL")) }
    Window(onCloseRequest = ::exitApplication, title = "Gamero") {
        MaterialTheme {
            if (url == null) {
                Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.padding(24.dp))
            } else {
                DesktopBrowser(url)
            }
        }
    }
}

@Composable
private fun DesktopBrowser(url: String) {
    val browserScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    var initialization by remember { mutableStateOf<Job?>(null) }
    var start by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0F) }
    var failure by remember { mutableStateOf<String?>(null) }
    var restart by remember { mutableStateOf(false) }
    val support = remember {
        val home = File(System.getProperty("user.home"))
        if (System.getProperty("os.name").contains("Mac")) File(home, "Library/Application Support/Gamero")
        else File(home, ".local/share/gamero")
    }
    LaunchedEffect(start) {
        if (!start) return@LaunchedEffect
        initialization = browserScope.launch {
            KCEF.init(builder = {
                installDir(File(support, "browser-jbr-25.0.4.1-b635.72"))
                download { github { release("jbr-release-25.0.4.1b635.72") } }
                progress {
                    onDownloading { progress = it.coerceAtLeast(0F) }
                    onInitialized { initialized = true }
                }
                settings { cachePath = File(support, "web-cache").absolutePath }
            }, onError = { failure = it?.javaClass?.simpleName ?: "Unknown error" }, onRestartRequired = { restart = true })
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            // Do not block the UI while setup/download finishes; init owns its lifetime.
            browserScope.launch {
                initialization?.join()
                if (start) KCEF.dispose()
                browserScope.cancel()
            }
        }
    }
    when {
        restart -> Text("Browser setup finished. Restart Gamero.", Modifier.padding(24.dp))
        failure != null -> Text("Browser initialization failed: $failure", Modifier.padding(24.dp))
        initialized -> App(url, enableLegacyBridge = false)
        start -> Text("Preparing browser: ${progress.toInt()}%", Modifier.padding(24.dp))
        else -> Column(Modifier.padding(24.dp)) {
            Text("Open $url")
            Text("Gamero uses a browser runtime. Continue may download it into this app’s support directory.")
            Button(onClick = { start = true }) { Text("Continue") }
        }
    }
}
