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
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import dev.datlag.kcef.KCEF
import dev.datlag.kcef.KCEFAcknowledge
import com.jetbrains.cef.JCefAppConfig
import org.cef.CefApp
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import kotlin.system.exitProcess
import org.formulagen.wrapper.companion.NativeBridge
import org.formulagen.wrapper.hovel.HovelCompanion
import java.nio.file.Path
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import org.cef.CefClient

private class DesktopResources : AutoCloseable {
    val browser = AtomicReference<org.cef.browser.CefBrowser?>(null)
    val windowActive = AtomicBoolean(false)
    private var keyboardClient: CefClient? = null
    private val keyboard = BrowserEditKeyboard(browser::get, windowActive::get)

    fun attachBrowser(target: org.cef.browser.CefBrowser) {
        browser.set(null)
        keyboardClient?.removeKeyboardHandler()
        keyboardClient = null
        if (System.getProperty("os.name").contains("Mac")) {
            // This dedicated WebView client has no other keyboard handler.
            keyboardClient = target.client.also { it.addKeyboardHandler(keyboard) }
            println("GAMERO_EDIT_HANDLER: registrationRequested=true")
        }
        browser.set(target)
    }

    var bridge: NativeBridge? = null
    var companion: HovelCompanion? = null
    override fun close() {
        windowActive.set(false)
        browser.set(null)
        keyboardClient?.removeKeyboardHandler()
        keyboardClient = null
        try { bridge?.close() } finally { companion?.close() }
    }
}

/** App-independent desktop configuration; product adapters supply the trusted URL. */
internal fun desktopWebUrl(args: Array<String>, environment: String?): String? {
    if (args.size > 1) return null
    val raw = args.singleOrNull()?.removePrefix("--url=") ?: environment ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.userInfo != null || uri.host == null || uri.fragment != null) return null
    if (uri.scheme != "https" && !(uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) return null
    return uri.toASCIIString()
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val exitAction = AtomicReference<(() -> Unit)?>(null)
    val failure = DesktopFailure { EventQueue.invokeLater { exitAction.get()?.invoke() } }
    Thread.setDefaultUncaughtExceptionHandler(failure)
    val resources = DesktopResources()
    val editActions = BrowserEditActions(resources.browser::get)
    val lifecycle = BrowserLifecycle(
        dispose = { CefApp.getInstanceIfAny()?.dispose(); KCEF.dispose() },
        awaitTermination = {
            while (CefApp.getState() != CefApp.CefAppState.NONE && CefApp.getState() != CefApp.CefAppState.TERMINATED) delay(20)
        },
    )
    try {
        application(exitProcessOnExit = false) {
            exitAction.set(::exitApplication)
            val url = remember { desktopWebUrl(args, System.getenv("GAMERO_WEB_URL")) }
            CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides object : WindowExceptionHandlerFactory {
                override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler = failure
            }) {
                Window(onCloseRequest = ::exitApplication, title = "Gamero") {
                    DisposableEffect(window) {
                        // Read AWT state on its event thread, never from the CEF callback thread.
                        val listener = object : WindowAdapter() {
                            override fun windowActivated(event: WindowEvent) { resources.windowActive.set(true) }
                            override fun windowDeactivated(event: WindowEvent) { resources.windowActive.set(false) }
                            override fun windowClosing(event: WindowEvent) { resources.windowActive.set(false) }
                            override fun windowClosed(event: WindowEvent) { resources.windowActive.set(false) }
                        }
                        window.addWindowListener(listener)
                        resources.windowActive.set(window.isActive)
                        onDispose {
                            resources.windowActive.set(false)
                            window.removeWindowListener(listener)
                        }
                    }
                    if (System.getProperty("os.name").contains("Mac")) {
                        MenuBar {
                            Menu("Edit") {
                                Item("Undo", onClick = { editActions.perform(BrowserEditAction.UNDO) })
                                Item("Redo", onClick = { editActions.perform(BrowserEditAction.REDO) })
                                Separator()
                                Item("Cut", onClick = { editActions.perform(BrowserEditAction.CUT) })
                                Item("Copy", onClick = { editActions.perform(BrowserEditAction.COPY) })
                                Item("Paste", onClick = { editActions.perform(BrowserEditAction.PASTE) })
                                Item("Select All", onClick = { editActions.perform(BrowserEditAction.SELECT_ALL) })
                            }
                        }
                    }
                    MaterialTheme {
                        if (url == null) {
                            Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.padding(24.dp))
                        } else {
                            DesktopBrowser(url, lifecycle, resources, failure)
                        }
                    }
                }
            }
        }
    } catch (error: Throwable) { failure.onException(error) }
    val cleanShutdown = runBlocking {
        val resourcesClosed = runCatching { withContext(Dispatchers.IO) { resources.close() } }.isSuccess
        val browserClosed = lifecycle.shutdown()
        resourcesClosed && browserClosed
    }
    exitProcess(if (cleanShutdown && !failure.hasFailed) 0 else 1)
}

@OptIn(KCEFAcknowledge::class)
@Composable
private fun DesktopBrowser(url: String, lifecycle: BrowserLifecycle, resources: DesktopResources, runtimeFailure: DesktopFailure) {
    var start by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val support = remember {
        val home = File(System.getProperty("user.home"))
        if (System.getProperty("os.name").contains("Mac")) File(home, "Library/Application Support/Gamero")
        else File(home, ".local/share/gamero")
    }
    val companion = remember {
        if (System.getenv("GAMERO_PRODUCT") == "hovel" && System.getProperty("os.name").contains("Mac")) {
            val executable = System.getenv("GAMERO_CODEX_PATH")?.let(Path::of)
            val workspace = System.getenv("GAMERO_WORKSPACE")?.let(Path::of)
            if (executable != null && workspace != null) {
                HovelCompanion(url, support.toPath().resolve("hovel-${HovelCompanion.sha256(url)}"), executable, workspace)
                    .also { resources.companion = it }
            } else null
        } else null
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
                val app = CefApp.getInstance(arguments, settings)
                awaitBrowserInitialization(
                    register = { ready -> app.onInitialization { state ->
                        if (state == CefApp.CefAppState.INITIALIZED) ready()
                    } },
                    state = CefApp::getState,
                )
                KCEF.init(app)
                println("GAMERO_JCEF_INITIALIZED: module=${CefApp::class.java.module.name} runtime=${System.getProperty("java.runtime.version")}")
                initialized = true
            } catch (error: Throwable) {
                failure = if (error is IllegalStateException) error.message else error.javaClass.simpleName
                runtimeFailure.onException(error)
            }
        }
    }
    when {
        failure != null -> Text("Browser initialization failed: $failure. Close Gamero and retry setup.", Modifier.padding(24.dp))
        initialized -> Column {
            if (companion != null) {
                Text(companion.status, Modifier.padding(8.dp))
                companion.approval?.let { approval ->
                    Text("Connect account ${approval.ownerId} at $url to this Mac? This stores a host credential in Keychain and allows read-only Codex tasks in ${System.getenv("GAMERO_WORKSPACE")} while Gamero is open. Codex uses your own ChatGPT subscription.", Modifier.padding(8.dp))
                    Button(onClick = { approval.answer.complete(true) }) { Text("Approve computer connection") }
                    Button(onClick = { approval.answer.complete(false) }) { Text("Cancel") }
                }
            } else if (System.getenv("GAMERO_PRODUCT") == "hovel") {
                Text("Set GAMERO_CODEX_PATH and GAMERO_WORKSPACE to enable this Mac's optional Codex connection.", Modifier.padding(8.dp))
            }
            App(url, enableLegacyBridge = false, webViewFactory = ::persistentDesktopBrowser, onWebViewCreated = { browser ->
                resources.attachBrowser(browser)
                if (companion != null) {
                    resources.bridge?.close()
                    resources.bridge = NativeBridge(browser, url, companion::invalidatePending, companion::dispatch)
                }
            })
        }
        start -> Text("Preparing browser…", Modifier.padding(24.dp))
        else -> Column(Modifier.padding(24.dp)) {
            Text("Open $url")
            Text("Gamero requires a verified JetBrains Runtime with JCEF. It does not download or modify browser binaries.")
            Button(onClick = { start = true }) { Text("Continue") }
        }
    }
}
