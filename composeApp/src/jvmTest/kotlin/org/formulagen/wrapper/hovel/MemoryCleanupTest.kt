package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*

class MemoryCleanupTest {
    private class Remote : HovelTransport {
        var epoch = 1L
        var acknowledged: Long? = null
        var offline = false
        var loseAck = false
        var acknowledgements = 0
        override fun post(route: String, body: JsonObject): JsonObject {
            check(!offline)
            if (route == "memory/cleanup/ack") {
                check(body.getValue("epoch").jsonPrimitive.long == epoch)
                acknowledged = epoch
                acknowledgements++
                if (loseAck) { loseAck = false; error("Acknowledgement response lost") }
            }
            val expected = body["expectedEpoch"]?.jsonPrimitive?.long
            return buildJsonObject {
                put("epoch", epoch)
                put("acknowledgedEpoch", acknowledged?.let(::JsonPrimitive) ?: JsonNull)
                put("valid", acknowledged == epoch && (expected == null || expected == epoch))
            }
        }
    }
    private fun marker() = Files.createTempDirectory("memory-cleanup").resolve("generation")

    @Test fun generationZeroPreservesOrdinaryContextButARegressedServerCannotResume() {
        val remote = Remote().apply { epoch = 0 }
        val gate = MemoryCleanup(remote, marker())
        var erased = 0
        gate.register { erased++ }
        assertTrue(gate.poll())
        assertEquals(0, erased)
        remote.epoch = 1
        assertTrue(gate.poll())
        assertEquals(1, erased)
        remote.epoch = 0
        assertFalse(gate.poll())
        assertFalse(gate.ready)
        assertEquals(1L, remote.acknowledged)
    }

    @Test fun cleanupWaitsForWritersAndRestartKeepsFreshContext() {
        val remote = Remote()
        val marker = marker()
        val gate = MemoryCleanup(remote, marker)
        var erased = 0
        gate.register { erased++ }
        assertFalse(gate.ready)
        assertTrue(gate.poll())
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val thread = Thread {
            gate.run({ cancelled.countDown() }) { entered.countDown(); check(finish.await(5, TimeUnit.SECONDS)) }
        }.apply { start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        remote.epoch++
        assertFalse(gate.poll())
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertEquals(1, erased)
        assertEquals(1L, remote.acknowledged)
        assertFails { gate.run({}) { fail("New work must wait") } }
        finish.countDown(); thread.join(5000); assertFalse(thread.isAlive)
        assertTrue(gate.poll())
        assertEquals(2, erased)
        assertEquals(2L, remote.acknowledged)
        val restarted = MemoryCleanup(remote, marker)
        restarted.register { fail("Current context must survive restart") }
        assertTrue(restarted.poll())
        restarted.check()
    }

    @Test fun failedCleanupAndLostAcknowledgementNeverAuthorizePartialCleanup() {
        val remote = Remote()
        val marker = marker()
        val gate = MemoryCleanup(remote, marker)
        var failCleanup = true
        var erased = 0
        gate.register { erased++; check(!failCleanup) }
        assertFalse(gate.poll())
        assertFalse(Files.exists(marker))
        assertEquals(0, remote.acknowledgements)
        failCleanup = false; remote.loseAck = true
        assertFalse(gate.poll())
        assertFalse(gate.ready)
        assertEquals("1", Files.readString(marker))
        assertTrue(gate.poll())
        assertEquals(2, erased)
        assertEquals(1, remote.acknowledgements)
    }

    @Test fun deletionDuringCleanupAndOfflineChecksFailClosed() {
        val remote = Remote()
        val gate = MemoryCleanup(remote, marker())
        var race = true
        gate.register { if (race) { remote.epoch++; race = false } }
        assertFalse(gate.poll())
        assertEquals(0, remote.acknowledgements)
        assertTrue(gate.poll())
        remote.offline = true
        assertFails { gate.check() }
        assertFalse(gate.ready)
        assertFalse(gate.poll())
        remote.offline = false
        assertTrue(gate.poll())
        remote.epoch++
        assertFails { gate.check() }
        assertFalse(gate.ready)
        assertTrue(gate.poll())
        gate.check()
    }
}
