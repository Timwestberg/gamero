package org.formulagen.wrapper.bridge

/**
 * The words of one utterance, kept across what a recognizer reports of it. A recognizer may close a stretch of speech
 * after a pause and start its next report from nothing; asked to finish, it may then answer with only the last
 * stretch, with nothing at all, or with an error. What the person said is every stretch, so each closed one is kept
 * and the rest is added to it.
 */
class HeardWords {
    private var closed = ""
    private var open = ""

    /** Everything heard so far, in order. */
    val all: String
        get() = when {
            open.isBlank() -> closed
            closed.isBlank() -> open
            // A recognizer that goes on from its closed stretch, or repeats it as its last word, says nothing new.
            open.startsWith(closed) -> open
            closed.endsWith(open) -> closed
            else -> "$closed $open"
        }

    /** Takes a report: the recognizer's [text] for its current stretch, and whether it has now [closedStretch]. Returns [all]. */
    fun hear(text: String, closedStretch: Boolean): String {
        open = text.trim()
        val heard = all
        if (closedStretch) {
            closed = heard
            open = ""
        }
        return heard
    }
}

/** What a recognizer's ending means for the utterance it was hearing. */
enum class RecognitionEnd { HEARD, SILENCE, FAILED }

/**
 * How an utterance ends when the recognizer stops with an error. With words already [heard], the words stand: the
 * error only says the recognizer had nothing to add. With none, a "no speech" error ([noSpeech]) is silence, which
 * the person may simply try again, and anything else is a failure.
 */
fun recognitionEnd(heard: String, noSpeech: Boolean): RecognitionEnd = when {
    heard.isNotBlank() -> RecognitionEnd.HEARD
    noSpeech -> RecognitionEnd.SILENCE
    else -> RecognitionEnd.FAILED
}
