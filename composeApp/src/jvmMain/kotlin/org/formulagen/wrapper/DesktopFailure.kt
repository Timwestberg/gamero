package org.formulagen.wrapper

import androidx.compose.ui.window.WindowExceptionHandler
import java.util.concurrent.atomic.AtomicBoolean

/** Compose handles window exceptions itself, so an uncaught-thread handler alone is insufficient. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal class DesktopFailure(private val requestExit: () -> Unit) : WindowExceptionHandler, Thread.UncaughtExceptionHandler {
    private val failed = AtomicBoolean(false)
    val hasFailed: Boolean get() = failed.get()

    override fun onException(throwable: Throwable) {
        failed.set(true)
        System.err.println("GAMERO_RUNTIME_FAILED: ${throwable.javaClass.name}")
        throwable.printStackTrace(System.err)
        requestExit()
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) = onException(throwable)
}
