package org.formulagen.wrapper.bridge
import kotlinx.serialization.Serializable

/** Playback belongs to a local Talk session, never a remote microphone owner. */
interface VoicePlaybackCapability {
    suspend fun speak(args: LocalSpeechArgs): LocalSpeechStatus
    suspend fun stopSpeech(args: VoiceInputScope): LocalSpeechStatus
    suspend fun speechStatus(args: VoiceInputScope): LocalSpeechStatus
}
@Serializable data class LocalSpeechArgs(val sessionId: String, val text: String)
@Serializable data class LocalSpeechStatus(val sessionId: String, val state: String)
object VoicePlaybackMethods {
    val SPEAK = BridgeMethod("voicePlayback.speak", LocalSpeechArgs.serializer(), LocalSpeechStatus.serializer()) {
        SharedRules.isRecordId(it.sessionId) && it.text.length in 1..CompanionLimits.MESSAGE
    }
    val STOP = BridgeMethod("voicePlayback.stop", VoiceInputScope.serializer(), LocalSpeechStatus.serializer()) { SharedRules.isRecordId(it.sessionId) }
    val STATUS = BridgeMethod("voicePlayback.status", VoiceInputScope.serializer(), LocalSpeechStatus.serializer()) { SharedRules.isRecordId(it.sessionId) }
    fun pack(input: Capability<VoicePlaybackCapability>): CapabilityPack = binder.pack(input)
    internal val binder = CapabilityBinder("voicePlayback", listOf<CapabilityHandler<VoicePlaybackCapability>>(
        SPEAK.handle { input, args -> input.speak(args) }, STOP.handle { input, args -> input.stopSpeech(args) }, STATUS.handle { input, args -> input.speechStatus(args) },
    ))
}
