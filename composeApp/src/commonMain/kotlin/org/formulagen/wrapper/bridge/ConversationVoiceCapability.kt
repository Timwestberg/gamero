package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Voice in a conversation on the Mac: dictation fills the page's draft, and Talk is a voice
 * conversation with the coordinator. The microphone serves one conversation and one use at a time.
 * Desktop only; the conversation runner brings the implementation, replacing `chat.dictation` and
 * `chat.hearing`.
 */
interface ConversationVoiceCapability {
    suspend fun dictation(args: ConversationDictationArgs): ConversationVoiceStatus
    suspend fun hearing(args: ConversationHearingArgs): ConversationVoiceStatus
    suspend fun voiceStatus(): ConversationVoiceStatus
}

/** Dictation names no member and no effort: nothing is sent. */
@Serializable
data class ConversationDictationArgs(val conversationId: String, val action: DictationAction)

/** A heard turn goes to the conversation's coordinator, so Talk names no member and no effort. */
@Serializable
data class ConversationHearingArgs(val conversationId: String, val action: HearingAction)

/**
 * What the microphone and speaker are doing, for the conversation they serve ([conversationId], null
 * when none). [dictation] is present only while the page dictates, or with a notice until it starts
 * again. The contract's `conversationId` is `nullable`, not `optional`, so the key is always written
 * ([ConversationVoiceStatusSerializer]).
 */
@Serializable(with = ConversationVoiceStatusSerializer::class)
data class ConversationVoiceStatus(
    val conversationId: String?,
    val hearing: ChatHearing = ChatHearing(),
    val voice: ChatVoice = ChatVoice(),
    val dictation: ChatDictation? = null,
)

/** The bridge leaves out null fields, so the status is written through [Wire], whose `conversationId` writes its own `null`. */
internal object ConversationVoiceStatusSerializer : KSerializer<ConversationVoiceStatus> {
    @Serializable
    private class Wire(val conversationId: Served, val hearing: ChatHearing, val voice: ChatVoice, val dictation: ChatDictation? = null)

    @Serializable(with = ServedSerializer::class)
    private class Served(val conversationId: String?)

    @OptIn(ExperimentalSerializationApi::class)
    private object ServedSerializer : KSerializer<Served> {
        override val descriptor: SerialDescriptor = String.serializer().descriptor.nullable
        override fun serialize(encoder: Encoder, value: Served) =
            value.conversationId?.let(encoder::encodeString) ?: encoder.encodeNull()
        override fun deserialize(decoder: Decoder) =
            Served(if (decoder.decodeNotNullMark()) decoder.decodeString() else decoder.decodeNull())
    }

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ConversationVoiceStatus) = encoder.encodeSerializableValue(
        Wire.serializer(), Wire(Served(value.conversationId), value.hearing, value.voice, value.dictation),
    )
    override fun deserialize(decoder: Decoder): ConversationVoiceStatus {
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        return ConversationVoiceStatus(wire.conversationId.conversationId, wire.hearing, wire.voice, wire.dictation)
    }
}

/** Mirrors event-contracts/host-bridge/conversation-voice.ts. */
object ConversationVoiceMethods {
    private fun uuid(value: String) = BridgeProtocol.UUID_PATTERN.matches(value)

    val DICTATION = BridgeMethod(
        "conversationVoice.dictation", ConversationDictationArgs.serializer(), ConversationVoiceStatus.serializer(),
    ) { uuid(it.conversationId) }
    val HEARING = BridgeMethod(
        "conversationVoice.hearing", ConversationHearingArgs.serializer(), ConversationVoiceStatus.serializer(),
    ) { uuid(it.conversationId) }
    val VOICE_STATUS = BridgeMethod("conversationVoice.voiceStatus", NoArgs.serializer(), ConversationVoiceStatus.serializer())

    fun pack(voice: Capability<ConversationVoiceCapability>): CapabilityPack = binder.pack(voice)

    internal val binder = CapabilityBinder("conversationVoice", listOf<CapabilityHandler<ConversationVoiceCapability>>(
        DICTATION.handle { voice, args -> voice.dictation(args) },
        HEARING.handle { voice, args -> voice.hearing(args) },
        VOICE_STATUS.handle { voice, _ -> voice.voiceStatus() },
    ))
}
