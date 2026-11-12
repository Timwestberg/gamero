package org.formulagen.wrapper.voice

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val BYTE_MASK = 0xffL
private const val LINE_FEED = '\n'.code.toByte()
private const val SPACE = ' '.code.toByte()
private const val UPPER_A = 'A'.code.toByte()
private const val UPPER_Z = 'Z'.code.toByte()
private const val TO_LOWER = 'a' - 'A'
private const val INITIAL_WORDS = 1 shl 16

/**
 * The words lexicon files can say, for [coverUnknownWords], where memory is short. The chat lexicons hold about
 * 660,000 words: as strings they would cost a phone tens of megabytes, so each is kept as a 64-bit hash of its
 * lower-case bytes, sorted. Only `in` can be asked of it.
 *
 * A word is the text before a line's first space, as [parseKokoroLexicon] reads it; a line with no pronunciation
 * adds nothing. Letters are lower-cased as ASCII, which is what the lexicons hold. Two words sharing a hash would
 * leave an unknown word unspelled; at this size that is about one in ten trillion.
 */
internal class LexiconWords(files: Iterable<ByteArray>) : AbstractSet<String>() {
    private val hashes: LongArray

    init {
        var found = LongArray(INITIAL_WORDS)
        var count = 0
        for (bytes in files) {
            var index = 0
            while (index < bytes.size) {
                var hash = FNV_OFFSET
                val start = index
                while (index < bytes.size && bytes[index] != SPACE && bytes[index] != LINE_FEED) {
                    hash = mix(hash, bytes[index])
                    index++
                }
                val word = index > start
                val pronounced = index < bytes.size && bytes[index] == SPACE
                while (index < bytes.size && bytes[index] != LINE_FEED) index++
                index++
                if (word && pronounced) {
                    if (count == found.size) found = found.copyOf(count * 2)
                    found[count++] = hash
                }
            }
        }
        hashes = found.copyOf(count).also { it.sort() }
    }

    /** How many entries were read. A word listed twice counts twice. */
    override val size: Int get() = hashes.size

    override fun contains(element: String): Boolean {
        var hash = FNV_OFFSET
        for (byte in element.encodeToByteArray()) hash = mix(hash, byte)
        var low = 0
        var high = hashes.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val found = hashes[middle]
            when {
                found < hash -> low = middle + 1
                found > hash -> high = middle - 1
                else -> return true
            }
        }
        return false
    }

    override fun iterator(): Iterator<String> =
        throw UnsupportedOperationException("The lexicon's words are kept as hashes: only `in` can be asked.")

    private fun mix(hash: Long, byte: Byte): Long {
        val lower = if (byte >= UPPER_A && byte <= UPPER_Z) byte + TO_LOWER else byte.toInt()
        return (hash xor (lower.toLong() and BYTE_MASK)) * FNV_PRIME
    }
}
