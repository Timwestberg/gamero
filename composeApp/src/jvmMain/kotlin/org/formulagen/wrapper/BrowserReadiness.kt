package org.formulagen.wrapper

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.cef.CefApp

/** startup() and getInstance() schedule native work; neither means contexts are usable. */
internal suspend fun awaitBrowserInitialization(
    register: (() -> Unit) -> Unit,
    state: () -> CefApp.CefAppState,
    timeoutMillis: Long = 30_000,
) {
    val ready = CompletableDeferred<Unit>()
    register { ready.complete(Unit) }
    withTimeout(timeoutMillis) {
        while (!ready.isCompleted) {
            check(state() !in setOf(CefApp.CefAppState.SHUTTING_DOWN, CefApp.CefAppState.TERMINATED)) {
                "JCEF terminated before native initialization completed"
            }
            delay(20)
        }
        ready.await()
        check(state() == CefApp.CefAppState.INITIALIZED) { "JCEF stopped before the browser could be exposed" }
    }
}
