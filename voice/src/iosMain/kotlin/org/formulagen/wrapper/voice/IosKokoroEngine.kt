package org.formulagen.wrapper.voice

import ember.voice.bridge.ember_voice_kokoro_create
import ember.voice.bridge.ember_voice_kokoro_destroy
import ember.voice.bridge.ember_voice_kokoro_sample_rate
import ember.voice.bridge.ember_voice_kokoro_speak
import ember.voice.bridge.ember_voice_sherpa_linked
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The files a Kokoro model directory must hold, as on the Mac. */
private val KOKORO_FILES = listOf("model.onnx", "voices-blends.bin", "tokens.txt", "lexicon-us-en.txt")

/** The regular inflections the Mac writes beside the upstream lexicon. The app bundles the Mac's copy. */
private const val CHAT_LEXICON = "lexicon-chat.txt"

/** True when this build links sherpa-onnx, so [VoiceConfig.KOKORO] can open at all. */
@OptIn(ExperimentalForeignApi::class)
fun kokoroLinked(): Boolean = ember_voice_sherpa_linked() != 0

/** The Kokoro files missing from [dir]; empty when the directory can speak. */
fun kokoroFilesMissing(dir: String): List<String> =
    KOKORO_FILES.filterNot { NSFileManager.defaultManager.fileExistsAtPath("${dir.trimEnd('/')}/$it") }

/**
 * Kokoro 82M v1.0 through the sherpa-onnx 2.0.0 xcframework: the Mac's engine
 * ([SherpaKokoroEngine]) on the phone, with the same voices, lexicons and one
 * sentence per [synthesize]. The model directory is read-only in the app
 * bundle, so the inflection lexicon is the bundled copy, not one written here.
 * Without it the upstream lexicon speaks alone and more words are spelled.
 * Hovel's own words are written to the app's caches and loaded last.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class KokoroIosPieceEngine(private val config: VoiceConfig) : PieceEngine {
    private var handle: COpaquePointer?
    private val knownWords: Set<String>
    private val cancel = AtomicInt(0)
    private var voiceOverride: String? = null
    /** The voice a person picked after the engine opened. The model holds every voice, so a change loads nothing. */
    private var chosenVoice: String? = null
    override val sampleRate: Int

    init {
        if (!kokoroLinked()) throw IllegalStateException("This build does not link sherpa-onnx, so Kokoro cannot speak.")
        val dir = config.kokoroModelDir.trimEnd('/')
        refuseNonCommercialVoice(dir)
        val missing = kokoroFilesMissing(dir)
        if (missing.isNotEmpty()) {
            throw IllegalStateException("Kokoro files missing in $dir: ${missing.joinToString(", ")}")
        }
        val chosen = voice()
        if (chosen.sid !in 0 until kokoroCatalog().speakerCount) {
            throw IllegalStateException("Kokoro voice ${chosen.id} sid ${chosen.sid} is outside the voice table")
        }
        // The upstream file first: sherpa ignores a later duplicate, so its pronunciation wins.
        val lexicons = (listOf("$dir/lexicon-us-en.txt", "$dir/$CHAT_LEXICON") + listOfNotNull(productLexicon()))
            .filter { NSFileManager.defaultManager.fileExistsAtPath(it) }
        // One file at a time: the larger is 19 MB, and only its words' hashes are kept.
        knownWords = LexiconWords(lexicons.asSequence().mapNotNull(::fileBytes).asIterable())
        val created = ember_voice_kokoro_create(
            "$dir/model.onnx",
            "$dir/voices-blends.bin",
            "$dir/tokens.txt",
            lexicons.joinToString(","),
            config.numThreads,
        ) ?: throw IllegalStateException("Kokoro did not load from $dir")
        val rate = ember_voice_kokoro_sample_rate(created)
        if (rate <= 0) {
            ember_voice_kokoro_destroy(created)
            throw IllegalStateException("Kokoro reported no sample rate")
        }
        handle = created
        sampleRate = rate
    }

    override fun usesFrameCeiling(): Boolean = false

    override fun synthesizeLine(text: String, voiceId: String): PieceAudio {
        voiceOverride = voiceId
        try {
            return synthesize(text, 0, config.seed)
        } finally {
            voiceOverride = null
        }
    }

    override fun chooseVoice(voiceId: String) {
        if (kokoroVoice(voiceId) != null) chosenVoice = voiceId
    }

    override fun requestCancel() = cancel.store(1)

    override fun clearCancel() = cancel.store(0)

    override fun isCancelled(): Boolean = cancel.load() != 0

    override fun synthesize(text: String, maxFrames: Int, seed: Int): PieceAudio {
        val current = handle ?: throw IllegalStateException("voice engine was released")
        if (isCancelled()) throw SpeechCancelled()
        val chosen = voice()
        val spoken = coverUnknownWords(text, knownWords)
        return collect(text, sampleRate, 0, stop = ::isCancelled) { callback, arg ->
            ember_voice_kokoro_speak(current, spoken, chosen.sid, chosen.speed, callback, arg)
        }
    }

    override fun release() {
        val current = handle ?: return
        handle = null
        ember_voice_kokoro_destroy(current)
    }

    private fun voice(): KokoroVoice {
        val id = voiceOverride ?: chosenVoice ?: config.kokoroVoiceId.ifEmpty { kokoroCatalog().defaultId }
        return kokoroVoice(id) ?: throw IllegalStateException("Unknown Kokoro voice '$id'.")
    }
}

/**
 * Hovel's own words ([kokoroProductWords]) as a lexicon file in the app's caches, since the bundle cannot be
 * written. Null when the file cannot be written; those words are then spelled, as every unknown word is.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun productLexicon(): String? {
    val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: return null
    val path = "$caches/$KOKORO_PRODUCT_LEXICON"
    val text = lexiconText(kokoroProductWords()) as NSString
    return path.takeIf { text.writeToFile(it, atomically = true, encoding = NSUTF8StringEncoding, error = null) }
}

/** A file's bytes, or null when it cannot be read. */
@OptIn(ExperimentalForeignApi::class)
private fun fileBytes(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path, NSDataReadingMappedIfSafe, null) ?: return null
    val source = data.bytes ?: return ByteArray(0)
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), source, data.length) }
    return bytes
}
