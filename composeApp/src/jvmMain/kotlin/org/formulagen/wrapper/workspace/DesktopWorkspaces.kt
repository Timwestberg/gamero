package org.formulagen.wrapper.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.MenuScope
import java.awt.Frame

/*
 * What `main.kt` sees of project workspaces. A development build compiles `src/jvmDev`, whose
 * `desktopWorkspaces` builds the windows, the probe and their Develop menu; a release build compiles
 * `src/jvmRelease`, whose `desktopWorkspaces` is null, so no workspace code or terminal library is in
 * it (docs/desktop.md, "Build boundaries").
 */

/** The project workspaces in a development build, as `main.kt` uses them. */
internal interface DesktopWorkspaces : AutoCloseable {
    /** Before the app quits: ask once when work would be lost, then [quit]; [onCancel] if the person keeps the app. AWT thread. */
    fun confirmQuit(appName: String, onCancel: () -> Unit, quit: () -> Unit)

    /** The app's exit has started, by any path: later quit requests are ignored. Any thread. */
    fun exitStarted()

    /** The main window's Develop menu items, in [menu]; [owner] is the main window. */
    @Composable
    fun DevelopItems(menu: MenuScope, owner: Frame)

    /** The workspace windows, in the app's one Compose application. [exit] ends the app at once; [requestQuit] asks first. */
    @Composable
    fun Windows(exit: () -> Unit, requestQuit: () -> Unit)
}

/**
 * For a workspace edge that contains and logs what it catches: rethrows [error] when the JVM itself
 * is failing (out of memory, a stack overflow, an internal error), which should still end the app.
 * Anything else is contained, including a library that will not load ([LinkageError]:
 * `UnsatisfiedLinkError`, `ExceptionInInitializerError`, `NoClassDefFoundError`).
 */
internal fun rethrowIfFatal(error: Throwable) {
    if (error is VirtualMachineError) throw error
}
