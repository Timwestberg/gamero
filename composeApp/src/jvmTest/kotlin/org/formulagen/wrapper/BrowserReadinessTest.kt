package org.formulagen.wrapper

import kotlinx.coroutines.*
import org.cef.CefApp.CefAppState
import kotlin.test.*

class BrowserReadinessTest {
    @Test fun browserCannotBeExposedBeforeNativeCallback() = runBlocking {
        val registered = CompletableDeferred<() -> Unit>()
        var exposed = false
        var state = CefAppState.INITIALIZING
        val opening = async {
            awaitBrowserInitialization({ registered.complete(it) }, { state })
            exposed = true
        }
        val initialized = registered.await()
        delay(40)
        assertFalse(exposed)
        state = CefAppState.INITIALIZED
        initialized()
        opening.await()
        assertTrue(exposed)
    }

    @Test fun alreadyInitializedCallbackIsNotMissed() = runBlocking {
        awaitBrowserInitialization({ it() }, { CefAppState.INITIALIZED })
    }

    @Test fun nativeInitializationFailureTimesOutWithoutExposingBrowser() = runBlocking {
        assertFailsWith<TimeoutCancellationException> {
            awaitBrowserInitialization({}, { CefAppState.INITIALIZING }, timeoutMillis = 30)
        }
        Unit
    }

    @Test fun terminationBeforeReadyFailsImmediately() = runBlocking {
        for (state in listOf(CefAppState.SHUTTING_DOWN, CefAppState.TERMINATED)) {
            assertFailsWith<IllegalStateException> { awaitBrowserInitialization({}, { state }) }
        }
    }

    @Test fun terminationAfterCallbackStillPreventsExposure() = runBlocking {
        assertFailsWith<IllegalStateException> {
            awaitBrowserInitialization({ it() }, { CefAppState.TERMINATED })
        }
        Unit
    }

    @Test fun composeAndBackgroundFailuresRequestExitAndRemainFailed() {
        var exits = 0
        val failure = DesktopFailure { exits++ }
        assertFalse(failure.hasFailed)
        failure.onException(IllegalStateException("fixture window failure"))
        failure.uncaughtException(Thread.currentThread(), UnsatisfiedLinkError("fixture native failure"))
        assertTrue(failure.hasFailed)
        assertEquals(2, exits)
    }
}
