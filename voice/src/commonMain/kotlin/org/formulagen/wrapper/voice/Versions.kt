package org.formulagen.wrapper.voice

/**
 * Release ordering. A pre-release is lower than the same core without one,
 * so 2.0.0-rc1 is lower than 2.0.0. Build metadata after '+' is ignored.
 */
fun compareVersions(left: String, right: String): Int {
    val a = parseReleaseVersion(left)
    val b = parseReleaseVersion(right)
    val n = maxOf(a.core.size, b.core.size)
    for (i in 0 until n) {
        val av = a.core.getOrElse(i) { 0 }
        val bv = b.core.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    if (a.pre == null && b.pre == null) return 0
    if (a.pre == null) return 1
    if (b.pre == null) return -1
    val limit = maxOf(a.pre.size, b.pre.size)
    for (i in 0 until limit) {
        if (i == a.pre.size) return -1
        if (i == b.pre.size) return 1
        val cmp = comparePreId(a.pre[i], b.pre[i])
        if (cmp != 0) return cmp
    }
    return 0
}

private data class ReleaseVersion(val core: List<Int>, val pre: List<String>?)

private fun parseReleaseVersion(raw: String): ReleaseVersion {
    var text = raw.trim()
    if (text.startsWith("v") || text.startsWith("V")) text = text.substring(1)
    val plus = text.indexOf('+')
    if (plus >= 0) text = text.substring(0, plus)
    val dash = text.indexOf('-')
    val coreText = if (dash < 0) text else text.substring(0, dash)
    val preText = if (dash < 0) null else text.substring(dash + 1)
    val core = coreText.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: 0 }
    val pre = preText?.split('.')?.filter { it.isNotEmpty() }
    return ReleaseVersion(if (core.isEmpty()) listOf(0) else core, if (pre.isNullOrEmpty()) null else pre)
}

private fun comparePreId(left: String, right: String): Int {
    val ln = left.toIntOrNull()
    val rn = right.toIntOrNull()
    if (ln != null && rn != null) return ln.compareTo(rn)
    if (ln != null) return -1
    if (rn != null) return 1
    return left.compareTo(right)
}
