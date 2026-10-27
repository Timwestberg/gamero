package org.formulagen.wrapper.progression

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path
import java.util.UUID
import java.awt.Desktop
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities

/** Standalone desktop entry into the same commonMain screens used by future hosts. */
fun main() {
    var processExit = 0
    application {
        val project = System.getenv("HOVEL_GAME_PROJECT")?.let(Path::of)
            ?: error("Set HOVEL_GAME_PROJECT to games/ember-tower in the companion checkout.")
        val scope = rememberCoroutineScope()
        val controller = remember { PresentationController { UUID.randomUUID().toString() } }
        val host = remember { DesktopCompanionHost(project, scope, controller) }
        val state by controller.state.collectAsState()
        val exitCode by host.exitCode.collectAsState()
        val connected by host.connected.collectAsState()
        DisposableEffect(host) { host.start(); onDispose { host.close() } }
        LaunchedEffect(exitCode) { exitCode?.let { processExit = it; exitApplication() } }
        val visible = connected && state.snapshot?.view?.let { it != "closed" } == true
        Window(onCloseRequest = { controller.begin(if (state.snapshot?.view == "growth") "later" else "close")?.let(host::send) },
            visible = visible, title = "Hovel · Companion growth", state = rememberWindowState(placement = androidx.compose.ui.window.WindowPlacement.Maximized, width = 1180.dp, height = 820.dp)) {
            DisposableEffect(window) {
                val diagnostics = System.getenv("HOVEL_PRESENTATION_DIAGNOSTICS") == "1"
                val shown = object : ComponentAdapter() {
                    /** Activate the application after the native window is actually shown. */
                    override fun componentShown(event: ComponentEvent) {
                        SwingUtilities.invokeLater {
                            if (window.isShowing) {
                                if (Desktop.isDesktopSupported()) {
                                    val desktop = Desktop.getDesktop()
                                    if (desktop.isSupported(Desktop.Action.APP_REQUEST_FOREGROUND)) desktop.requestForeground(true)
                                }
                                window.toFront()
                                window.requestFocus()
                            }
                        }
                    }
                }
                val focus = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent) {
                        if (diagnostics) println("HOVEL_PRESENTATION_FOCUS gained")
                    }
                }
                window.addComponentListener(shown)
                window.addWindowFocusListener(focus)
                onDispose {
                    window.removeComponentListener(shown)
                    window.removeWindowFocusListener(focus)
                }
            }
            CompanionPresentation(state, controller::select,
                { action -> controller.begin(action)?.let(host::send) },
                { controller.retry()?.let(host::send) }, controllerInput = host.controllerInput,
                reducedEffectsInitially = System.getenv("HOVEL_REDUCED_EFFECTS") == "1")
        }
    }

    kotlin.system.exitProcess(processExit)
}
