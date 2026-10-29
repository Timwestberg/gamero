package org.formulagen.wrapper.chat

import org.formulagen.wrapper.voice.speechPieces

/** What [SentenceCursor.feed] wants spoken now. [replaced] means the provider rewrote the reply. */
internal data class CursorUpdate(val replaced: Boolean, val pieces: List<String>)

/**
 * Turns a growing reply into closed sentences.
 * Each [feed] gets the full text so far, which is what Codex and Claude already pass.
 * The unfinished tail stays unspoken until [finish] or an early comma on the first piece.
 */
internal class SentenceCursor {
    private var consumed = ""
    private var commaUsed = false

    fun feed(raw: String, finish: Boolean): CursorUpdate {
        val normalized = speechPieces(speechText(raw)).joinToString(" ").trim()
        var replaced = false
        if (consumed.isNotEmpty() && !normalized.startsWith(consumed)) {
            consumed = ""
            commaUsed = false
            replaced = true
        }
        val rest = if (consumed.isEmpty()) normalized else normalized.removePrefix(consumed).trimStart()
        if (rest.isEmpty()) return CursorUpdate(replaced, emptyList())
        val (closed, tail) = splitClosed(rest)
        val take = StringBuilder()
        if (closed.isNotEmpty()) take.append(closed.joinToString(" "))
        if (consumed.isEmpty() && !commaUsed && closed.isEmpty() && !finish) {
            val cut = commaCut(tail)
            if (cut != null) {
                commaUsed = true
                take.append(cut)
            }
        }
        if (finish && tail.isNotEmpty()) {
            val remain = tailAfterComma(tail)
            if (remain.isNotEmpty()) {
                if (take.isNotEmpty()) take.append(' ')
                take.append(remain)
            }
        }
        val spoken = take.toString().trim()
        if (spoken.isEmpty()) return CursorUpdate(replaced, emptyList())
        val candidate = if (consumed.isEmpty()) spoken else "$consumed $spoken"
        if (!normalized.startsWith(candidate)) return CursorUpdate(replaced, emptyList())
        consumed = candidate
        return CursorUpdate(replaced, speechPieces(spoken).ifEmpty { listOf(spoken) })
    }

    private fun tailAfterComma(tail: String): String {
        if (!commaUsed) return tail
        val cut = commaCut(tail) ?: return tail
        return if (tail.startsWith(cut)) tail.removePrefix(cut).trimStart() else tail
    }
}

/** First comma inside the first 40 characters, included in the cut. */
internal fun commaCut(tail: String): String? {
    val at = tail.indexOf(',')
    if (at !in 0..39) return null
    val cut = tail.substring(0, at + 1).trim()
    return cut.ifEmpty { null }
}

private fun splitClosed(text: String): Pair<List<String>, String> {
    val closed = ArrayList<String>()
    val current = StringBuilder()
    for (ch in text) {
        current.append(ch)
        if (ch == '.' || ch == '!' || ch == '?') {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) closed.add(piece)
            current.clear()
        }
    }
    return closed to current.toString().trim()
}
