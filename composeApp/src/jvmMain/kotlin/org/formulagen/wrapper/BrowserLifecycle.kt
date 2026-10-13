package org.formulagen.wrapper

import kotlinx.coroutines.*

/** Own browser initialization beyond composition, then await bounded shutdown off the UI thread. */
internal class BrowserLifecycle(
    private val dispose: suspend () -> Unit,
    private val awaitTermination: suspend () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var initialization: Job? = null

    fun start(initialize: suspend () -> Unit) {
        check(initialization == null)
        initialization = scope.launch { initialize() }
    }

    suspend fun shutdown(timeoutMillis: Long = 15_000): Boolean {
        return try {
            withTimeoutOrNull(timeoutMillis) {
                initialization?.join()
                if (initialization != null) scope.async { dispose(); awaitTermination() }.await()
                true
            } ?: false
        } catch (_: Exception) { false } finally { scope.cancel() }
    }
}
