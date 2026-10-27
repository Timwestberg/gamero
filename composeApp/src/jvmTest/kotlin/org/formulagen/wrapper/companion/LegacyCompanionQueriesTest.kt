package org.formulagen.wrapper.companion

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.cef.callback.CefQueryCallback
import kotlin.test.*

/** Version 1 is the legacy companion protocol only, and a cancelled call never ends the page's context. */
class LegacyCompanionQueriesTest {
    private class Answers : CefQueryCallback {
        val successes = CopyOnWriteArrayList<String>()
        val failures = CopyOnWriteArrayList<Pair<Int, String>>()
        override fun success(response: String) { successes += response }
        override fun failure(errorCode: Int, errorMessage: String) { failures += errorCode to errorMessage }
    }

    private val watch = """{"version":1,"method":"watchContext","args":{}}"""
    private fun call(context: String, method: String) = """{"version":1,"contextId":"$context","method":"$method","args":{}}"""
    private fun contextOf(answers: Answers) = Json.parseToJsonElement(answers.successes.single()).jsonObject.getValue("contextId").jsonPrimitive.content

    @Test fun cancellingOneCallVoidsOnlyThatCallAndOtherProductsAreRefused() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dispatched = CopyOnWriteArrayList<String>()
        val entered = CountDownLatch(1)
        var ended = 0
        val legacy = LegacyCompanionQueries({ method, _ ->
            dispatched += method
            if (method == "prepareConnection") { entered.countDown(); awaitCancellation() }
            buildJsonObject { put("version", 1) }
        }, { ended++ }, scope)
        try {
            val opened = Answers()
            legacy.onQuery(watch, true, "frame", 1, opened)
            val context = contextOf(opened)
            assertEquals(1, ended, "opening a v1 context voids an approval from the previous one")

            val waiting = Answers()
            legacy.onQuery(call(context, "prepareConnection"), false, "frame", 2, waiting)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(legacy.onQueryCanceled(2))
            assertEquals(2, ended)
            assertFalse(legacy.onQueryCanceled(99), "an unknown query is not version 1's")

            val capabilities = Answers()
            legacy.onQuery(call(context, "capabilities"), false, "frame", 3, capabilities)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (capabilities.successes.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(1, capabilities.successes.size, "the context survived the cancelled call")

            for (moved in listOf("pet.status", "providers.status", "games.launch")) {
                val refused = Answers()
                legacy.onQuery(call(context, moved), false, "frame", 4, refused)
                assertEquals(listOf(400 to "Unknown native method"), refused.failures.toList())
            }
            assertEquals(listOf("prepareConnection", "capabilities"), dispatched.toList())

            val other = Answers()
            legacy.onQuery(call(context, "capabilities"), false, "another-frame", 5, other)
            assertEquals(403, other.failures.single().first)
            assertTrue(legacy.onQueryCanceled(1))
            val expired = Answers()
            legacy.onQuery(call(context, "capabilities"), false, "frame", 6, expired)
            assertEquals(403, expired.failures.single().first)
        } finally { legacy.close(); scope.cancel() }
    }
}
