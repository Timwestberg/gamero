package org.formulagen.wrapper.chat

import org.formulagen.wrapper.voice.HearingResult

/**
 * The Mac's one microphone and recognizer ([ear]), shared by team chat and conversations: one user
 * at a time. A [user] starts a hold or a session only while no other user holds the microphone, and
 * its controls act only on its own capture, so one user ending, pausing or closing never touches
 * another's. Closing this closes the recognizer.
 */
internal class SharedHearing(private val ear: Hearing) : AutoCloseable {
    private var owner: User? = null

    /** A new user's share of the microphone. */
    fun user(): Hearing = User()

    override fun close() = ear.close()

    private inner class User : Hearing {
        private fun owns() = synchronized(this@SharedHearing) { owner === this }

        /** Claim the microphone and run [start]; False, holding nothing, when another user has it or the capture did not start. */
        private fun claim(start: (release: () -> Unit) -> Boolean): Boolean = synchronized(this@SharedHearing) {
            if (owner != null && owner !== this) return false
            val previous = owner
            owner = this
            val started = runCatching { start(::release) }.getOrElse { owner = previous; throw it }
            if (!started) owner = previous
            started
        }

        private fun release() = synchronized(this@SharedHearing) { if (owner === this) owner = null }

        override fun down(onPartial: (String) -> Unit, onFailed: (String) -> Unit) = claim { release ->
            ear.down(onPartial) { message -> release(); onFailed(message) }
        }

        override fun up(onResult: (HearingResult) -> Unit): Boolean {
            if (!owns()) return false
            try { return ear.up(onResult) } finally { release() }
        }

        override fun startSession(
            silenceMs: Int,
            onPartial: (String) -> Unit,
            onUtterance: (HearingResult) -> Unit,
            onFailed: (String) -> Unit,
            onFrame: (voicedMs: Int, chunkMs: Int, vadProb: Float) -> Unit,
        ) = claim { release ->
            ear.startSession(silenceMs, onPartial, onUtterance, { message -> release(); onFailed(message) }, onFrame)
        }

        override fun setPaused(value: Boolean) { if (owns()) ear.setPaused(value) }
        override fun restartUtterance() { if (owns()) ear.restartUtterance() }
        override fun noteEmberSpeaking(text: String) { if (owns()) ear.noteEmberSpeaking(text) }
        override fun noteEmberSilent(text: String) { if (owns()) ear.noteEmberSilent(text) }

        override fun endSession() {
            if (!owns()) return
            ear.endSession()
            release()
        }

        override fun awaitStopped(timeoutMs: Long) = ear.awaitStopped(timeoutMs)

        override val busyElsewhere: Boolean get() = synchronized(this@SharedHearing) { owner != null && owner !== this }

        /** A user that closes lets go of its own capture; the recognizer stays for the others. */
        override fun close() = endSession()
    }
}
