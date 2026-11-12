package org.formulagen.wrapper.conversation.voice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.bridge.*

/** Generic dictation shares the existing recognizer and microphone lease with Talk. */
internal class DesktopVoiceInput(private val ear: Hearing, private val speech: ChatSpeech) : VoiceInputCapability, VoicePlaybackCapability, AutoCloseable {
    private val sessions = VoiceInputSessions()
    private val gate = Any()
    private val clock = java.util.Timer("composer-capture", true)
    private var deadline: java.util.TimerTask? = null
    /** Native backstop also closes capture when the web document disappears. */
    private fun bound(id: String, milliseconds: Long) {
        deadline?.cancel()
        deadline = object : java.util.TimerTask() {
            override fun run() = synchronized(gate) {
                if (sessions.owns(id)) { ear.endSession(); sessions.update(id, "error", reason = "timeout") }
                Unit
            }
        }.also { clock.schedule(it, milliseconds) }
    }
    override suspend fun availability() = VoiceInputAvailability(if (ear.busyElsewhere) "unavailable" else "ready", true, if (ear.busyElsewhere) "busy" else null)
    override suspend fun start(args: VoiceInputStart): VoiceInputSnapshot = synchronized(gate) {
        if (sessions.contains(args.sessionId)) return@synchronized sessions.read(args.sessionId)
        val next = sessions.begin(args.sessionId)
        if (next.state != "starting") return@synchronized next
        val id = args.sessionId
        bound(id, 120_000)
        val started = ear.down(
            { words -> synchronized(gate) { if (sessions.owns(id)) sessions.update(id, sessions.read(id).state, partial = words) } },
            { synchronized(gate) { if (sessions.owns(id)) sessions.update(id, "error", reason = "unavailable") } },
        )
        if (!started) sessions.update(id, "error", reason = "busy") else sessions.update(id, "listening")
    }
    override suspend fun stop(args: VoiceInputStop): VoiceInputSnapshot {
        val shouldFinish = synchronized(gate) {
            if (!sessions.owns(args.sessionId)) return sessions.update(args.sessionId, "cancelled")
            if (sessions.read(args.sessionId).state == "stopping") return sessions.read(args.sessionId)
            if (args.disposition == "discard") {
                ear.endSession()
                return sessions.update(args.sessionId, "cancelled")
            }
            bound(args.sessionId, 5_000)
            sessions.update(args.sessionId, "stopping")
            true
        }
        if (shouldFinish) withContext(Dispatchers.IO) {
            ear.up { result -> synchronized(gate) { sessions.update(args.sessionId, "done", final = result.text) } }
        }
        return synchronized(gate) {
            if (sessions.read(args.sessionId).state == "stopping") sessions.update(args.sessionId, "done", final = "") else sessions.read(args.sessionId)
        }
    }
    override suspend fun status(args: VoiceInputScope) = synchronized(gate) { sessions.read(args.sessionId) }
    private var speechSession: String? = null
    private var speechRevision: Int? = null
    private val speechStops = linkedSetOf<String>()
    override suspend fun speak(args: LocalSpeechArgs): LocalSpeechStatus = say(args, replace = true)
    override suspend fun append(args: LocalSpeechArgs): LocalSpeechStatus = say(args, replace = false)
    /**
     * The rules of [VoicePlaybackCapability] over the Mac's shared speaker. This session owns the speaker while the
     * playback revision is the one it spoke at; Talk, or another session, taking the speaker changes the revision.
     * Every piece goes through [ChatSpeech.append], so the next sentence is synthesized while this one plays.
     */
    private fun say(args: LocalSpeechArgs, replace: Boolean): LocalSpeechStatus = synchronized(gate) {
        val id = args.sessionId
        if (id in speechStops) return@synchronized LocalSpeechStatus(id, "cancelled")
        val mine = speechSession == id && speechRevision == speech.playbackRevision()
        val busy = speech.status().state.name in setOf("SPEAKING", "PREPARING")
        if (busy && !mine) return@synchronized LocalSpeechStatus(id, "error")
        if (busy && replace) speech.stop()
        speechSession = id
        speechRevision = speech.playbackRevision()
        speech.append(args.text)
        playbackStatus(id)
    }
    override suspend fun stopSpeech(args: VoiceInputScope): LocalSpeechStatus = synchronized(gate) {
        speechStops.add(args.sessionId)
        while (speechStops.size > 32) speechStops.remove(speechStops.first())
        if (speechSession == args.sessionId && speechRevision == speech.playbackRevision()) speech.stop()
        LocalSpeechStatus(args.sessionId, "cancelled")
    }
    override suspend fun speechStatus(args: VoiceInputScope) = synchronized(gate) { playbackStatus(args.sessionId) }
    override suspend fun voices(): PlaybackVoices = synchronized(gate) { listedVoices() }
    /** The Mac's own voice row does the same: [ChatSpeech.previewVoice] stops the speaker, switches and says hello. */
    override suspend fun chooseVoice(args: PlaybackVoiceChoice): PlaybackVoices = synchronized(gate) {
        if (speech.voiceChoices().any { it.id == args.voiceId }) speech.previewVoice(args.voiceId)
        listedVoices()
    }
    private fun listedVoices() = playbackVoices(speech.currentVoiceId(), speech.voiceChoices().map { PlaybackVoice(it.id, it.name) })
    private fun playbackStatus(id: String): LocalSpeechStatus = LocalSpeechStatus(id, when {
        id in speechStops -> "cancelled"
        speechSession != id || speechRevision != speech.playbackRevision() -> "error"
        speech.status().state.name in setOf("UNAVAILABLE", "ERROR") -> "error"
        speech.status().state.name in setOf("SPEAKING", "PREPARING") -> "speaking"
        else -> "done"
    })
    /** Document teardown releases capture without waiting for recognition. */
    fun invalidate() = synchronized(gate) { deadline?.cancel(); sessions.cancelAll(); ear.endSession(); speechSession?.let { speechStops.add(it); if (speechRevision == speech.playbackRevision()) speech.stop() } }
    override fun close() { invalidate(); clock.cancel(); ear.close() }
}
