package org.formulagen.wrapper.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.ui.fgInk300
import org.formulagen.wrapper.ui.fgInk700
import org.formulagen.wrapper.ui.fgInk900
import org.formulagen.wrapper.ui.fgInk950
import org.formulagen.wrapper.ui.fgRed400
import org.formulagen.wrapper.ui.fgWhite

/** How often the window asks each running tab whether it is busy. */
private const val BUSY_POLL_MS = 1_000L

/** Every open workspace window, as windows of the app's one Compose application (`main.kt`). */
@Composable
internal fun WorkspaceWindowsHost(workspaces: WorkspaceWindows) {
    for (window in workspaces.windows) key(window.projectId) {
        WorkspaceWindow(window, onClose = { workspaces.close(window) })
    }
}

@Composable
private fun WorkspaceWindow(state: WorkspaceWindowState, onClose: () -> Unit) {
    val windowState = rememberWindowState(width = 1100.dp, height = 720.dp)
    Window(onCloseRequest = onClose, state = windowState, title = state.title) {
        DisposableEffect(window) {
            state.awtWindow = window
            onDispose { state.awtWindow = null }
        }
        LaunchedEffect(state.frontRequests) { if (state.frontRequests > 0) window.toFront() }
        LaunchedEffect(state) {
            while (true) {
                withContext(Dispatchers.IO) { state.pollBusy() }
                delay(BUSY_POLL_MS)
            }
        }
        // The last tab closing closes the window, as in Terminal.
        LaunchedEffect(state.tabs.size) { if (state.tabs.isEmpty()) onClose() }
        MenuBar {
            Menu("Shell") {
                Item("New Tab", shortcut = KeyShortcut(Key.T, meta = true), enabled = state.canAddTab, onClick = { state.addTab(Launch.Shell) })
                Item("Close Tab", shortcut = KeyShortcut(Key.W, meta = true), onClick = { state.selectedTab()?.let(state::closeTab) })
            }
            // The spike's rendering check: catalog tools started through the login shell.
            Menu("Develop") {
                for (tool in DEV_TOOLS) Item("Open ${tool.label}", enabled = state.canAddTab, onClick = { state.addTab(Launch.Tool(tool)) })
            }
        }
        Column(Modifier.fillMaxSize().background(fgInk950)) {
            TabBar(state)
            state.selectedTab()?.let { TabBanner(state, it) }
            TerminalPane(state.deck, state.selected, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

private val DEV_TOOLS = listOf(WorkspaceTool.NEOVIM, WorkspaceTool.LAZYGIT, WorkspaceTool.YAZI)

@Composable
private fun TabBar(state: WorkspaceWindowState) {
    Row(Modifier.fillMaxWidth().height(34.dp).background(fgInk900).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (tab in state.tabs) {
            val selected = tab.id == state.selected
            Row(
                Modifier.padding(end = 4.dp).background(if (selected) fgInk700 else fgInk900).clickable { state.select(tab) }.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tab.title + if (tab.busy) "  ●" else "", color = if (selected) fgWhite else fgInk300, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text("×", color = fgInk300, fontSize = 13.sp, modifier = Modifier.clickable { state.closeTab(tab) })
            }
        }
        NewTabMenu(state)
    }
}

@Composable
private fun NewTabMenu(state: WorkspaceWindowState) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text("+", color = if (state.canAddTab) fgWhite else fgInk700, fontSize = 16.sp,
            modifier = Modifier.clickable(enabled = state.canAddTab) { open = true }.padding(horizontal = 10.dp, vertical = 4.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Shell") }, onClick = { open = false; state.addTab(Launch.Shell) })
            for (tool in DEV_TOOLS) DropdownMenuItem(text = { Text(tool.label) }, onClick = { open = false; state.addTab(Launch.Tool(tool)) })
        }
    }
}

/** What the selected tab needs to say above its terminal: starting, a failed start, or an exit. */
@Composable
private fun TabBanner(state: WorkspaceWindowState, tab: WorkspaceTab) {
    val (message, action) = when (val current = tab.state) {
        TabState.Running -> return
        TabState.Starting -> "Starting…" to null
        is TabState.Failed -> "Could not start: ${current.message}" to "Retry"
        is TabState.Exited -> "Process exited (${current.code})" to "Restart"
    }
    Row(Modifier.fillMaxWidth().background(fgInk900).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = if (tab.state is TabState.Failed) fgRed400 else fgInk300, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) {
            TextButton(onClick = { state.restart(tab) }) { Text(action, color = fgWhite) }
            TextButton(onClick = { state.closeTab(tab) }) { Text("Close", color = fgWhite) }
        }
    }
}
