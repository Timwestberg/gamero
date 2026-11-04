package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.MenuScope
import java.awt.Frame
import java.nio.file.Path
import org.formulagen.wrapper.conversations.NativeFolderPicker
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/*
 * The workspace's development entry points, in src/jvmDev, which a release never compiles
 * (docs/desktop.md, "Build boundaries"):
 *
 * - [desktopWorkspaces] builds the windows for `main.kt`, with [spikeSessionHost] as their host.
 * - Develop → Open Workspace… in the main window picks a folder and opens a window with a Shell tab.
 *   Develop → Forget Trusted Folders makes every folder ask again.
 * - HOVEL_WORKSPACE_FOLDER opens one at launch. Both ask first for a folder not yet trusted.
 * - HOVEL_WORKSPACE_PROBE, with HOVEL_WORKSPACE_FOLDER, runs the probe ([WorkspaceProbe]) against that window.
 */

private const val TAG = "workspace-development"

/** A development build's workspaces, for `main.kt`: windows on trusted folders, their Develop menu, and the probe when asked for. */
internal fun desktopWorkspaces(environment: Map<String, String>, support: Path): DesktopWorkspaces? = DevelopmentWorkspaces(environment, support)

/** [DesktopWorkspaces] over one [WorkspaceWindows], asking through the probe's confirmer when HOVEL_WORKSPACE_PROBE is set. */
private class DevelopmentWorkspaces(private val environment: Map<String, String>, support: Path) : DesktopWorkspaces {
    private val probe = probeConfirmer(environment)
    private val windows = WorkspaceWindows(folderTrustFile(support), probe ?: SheetConfirmer) { spikeSessionHost(environment, support) }

    override fun confirmQuit(appName: String, onCancel: () -> Unit, quit: () -> Unit) = windows.confirmQuit(appName, onCancel, quit)

    override fun exitStarted() = windows.exitStarted()

    @Composable
    override fun DevelopItems(menu: MenuScope, owner: Frame) {
        menu.Item("Open Workspace…", onClick = { openWorkspaceFromPicker(windows, owner) })
        // The profile's settings will own this.
        menu.Item("Forget Trusted Folders", onClick = { windows.forgetTrustedFolders() })
    }

    @Composable
    override fun Windows(exit: () -> Unit, requestQuit: () -> Unit) {
        WorkspaceWindowsHost(windows)
        DevelopmentWorkspaceLaunch(windows, environment, probe, exit, requestQuit)
    }

    override fun close() = windows.close()
}

/**
 * The terminal spike's session host: the player's login shell, Hovel's startup files in the Caches
 * folder, Neovim's sockets beside them, and one `bin` folder kept first in every project's `PATH`. That folder is
 * `HOVEL_WORKSPACE_BIN` when set (development), else `<support>/workspace/bin`, where the tool
 * manager will write its wrappers. `HOVEL_WORKSPACE_SHELL` (development) tries another shell in
 * place of the login shell.
 */
internal fun spikeSessionHost(environment: Map<String, String>, support: Path): SessionHost {
    val shell = developmentShell(environment) ?: loginShell(readDirectoryShell(), environment)
    val files = ShellFiles.install(ShellFiles.defaultFolder())
    val bin = environment["HOVEL_WORKSPACE_BIN"]?.takeIf { Path.of(it).isAbsolute } ?: support.resolve("workspace/bin").toString()
    val launch = ShellLaunch(shell, environment, files)
    val host = PtySessionHost(EditorSockets(EditorSockets.defaultFolder())) { spec, toolArguments -> launch.command(spec.launch, listOf(bin), toolArguments) }
    log(LogLevel.Info, TAG, "Workspace shell ${ShellKind.of(shell)}; natives ${System.getProperty(PTY4J_NATIVE_FOLDER) ?: "unpacked by pty4j"} and ${System.getProperty(JNA_BOOT_PATH) ?: "unpacked by JNA"}")
    return host
}

/** `HOVEL_WORKSPACE_SHELL` when it names an executable by absolute path; otherwise null, and the login shell is used. */
private fun developmentShell(environment: Map<String, String>): String? {
    val shell = environment["HOVEL_WORKSPACE_SHELL"]?.takeIf { it.isNotBlank() } ?: return null
    if (isUsableShell(shell)) return shell
    log(LogLevel.Warn, TAG, "HOVEL_WORKSPACE_SHELL is not an executable at an absolute path; using the login shell")
    return null
}

/** Develop → Open Workspace…: the native folder picker, then a window on the chosen folder, if trusted. AWT thread. */
internal fun openWorkspaceFromPicker(workspaces: WorkspaceWindows, owner: Frame) {
    try {
        val folder = NativeFolderPicker { owner }.choose("Open a workspace on a folder", null) ?: return
        if (workspaces.open(folder) == null) log(LogLevel.Info, TAG, "The workspace was not opened: the folder was not trusted")
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        log(LogLevel.Error, TAG, "Could not open a workspace", error)
    }
}

/**
 * Opens HOVEL_WORKSPACE_FOLDER at launch, asking first when it is not trusted. With HOVEL_WORKSPACE_PROBE,
 * the probe opens it and runs its steps, answering through [confirmer]. [exit] ends the app at once;
 * [requestQuit] is ⌘Q's path, which asks first when work would be lost.
 */
@Composable
internal fun DevelopmentWorkspaceLaunch(
    workspaces: WorkspaceWindows,
    environment: Map<String, String>,
    confirmer: ProbeConfirmer?,
    exit: () -> Unit,
    requestQuit: () -> Unit,
) {
    LaunchedEffect(Unit) {
        val folder = environment["HOVEL_WORKSPACE_FOLDER"]?.takeIf { it.isNotBlank() }?.let(Path::of) ?: return@LaunchedEffect
        val steps = environment["HOVEL_WORKSPACE_PROBE"]?.takeIf { it.isNotBlank() }
        if (steps != null && confirmer != null) {
            WorkspaceProbe(workspaces, folder, Path.of(steps), confirmer, exit, requestQuit).start()
            return@LaunchedEffect
        }
        if (steps != null) log(LogLevel.Warn, TAG, "HOVEL_WORKSPACE_PROBE is set but the windows have no probe confirmer")
        try {
            if (workspaces.open(folder) == null) log(LogLevel.Info, TAG, "HOVEL_WORKSPACE_FOLDER was not opened: the folder was not trusted")
        } catch (error: Throwable) {
            rethrowIfFatal(error)
            log(LogLevel.Error, TAG, "Could not open HOVEL_WORKSPACE_FOLDER", error)
        }
    }
}
