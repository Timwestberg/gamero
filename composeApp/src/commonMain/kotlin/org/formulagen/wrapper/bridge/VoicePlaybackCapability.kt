package org.formulagen.wrapper.bridge
import kotlinx.serialization.Serializable

/**
 * Playback belongs to a local Talk session, never a remote microphone owner. Every host keeps the same rules:
 * - [speak] starts over: it drops what the session was saying and says the text;
 * - [append] says the text after what the session is saying, and starts speaking when the session is idle or done;
 * - one session speaks at a time: another session's `speak` or `append` answers `error` while the speaker has
 *   anything left to say, and never interrupts it;
 * - a stopped session stays `cancelled`: a late `speak` or `append` cannot start it again;
 * - [speechStatus] is `speaking` until everything queued has been said, then `done`.
 */
interface VoicePlaybackCapability {
    suspend fun speak(args: LocalSpeechArgs): LocalSpeechStatus
    suspend fun append(args: LocalSpeechArgs): LocalSpeechStatus
    suspend fun stopSpeech(args: VoiceInputScope): LocalSpeechStatus
    suspend fun speechStatus(args: VoiceInputScope): LocalSpeechStatus
}
@Serializable data class LocalSpeechArgs(val sessionId: String, val text: String)
@Serializable data class LocalSpeechStatus(val sessionId: String, val state: String)
object VoicePlaybackMethods {
    private fun speech(args: LocalSpeechArgs) = SharedRules.isRecordId(args.sessionId) && args.text.length in 1..CompanionLimits.MESSAGE
    val APPEND = BridgeMethod("voicePlayback.append", LocalSpeechArgs.serializer(), LocalSpeechStatus.serializer(), accepts = ::speech)
    val SPEAK = BridgeMethod("voicePlayback.speak", LocalSpeechArgs.serializer(), LocalSpeechStatus.serializer(), accepts = ::speech)
    val STOP = BridgeMethod("voicePlayback.stop", VoiceInputScope.serializer(), LocalSpeechStatus.serializer()) { SharedRules.isRecordId(it.sessionId) }
    val STATUS = BridgeMethod("voicePlayback.status", VoiceInputScope.serializer(), LocalSpeechStatus.serializer()) { SharedRules.isRecordId(it.sessionId) }
    fun pack(input: Capability<VoicePlaybackCapability>): CapabilityPack = binder.pack(input)
    internal val binder = CapabilityBinder("voicePlayback", listOf<CapabilityHandler<VoicePlaybackCapability>>(
        APPEND.handle { input, args -> input.append(args) }, SPEAK.handle { input, args -> input.speak(args) },
        STOP.handle { input, args -> input.stopSpeech(args) }, STATUS.handle { input, args -> input.speechStatus(args) },
    ))
}

/**
 * Who is speaking on a device whose engine reports each utterance's end, and how much is left to say. It holds
 * the rules of [VoicePlaybackCapability] and no audio; the caller serializes access and drives its engine from
 * the answers. A [ticket] names one queue: the caller tags each utterance with it, so an utterance that a flush
 * dropped cannot count against the queue that replaced it.
 */
class LocalSpeechQueue {
    /** What the engine does with one `speak` or `append`. */
    enum class Admission {
        /** The session was stopped. Say nothing. */
        CANCELLED,
        /** Another session is still speaking. Say nothing. */
        BUSY,
        /** Drop whatever the engine holds, then say the text as a new queue. */
        START,
        /** Say the text after what the engine already holds. */
        QUEUE,
    }

    private val stopped = linkedSetOf<String>()
    private var outstanding = 0
    private var failed = false

    /** The session that owns the queue, speaking or done. */
    var speaker: String? = null
        private set

    /** The live queue's name. It changes whenever the queue is replaced or dropped. */
    var ticket = 0
        private set

    val speaking: Boolean get() = outstanding > 0

    /** Counts the utterance in when the answer is [Admission.START] or [Admission.QUEUE]. */
    fun admit(sessionId: String, replace: Boolean): Admission = when {
        sessionId in stopped -> Admission.CANCELLED
        speaking && speaker != sessionId -> Admission.BUSY
        speaking && !replace -> Admission.QUEUE.also { outstanding++ }
        else -> {
            ticket++
            speaker = sessionId
            outstanding = 1
            failed = false
            Admission.START
        }
    }

    /** One utterance of the queue [ticket] was said or dropped by the engine. True when that emptied the live queue. */
    fun ended(ticket: Int): Boolean {
        if (ticket != this.ticket || outstanding == 0) return false
        outstanding--
        return outstanding == 0
    }

    /** The engine cannot say the queue [ticket]. True when that was the live queue, which the caller must now drop. */
    fun fail(ticket: Int): Boolean {
        if (ticket != this.ticket) return false
        val live = speaking
        this.ticket++
        outstanding = 0
        failed = true
        return live
    }

    /** Stops [sessionId] for good. True when it was speaking, so the caller must flush its engine. */
    fun stop(sessionId: String): Boolean {
        stopped.add(sessionId)
        while (stopped.size > REMEMBERED) stopped.remove(stopped.first())
        if (speaker != sessionId || !speaking) return false
        ticket++
        outstanding = 0
        return true
    }

    /** The app went away, audio was interrupted or the page ended: stops whoever is speaking. True when someone was. */
    fun interrupt(): Boolean = speaker?.takeIf { speaking }?.let(::stop) ?: false

    fun status(sessionId: String): String = when {
        sessionId in stopped -> "cancelled"
        speaker != sessionId || failed -> "error"
        speaking -> "speaking"
        else -> "done"
    }

    private companion object {
        /** Stopped sessions kept as tombstones, so a late `speak` for one of them stays cancelled. */
        const val REMEMBERED = 32
    }
}

/** One voice a device has installed. [quality] is 1 for a default voice, 2 for enhanced and 3 for premium. */
data class InstalledVoice(val id: String, val language: String, val quality: Int, val novelty: Boolean = false)

/**
 * The best downloaded voice for [language] (`en-US`): a voice of that exact locale before another region of the
 * language, premium before enhanced. Null when the device has only default voices for it; the caller then takes the
 * system's own default for the language, which is never a novelty voice. Chinese does not cross regions, because
 * its regions are different spoken languages.
 */
fun bestInstalledVoice(voices: List<InstalledVoice>, language: String): String? {
    val wanted = languageTag(language)
    val family = wanted.substringBefore('-')
    return voices
        .filter { !it.novelty && it.quality >= 2 }
        .mapNotNull { voice ->
            val tag = languageTag(voice.language)
            when {
                tag == wanted -> voice to 0
                tag.substringBefore('-') == family && family != "zh" -> voice to 1
                else -> null
            }
        }
        .sortedWith(compareBy({ it.second }, { -it.first.quality }, { it.first.id }))
        .firstOrNull()?.first?.id
}

/**
 * The recognizer locale for a page that asked for [requested], out of [supported]: that locale, else a locale of
 * the same language that the device uses ([device], most preferred first), else the language's home region. With
 * no request, or none of its language, the first device locale that has a match. Null when nothing matches; the
 * answer is spelled as the platform spelled it.
 */
fun bestSpeechLocale(requested: String?, device: List<String>, supported: Collection<String>): String? {
    val byTag = supported.sortedBy(::languageTag).associateBy(::languageTag)
    val devices = device.map(::languageTag)
    fun match(locale: String): String? {
        val tag = languageTag(locale)
        val family = tag.substringBefore('-')
        return byTag[tag]
            ?: devices.firstOrNull { it.substringBefore('-') == family && it in byTag }?.let(byTag::get)
            ?: byTag["$family-${HOME_REGIONS[family] ?: family}"]
            ?: byTag.entries.firstOrNull { it.key.substringBefore('-') == family }?.value
    }
    return requested?.let(::match) ?: device.firstNotNullOfOrNull(::match)
}

/** Languages whose home region is not spelled like the language, as `de-DE` and `fr-FR` are. */
private val HOME_REGIONS = mapOf("en" to "us", "zh" to "cn", "ja" to "jp", "ko" to "kr")

/** How long an utterance of [characters] may take before its engine is taken to have stalled. Generous: about four times a slow reading. */
fun speechBudgetMs(characters: Int): Long = SPEECH_START_BUDGET_MS + characters * 300L

/** How long an engine may take to start an utterance it was handed. */
const val SPEECH_START_BUDGET_MS = 10_000L

/** `en_US` and `EN-us` as `en-us`, so locales from different platform calls compare. */
private fun languageTag(locale: String): String = locale.replace('_', '-').lowercase()
