package org.formulagen.wrapper

import kotlinx.coroutines.*
import kotlin.test.*

class BrowserLifecycleTest {
    @Test fun shutdownWaitsForInitializationBeforeDisposal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var disposed = false
        val lifecycle = BrowserLifecycle(dispose = { disposed = true })
        lifecycle.start { entered.complete(Unit); release.await() }
        entered.await()
        val closing = async { lifecycle.shutdown() }
        yield()
        assertFalse(disposed)
        release.complete(Unit)
        assertTrue(closing.await())
        assertTrue(disposed)
    }
    @Test fun stalledSetupHasABoundedAbort() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val lifecycle = BrowserLifecycle(dispose = { fail("Uninitialized browser must not be disposed") })
        lifecycle.start { entered.complete(Unit); awaitCancellation() }
        entered.await()
        assertFalse(lifecycle.shutdown(timeoutMillis = 30))
    }
    @Test fun blockingNativeDisposalDoesNotDefeatTheDeadline() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val lifecycle = BrowserLifecycle(dispose = { entered.complete(Unit); release.await() })
        lifecycle.start { }
        try {
            val closing = async { lifecycle.shutdown(timeoutMillis = 250) }
            entered.await()
            assertFalse(closing.await())
        } finally { release.countDown() }
    }

    @Test fun nativeTerminationMustCompleteBeforeSuccess() = runBlocking {
        val disposed = CompletableDeferred<Unit>()
        val terminated = CompletableDeferred<Unit>()
        val lifecycle = BrowserLifecycle(dispose = { disposed.complete(Unit) }, awaitTermination = { terminated.await() })
        lifecycle.start { }
        val closing = async { lifecycle.shutdown() }
        disposed.await()
        assertFalse(closing.isCompleted)
        terminated.complete(Unit)
        assertTrue(closing.await())
    }

}
