package org.formulagen.wrapper.progression.embedded

import androidx.compose.runtime.*
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import java.awt.Dimension
import java.awt.event.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.swing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import org.formulagen.wrapper.progression.*

/** Development entry point: one native window with the game and Compose above it. */
fun main() {
    require(System.getProperty("os.name").contains("Mac")) { "This embedding prototype requires macOS" }
    SwingUtilities.invokeLater { EmbeddedCompanionWindow().show() }
}

/** Owns both surfaces, transport lifetimes, and input ownership for this window. */
internal class EmbeddedCompanionWindow(
    private val frame: JFrame = JFrame("Hovel · Embedded companion prototype"),
    private val suppliedEnvironment: Map<String, String> = System.getenv(),
    private val onReturn: (() -> Unit)? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val game = MacEmbeddedGame(scope)
    private val controller = PresentationController { UUID.randomUUID().toString() }
    private val environment = suppliedEnvironment + mapOf("HOVEL_EMBEDDED" to "1", "HOVEL_COMPANION_SAVE" to
        (suppliedEnvironment["HOVEL_COMPANION_SAVE"] ?: Files.createTempDirectory("hovel-embedded-").resolve("save.json").toString()))
    private val host = DesktopCompanionHost(Path.of(requireNotNull(environment["HOVEL_GAME_PROJECT"])), scope, controller, environment, game.launchArguments)
    private val originalContent = frame.contentPane
    private val back = ComposePanel().apply { background = java.awt.Color(12, 15, 19) }
    private val resizeListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) { resizeSurface() }
    }
    private val focusListener = object : WindowAdapter() {
        override fun windowLostFocus(event: WindowEvent) { canvas.release() }
        override fun windowGainedFocus(event: WindowEvent) { if (!overlay.isVisible) canvas.requestFocusInWindow() }
    }
    private val canvas = GameCanvas(game)
    private val overlay = ComposePanel()
    private val status = JLabel("Starting the adventure…", SwingConstants.CENTER)
    private var attached = false
    private var closed = false
    private var failed = false
    private val shutdownHook = Thread({
        runCatching { host.close() }
        runCatching { game.close() }
    }, "hovel-game-shutdown")
    private val layers = object : JLayeredPane() {
        override fun doLayout() {
            canvas.setBounds(0, 0, width, height)
            val panelWidth = minOf(1080, width - 64).coerceAtLeast(1)
            val panelHeight = minOf(780, height - 48).coerceAtLeast(1)
            overlay.setBounds((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight)
            status.setBounds(0, 0, width, height)
            back.setBounds(16, 12, 160, 44)
            if (attached) game.resize(width * 2, height * 2)
        }
    }

    /** Mount the native game surface before starting its owned helper process. */
    fun show() {
        if (onReturn == null) {
            frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            frame.minimumSize = Dimension(900, 650)
            frame.setSize(1440, 920)
            frame.setLocationRelativeTo(null)
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { close() }
            })
        }
        // Explicit layers avoid Kotlin choosing Container.add(component, index).
        layers.add(canvas)
        layers.setLayer(canvas, JLayeredPane.DEFAULT_LAYER)
        layers.add(overlay)
        layers.setLayer(overlay, JLayeredPane.PALETTE_LAYER)
        layers.add(status)
        layers.setLayer(status, JLayeredPane.MODAL_LAYER)
        overlay.isVisible = false
        status.foreground = java.awt.Color.WHITE
        status.isOpaque = true
        status.background = java.awt.Color(22, 24, 28)
        if (onReturn != null) {
            layers.add(back)
            layers.setLayer(back, JLayeredPane.DRAG_LAYER)
            back.setContent {
                MaterialTheme(colorScheme = darkColorScheme(primary = CompanionGlass.amber, onPrimary = CompanionGlass.ink)) {
                    Button(onClick = ::close) { Text("Back to games") }
                }
            }
        }
        originalContent.isVisible = false
        frame.layeredPane.add(layers)
        frame.layeredPane.setLayer(layers, JLayeredPane.MODAL_LAYER)
        frame.layeredPane.addComponentListener(resizeListener)
        resizeSurface()
        overlay.setContent {
            val state by controller.state.collectAsState()
            CompanionPresentation(state, controller::select,
                { action -> controller.begin(action)?.let(host::send) },
                { controller.retry()?.let(host::send) }, controllerInput = host.controllerInput)
        }
        frame.addWindowFocusListener(focusListener)
        frame.isVisible = true
        scope.launch {
            game.contextId.collect { context ->
                if (context != null) {
                    attached = MacGameLayer.attach(canvas, context)
                    if (!attached) fail("Could not attach the native game surface")
                    else {
                        status.isVisible = false
                        layers.doLayout()
                        canvas.requestFocusInWindow()
                        println("HOVEL_EMBEDDED_ATTACHED context=$context windows=${java.awt.Window.getWindows().count { it.isVisible }}")
                    }
                }
            }
        }
        scope.launch {
            combine(controller.state, host.connected) { state, connected -> !failed && connected && state.snapshot?.view?.let { it != "closed" } == true }.collect { visible ->
                if (visible != overlay.isVisible) {
                    canvas.acceptsInput = !visible
                    overlay.isVisible = visible
                    if (visible) overlay.requestFocusInWindow() else canvas.requestFocusInWindow()
                    layers.revalidate(); layers.repaint()
                    println("HOVEL_EMBEDDED_OVERLAY visible=$visible")
                }
            }
        }
        scope.launch { game.failure.collect { it?.let(::fail) } }
        scope.launch { host.exitCode.collect { if (it != null && !closed) fail("Adventure ended (exit $it). Close this window to finish.") } }
        println("HOVEL_EMBEDDED_SAVE ${environment["HOVEL_COMPANION_SAVE"]}")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        host.start()
    }

    /** Cover the existing content without destroying its browser or composition. */
    private fun resizeSurface() {
        layers.setBounds(originalContent.x, originalContent.y, originalContent.width, originalContent.height)
        layers.doLayout()
    }

    /** Keep failures visible in the owning window instead of an unexplained frozen scene. */
    private fun fail(message: String) {
        if (failed || closed) return
        failed = true
        if (attached) { MacGameLayer.detach(canvas); attached = false }
        canvas.acceptsInput = false
        canvas.isVisible = false
        overlay.isVisible = false
        status.text = message
        status.isOpaque = true
        status.background = java.awt.Color(22, 24, 28)
        status.isVisible = true
        System.err.println("HOVEL_EMBEDDED_FAILURE $message")
        host.close(); game.close()
    }

    /** Tear down the remote layer and child before disposing their parent window. */
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun close() {
        if (closed) return
        closed = true
        try {
            if (attached && canvas.isDisplayable) MacGameLayer.detach(canvas)
        } finally {
            host.close(); game.close(); scope.cancel()
        }
        overlay.dispose()
        back.dispose()
        frame.removeWindowFocusListener(focusListener)
        frame.layeredPane.removeComponentListener(resizeListener)
        frame.layeredPane.remove(layers)
        originalContent.isVisible = true
        frame.layeredPane.revalidate()
        frame.layeredPane.repaint()
        Runtime.getRuntime().removeShutdownHook(shutdownHook)
        if (onReturn == null) frame.dispose() else onReturn.invoke()
    }
}
