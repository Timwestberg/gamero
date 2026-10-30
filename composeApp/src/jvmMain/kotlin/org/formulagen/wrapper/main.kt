package org.formulagen.wrapper

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
import org.formulagen.wrapper.approvals.NativeDialogHost
import org.formulagen.wrapper.chat.KokoroVoicePicker
import org.formulagen.wrapper.bridge.desktopHostCapabilities
import org.formulagen.wrapper.companion.NativeBridge
import org.formulagen.wrapper.hovel.PhonePairing
import org.formulagen.wrapper.hovel.PhonePairingWindow
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.voice.runEchoSelfTest
import org.formulagen.wrapper.hovel.HovelLaunchScreen
import org.formulagen.wrapper.hovel.HovelStartState
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import org.cef.CefClient
import javax.swing.JFrame

private class DesktopResources : AutoCloseable {
    val browser = AtomicReference<org.cef.browser.CefBrowser?>(null)
    val windowActive = AtomicBoolean(false)
    var services: DesktopServices? = null
    private var keyboardClient: CefClient? = null
    // Escape the page receives is the page's: team chat keeps its catalog barge-in here, and a project conversation's
    // page sends its own interrupt only when it has no other use for the key. No page Escape reaches conversation voice
    // natively: the window's handler is team chat's, and the conversation row's own handler needs the row focused.
    private val keyboard = BrowserEditKeyboard(browser::get, windowActive::get, onEscape = {
        services?.chat?.endConversation() == true
    })

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
    override fun close() {
        windowActive.set(false)
        browser.set(null)
        keyboardClient?.removeKeyboardHandler()
        keyboardClient = null
        // The bridge stops taking requests before the services it calls go away.
        try { bridge?.close() } finally { services?.close() }
    }
}

/**
 * A launch setting from the environment, else the `-D` system property a packaged release bakes
 * in (`tools/build-release.sh`). The environment wins so a developer can repoint a packaged app.
 */
internal fun desktopSetting(environment: String?, property: String?): String? =
    environment?.takeIf { it.isNotBlank() } ?: property?.takeIf { it.isNotBlank() }

private fun isHovelProduct() = desktopSetting(System.getenv("GAMERO_PRODUCT"), System.getProperty("gamero.product")) == "hovel"

/** App-independent desktop configuration; product adapters supply the trusted URL. */
internal fun desktopWebUrl(args: Array<String>, environment: String?): String? {
    if (args.size > 1) return null
    val raw = args.singleOrNull()?.removePrefix("--url=") ?: environment ?: return null
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (!isAllowedWebUrl(uri.scheme, uri.host, uri.userInfo != null, uri.fragment != null)) return null
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
            exitAction.set { resources.services?.closeGame(); exitApplication() }
            val url = remember { desktopWebUrl(args, desktopSetting(System.getenv("GAMERO_WEB_URL"), System.getProperty("gamero.webUrl"))) }
            CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides object : WindowExceptionHandlerFactory {
                override fun exceptionHandler(window: java.awt.Window): WindowExceptionHandler = failure
            }) {
                Window(
                    onCloseRequest = { resources.services?.closeGame(); exitApplication() },
                    title = if (isHovelProduct()) HOVEL_PRODUCT else GAMERO_PRODUCT_NAME,
                    // Team chat's Escape. A project conversation's voice hears Escape only from its own row, below.
                    onPreviewKeyEvent = { event ->
                        event.key == Key.Escape && event.type == KeyEventType.KeyDown &&
                            resources.services?.chat?.endConversation() == true
                    },
                ) {
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
                        LaunchedEffect(Unit) {
                            val flag = System.getenv("HOVEL_HEARING_SELF_TEST")?.trim()?.lowercase()
                            if (flag == "1" || flag == "true" || flag == "yes") {
                                withContext(Dispatchers.IO) { runEchoSelfTest() }
                            }
                        }
                        var pairing by remember { mutableStateOf(false) }
                        MenuBar {
                            if (isHovelProduct()) {
                                // The one way a phone is confirmed: opened here by the person, never by a page.
                                Menu("Hovel") {
                                    Item("Pair a phone…", onClick = { pairing = true })
                                }
                            }
                            Menu("Develop") {
                                Item("Echo self-test", onClick = {
                                    Thread({
                                        println("echo self-test: starting inside Gamero Adventures")
                                        println("echo self-test: finished exit=${runEchoSelfTest()}")
                                    }, "ember-echo-self-test").apply { isDaemon = true }.start()
                                })
                            }
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
                        if (pairing) {
                            val companion = resources.services?.companion
                            val phones = remember(companion) { PhonePairing { companion?.nativeExchange() } }
                            MaterialTheme { PhonePairingWindow(phones, onClose = { pairing = false }) }
                        }
                    }
                    MaterialTheme {
                        if (url == null) {
                            Text("Set GAMERO_WEB_URL to an HTTPS app URL (or HTTP localhost for development).", Modifier.padding(24.dp))
                        } else {
                            DesktopBrowser(url, lifecycle, resources, failure, window)
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
private fun DesktopBrowser(url: String, lifecycle: BrowserLifecycle, resources: DesktopResources, runtimeFailure: DesktopFailure, window: JFrame) {
    var start by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val support = remember {
        val home = File(System.getProperty("user.home"))
        if (System.getProperty("os.name").contains("Mac")) File(home, "Library/Application Support/Gamero")
        else File(home, ".local/share/gamero")
    }
    val product = if (isHovelProduct()) HOVEL_PRODUCT else GAMERO_PRODUCT_NAME
    val services = remember {
        DesktopServices.create(url, support.toPath(), System.getenv(), product, window).also { resources.services = it }
    }
    val companion = services.companion
    // A Mac registered before runs its conversations from launch, with no page involved. Keychain may block, so off the UI thread.
    LaunchedEffect(companion) { companion?.let { withContext(Dispatchers.IO) { it.resume() } } }
    // Approvals and the start-of-work confirmation: windows of Hovel's own that the page cannot reach.
    NativeDialogHost(services.dialogs)
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
        isHovelProduct() && !initialized -> HovelLaunchScreen(
            state = failure?.let { HovelStartState.Failed(it, canRetry = false) }
                ?: if (start) HovelStartState.Preparing else HovelStartState.Ready(url),
            onContinue = { start = true },
            footnote = "Hovel runs in a verified JetBrains Runtime with JCEF. It never downloads or modifies browser binaries.",
        )
        failure != null -> Text("Browser initialization failed: $failure. Close Gamero and retry setup.", Modifier.padding(24.dp))
        initialized -> Column {
            if (companion != null) {
                Text(companion.status, Modifier.padding(8.dp))
                companion.approval?.let { approval ->
                    val tasks = companion.taskWorkspace?.let { " It also runs read-only Codex tasks in $it." }.orEmpty()
                    Text("Connect account ${approval.ownerId} at $url to this Mac? This stores a host credential in Keychain, and this Mac then runs the account's companion conversations whenever Hovel is open, with your own provider sign-ins. Agents work only in folders you choose here, and ask here before anything your settings do not allow.$tasks", Modifier.padding(8.dp))
                    Button(onClick = { approval.answer.complete(true) }) { Text("Approve computer connection") }
                    Button(onClick = { approval.answer.complete(false) }) { Text("Cancel") }
                }
            } else if (isHovelProduct()) {
                Text("Companion conversations run on a Mac.", Modifier.padding(8.dp))
            }
            val picker by services.voicePicker.state
            val conversationPicker by services.conversationPicker.state
            val conversationVoice = services.conversationVoice
            // The served conversation's row first; team chat's shows only in the Gamero catalog.
            if (conversationVoice != null && conversationPicker.visible) {
                KokoroVoicePicker(
                    conversationPicker,
                    conversationVoice::chooseSpokenVoice,
                    conversationVoice::converseClick,
                    conversationVoice::toggleFillers,
                    onEscape = conversationVoice::escape,
                )
            } else {
                KokoroVoicePicker(
                    picker,
                    services.chat::chooseSpokenVoice,
                    services.chat::converseClick,
                    services.chat::toggleFillers,
                )
            }
            App(url, enableLegacyBridge = false, webViewFactory = ::persistentDesktopBrowser, onWebViewCreated = { browser ->
                resources.attachBrowser(browser)
                resources.bridge?.close()
                resources.bridge = NativeBridge(
                    browser = browser,
                    product = product,
                    trustedUrl = url,
                    capabilities = desktopHostCapabilities(),
                    products = services.packs(product),
                    onContextEnded = services::invalidate,
                    onCompanionCallEnded = { companion?.invalidatePending() },
                    companion = companion?.let { it::dispatch },
                )
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
