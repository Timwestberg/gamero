package org.formulagen.wrapper.workspace

import com.jediterm.core.util.TermSize as JediTermSize
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTtyConnectorTest {
    private fun connector(onExit: (Int) -> Unit = {}) = SessionTtyConnector("Shell", onExit).also { it.connect(FakeSession()) }

    /** Everything the connector yields until it ends, reading [chunk] characters at a time. */
    private fun SessionTtyConnector.drain(chunk: Int = 64): String = buildString {
        val buffer = CharArray(chunk)
        while (true) {
            val count = read(buffer, 0, chunk)
            if (count < 0) break
            append(buffer, 0, count)
        }
    }

    private fun SessionTtyConnector.push(bytes: ByteArray) = output(bytes, bytes.size)

    @Test fun aCharacterSplitAcrossTwoReadsStaysWhole() {
        for (text in listOf("é", "€", "日", "😀", "👩‍💻")) {
            val bytes = text.toByteArray()
            for (split in 1 until bytes.size) {
                val connector = connector()
                connector.push(bytes.copyOfRange(0, split))
                connector.push(bytes.copyOfRange(split, bytes.size))
                connector.exited(0)
                assertEquals(text, connector.drain(), "\"$text\" split after byte $split")
            }
        }
    }

    @Test fun textArrivingOneByteAtATimeDecodes() {
        val text = "ls -la ✓ café 日本語 😀\r\n\u001b[1;32mok\u001b[0m"
        val connector = connector()
        text.toByteArray().forEach { connector.push(byteArrayOf(it)) }
        connector.exited(0)
        assertEquals(text, connector.drain())
    }

    @Test fun smallReadsGetSurrogatePairsInOrder() {
        val connector = connector()
        connector.push("a😀b".toByteArray())
        connector.exited(0)
        assertEquals("a😀b", connector.drain(chunk = 1))
    }

    @Test fun malformedBytesBecomeReplacementCharacters() {
        val connector = connector()
        connector.push(byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte(), 0xC3.toByte(), 'c'.code.toByte()))
        connector.exited(0)
        assertEquals("a�b�c", connector.drain())
    }

    @Test fun aCharacterCutOffByTheExitBecomesOneReplacement() {
        val connector = connector()
        connector.push("ok".toByteArray() + "😀".toByteArray().copyOfRange(0, 2))
        connector.exited(1)
        assertEquals("ok�", connector.drain())
    }

    @Test fun keepsItsOwnCopyOfEachChunk() {
        val connector = connector()
        val buffer = "abc".toByteArray()
        connector.output(buffer, 2)
        buffer.fill('z'.code.toByte())
        connector.exited(0)
        assertEquals("ab", connector.drain())
    }

    @Test fun readBlocksUntilOutputArrives() {
        val connector = connector()
        var read = ""
        val reader = thread { read = connector.drain() }
        Thread.sleep(150)
        assertTrue(reader.isAlive, "read waits for output")
        connector.push("late".toByteArray())
        connector.exited(0)
        reader.join(2_000)
        assertEquals("late", read)
    }

    @Test fun theExitEndsReadsAndWaitFor() {
        val exits = AtomicInteger(0)
        val connector = connector { exits.incrementAndGet() }
        assertTrue(connector.isConnected)
        connector.push("bye".toByteArray())
        connector.exited(42)
        connector.exited(42)
        assertFalse(connector.isConnected)
        assertEquals(42, connector.waitFor())
        assertEquals("bye", connector.drain())
        assertEquals(1, exits.get(), "onExit runs once")
    }

    @Test fun keysAndResizesGoToTheAttachment() {
        val session = FakeSession()
        val connector = SessionTtyConnector("Shell").also { it.connect(session) }
        connector.write("ls\r")
        connector.write(byteArrayOf(0x1b, '['.code.toByte(), 'A'.code.toByte()))
        connector.resize(JediTermSize(120, 40))
        assertEquals(listOf("ls\r", "\u001b[A"), session.input)
        assertEquals(listOf(TermSize(120, 40)), session.sizes)
    }

    @Test fun closingDetachesAndEndsReading() {
        val session = FakeSession()
        val connector = SessionTtyConnector("Shell").also { it.connect(session) }
        connector.push("x".toByteArray())
        connector.close()
        assertTrue(session.detached)
        assertFalse(connector.isConnected)
        // Output after the close is dropped rather than waiting for a reader that has gone, and reads end.
        connector.push("ignored".toByteArray())
        assertEquals("x", connector.drain())
    }

    @Test fun outputAfterTheEndIsDroppedOnceReadingHasEnded() {
        val connector = connector()
        connector.push("last".toByteArray())
        connector.exited(0)
        assertEquals("last", connector.drain())
        // A late chunk must not reach the flushed decoder, which would throw.
        connector.push("late".toByteArray())
        assertEquals(-1, connector.read(CharArray(8), 0, 8))
    }

    @Test fun aFailingExitHandlerIsContained() {
        val connector = connector { error("boom") }
        connector.exited(0)
        assertEquals(0, connector.waitFor())
    }
}
