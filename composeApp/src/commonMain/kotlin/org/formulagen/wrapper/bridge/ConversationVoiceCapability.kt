package org.formulagen.wrapper.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Voice in a conversation on the Mac: dictation fills the page's draft, and Talk is a voice
 * conversation with the coordinator. The microphone serves one conversation and one use at a time.
 * Desktop only; the conversation runner brings the implementation.
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

/** Dictation into a conversation's draft: [partial] so far, and [text] heard once `done`, each at most a message. */
@Serializable
data class ConversationDictation(
    val state: DictationState,
    val partial: String? = null,
    val text: String? = null,
    val notice: String? = null,
) {
    init {
        require(partial == null || partial.length in 1..CompanionLimits.MESSAGE)
        require(text == null || text.length in 1..CompanionLimits.MESSAGE)
        require(notice == null || notice.length in 1..ChatLimits.NOTICE)
    }
}

/** Talk's microphone, with the live transcript of the turn being heard, at most a message. */
@Serializable
data class ConversationHearing(val state: HearingState = HearingState.OFF, val partial: String? = null, val notice: String? = null) {
    init {
        require(partial == null || partial.length in 1..CompanionLimits.MESSAGE)
        require(notice == null || notice.length in 1..ChatLimits.NOTICE)
    }
}

/**
 * What the microphone and speaker are doing, for the conversation they serve ([conversationId], null
 * when none). [dictation] is present only while the page dictates, or with a notice until it starts
 * again.
 */
@Serializable(with = ConversationVoiceStatusSerializer::class)
data class ConversationVoiceStatus(
    val conversationId: String?,
    val hearing: ConversationHearing = ConversationHearing(),
    val voice: ChatVoice = ChatVoice(),
    val dictation: ConversationDictation? = null,
)

/** The status is written through [Wire], whose `conversationId` is an [ExplicitNull], so the key stays when there is none. */
internal object ConversationVoiceStatusSerializer : KSerializer<ConversationVoiceStatus> {
    @Serializable
    private class Wire(
        val conversationId: ExplicitNull<String>,
        val hearing: ConversationHearing,
        val voice: ChatVoice,
        val dictation: ConversationDictation? = null,
    )

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: ConversationVoiceStatus) = encoder.encodeSerializableValue(
        Wire.serializer(), Wire(ExplicitNull(value.conversationId), value.hearing, value.voice, value.dictation),
    )
    override fun deserialize(decoder: Decoder): ConversationVoiceStatus {
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        val conversationId = wire.conversationId.value
        require(conversationId == null || SharedRules.isRecordId(conversationId))
        return ConversationVoiceStatus(conversationId, wire.hearing, wire.voice, wire.dictation)
    }
}

/** Mirrors event-contracts/host-bridge/conversation-voice.ts. */
object ConversationVoiceMethods {
    val DICTATION = BridgeMethod(
        "conversationVoice.dictation", ConversationDictationArgs.serializer(), ConversationVoiceStatus.serializer(),
    ) { SharedRules.isRecordId(it.conversationId) }
    val HEARING = BridgeMethod(
        "conversationVoice.hearing", ConversationHearingArgs.serializer(), ConversationVoiceStatus.serializer(),
    ) { SharedRules.isRecordId(it.conversationId) }
    val VOICE_STATUS = BridgeMethod("conversationVoice.voiceStatus", NoArgs.serializer(), ConversationVoiceStatus.serializer())

    fun pack(voice: Capability<ConversationVoiceCapability>): CapabilityPack = binder.pack(voice)

    internal val binder = CapabilityBinder("conversationVoice", listOf<CapabilityHandler<ConversationVoiceCapability>>(
        DICTATION.handle { voice, args -> voice.dictation(args) },
        HEARING.handle { voice, args -> voice.hearing(args) },
        VOICE_STATUS.handle { voice, _ -> voice.voiceStatus() },
    ))
}
