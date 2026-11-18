package org.formulagen.wrapper.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The smoke marker hook: one call per document, for the trusted main frame only. */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstAnswerTest {
    private val answers = mutableListOf<Pair<String?, String>>()
    private val replies = mutableListOf<String>()
    private val channel = object : BridgeChannel {
        override fun reply(json: String) {
            replies += json
        }

        override fun event(json: String) {}
    }

    private fun TestScope.bridge(): HostBridge {
        var next = 0
        return HostBridge(
            product = "Hovel",
            trustedUrl = TRUSTED_URL,
            capabilities = HostCapabilities(HostPlatform.IOS, "1.0.0", Capability.Unsupported, Capability.Unsupported, Capability.Unsupported),
            context = backgroundScope.coroutineContext,
            newContextId = { CONTEXTS[next++] },
            onFirstAnswer = { method, outcome -> answers += method to outcome },
        )
    }

    private fun TestScope.send(bridge: HostBridge, raw: String, mainFrame: Boolean = true, url: String = TRUSTED_URL) {
        bridge.receive(raw, PageFrame(mainFrame, url), channel)
        advanceUntilIdle()
        runCurrent()
    }

    private fun call(contextId: String, method: String) = """{"version":2,"contextId":"$contextId","method":"$method","args":{}}"""

    @Test
    fun aRefusedFrameDoesNotCountAndTheTrustedMainFrameCountsOnce() = runTest {
        val bridge = bridge()
        send(bridge, WATCH, url = "https://evil.example/")
        send(bridge, WATCH, mainFrame = false)
        assertEquals(emptyList(), answers)
        assertEquals(2, replies.size)

        send(bridge, WATCH)
        send(bridge, call(CONTEXTS[0], "host.info"))
        send(bridge, call(CONTEXTS[0], "haptic.play"))
        assertEquals(listOf<Pair<String?, String>>("watchContext" to "ok"), answers)
    }

    @Test
    fun aNewDocumentCountsAgainWithItsErrorCode() = runTest {
        val bridge = bridge()
        send(bridge, WATCH)
        bridge.documentChanged()
        send(bridge, call(CONTEXTS[0], "host.info"))
        bridge.documentChanged()
        send(bridge, "{not json")
        bridge.documentChanged()
        send(bridge, """{"version":2,"contextId":"${CONTEXTS[0]}","method":"Bad\nName","args":{}}""")
        assertEquals(
            listOf<Pair<String?, String>>("watchContext" to "ok", "host.info" to "expired", null to "invalid", "Bad\nName" to "invalid"),
            answers,
        )
    }

    @Test
    fun anEndedWatchCountsAsANewDocumentForTransportsThatOnlySeeThat() = runTest {
        val bridge = bridge()
        send(bridge, WATCH)
        bridge.endContext(CONTEXTS[0])
        send(bridge, WATCH)
        send(bridge, call(CONTEXTS[1], "host.info"))
        assertEquals(listOf<Pair<String?, String>>("watchContext" to "ok", "watchContext" to "ok"), answers)
    }

    private companion object {
        const val TRUSTED_URL = "https://hovel.example/hovel.html"
        const val WATCH = """{"version":2,"method":"watchContext","args":{}}"""
        val CONTEXTS = listOf("6f1c2d3e-4a5b-4c6d-8e7f-901234567890", "0b8e4b52-3c1d-4e2f-9a6b-7c8d9e0f1a2b")
    }
}
