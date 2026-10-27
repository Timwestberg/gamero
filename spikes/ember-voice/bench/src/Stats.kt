package ember.voice.bench

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/** Nearest-rank percentile. p is 0..100. p90 of 10 values is the 9th. p99 of 10 is the max. */
fun percentile(values: List<Double>, p: Double): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    var rank = ceil(p / 100.0 * sorted.size).toInt()
    if (rank < 1) rank = 1
    if (rank > sorted.size) rank = sorted.size
    return sorted[rank - 1]
}

fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

data class SampleStats(
    val median: Double,
    val p90: Double,
    val p99: Double,
    val max: Double,
    val min: Double,
    val samples: List<Double>,
)

fun sampleStats(values: List<Double>): SampleStats {
    return SampleStats(
        median = median(values),
        p90 = percentile(values, 90.0),
        p99 = percentile(values, 99.0),
        max = values.max(),
        min = values.min(),
        samples = values,
    )
}

/**
 * One-sided Mann–Whitney p-value for "candidate values tend to be larger than baseline".
 * Exact when both sides have at most 10 values and there are no ties. Otherwise the
 * normal approximation with tie correction and a continuity correction.
 */
fun mannWhitneyGreaterP(baseline: List<Double>, candidate: List<Double>): Double {
    if (baseline.isEmpty() || candidate.isEmpty()) return 1.0
    val u = mannWhitneyU(baseline, candidate)
    val n1 = baseline.size
    val n2 = candidate.size
    val ties = hasTie(baseline, candidate)
    if (!ties && n1 <= 10 && n2 <= 10) {
        return exactGreaterP(n1, n2, u)
    }
    return normalGreaterP(n1, n2, u, tieCorrection(baseline + candidate))
}

fun mannWhitneyU(baseline: List<Double>, candidate: List<Double>): Double {
    var u = 0.0
    for (cand in candidate) {
        for (base in baseline) {
            if (cand > base) u += 1.0
            else if (cand == base) u += 0.5
        }
    }
    return u
}

private fun hasTie(baseline: List<Double>, candidate: List<Double>): Boolean {
    val all = (baseline + candidate).sorted()
    for (i in 1 until all.size) {
        if (all[i] == all[i - 1]) return true
    }
    return false
}

private fun tieCorrection(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val sorted = values.sorted()
    var sum = 0.0
    var i = 0
    while (i < sorted.size) {
        var j = i + 1
        while (j < sorted.size && sorted[j] == sorted[i]) j += 1
        val t = (j - i).toDouble()
        if (t > 1) sum += t * t * t - t
        i = j
    }
    return sum
}

private fun exactGreaterP(n1: Int, n2: Int, uObs: Double): Double {
    val total = binomial(n1 + n2, n2)
    var ge = 0L
    fun walk(start: Int, left: Int, rankSum: Int) {
        if (left == 0) {
            val u = rankSum - n2 * (n2 + 1) / 2.0
            if (u + 1e-9 >= uObs) ge += 1
            return
        }
        val last = n1 + n2 - left
        for (i in start..last) {
            walk(i + 1, left - 1, rankSum + (i + 1))
        }
    }
    walk(0, n2, 0)
    if (total == 0L) return 1.0
    return ge.toDouble() / total.toDouble()
}

private fun binomial(n: Int, k: Int): Long {
    var kk = k
    if (kk > n - kk) kk = n - kk
    var result = 1L
    for (i in 1..kk) {
        result = result * (n - kk + i) / i
    }
    return result
}

private fun normalGreaterP(n1: Int, n2: Int, u: Double, tieSum: Double): Double {
    val n = (n1 + n2).toDouble()
    val mean = n1 * n2 / 2.0
    var variance = n1.toDouble() * n2 * (n + 1.0) / 12.0
    if (tieSum > 0 && n > 1) {
        variance -= n1.toDouble() * n2 * tieSum / (12.0 * n * (n - 1.0))
    }
    if (variance <= 1e-12) return if (u > mean) 0.0 else 1.0
    val z = (u - mean - 0.5) / sqrt(variance)
    return 1.0 - normalCdf(z)
}

/** Abramowitz and Stegun 26.2.17. */
fun normalCdf(z: Double): Double {
    val t = 1.0 / (1.0 + 0.2316419 * kotlin.math.abs(z))
    val d = 0.3989422804014327 * exp(-z * z / 2.0)
    val poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
    val tail = d * poly
    return if (z >= 0) 1.0 - tail else tail
}

fun wordErrorRate(reference: String, hypothesis: String): WerScore {
    val ref = werTokens(reference)
    val hyp = werTokens(hypothesis)
    if (ref.isEmpty()) {
        return WerScore(if (hyp.isEmpty()) 0.0 else 1.0, 0, hyp.size, hypothesis)
    }
    val dist = editDistance(ref, hyp)
    return WerScore(dist.toDouble() / ref.size, ref.size, dist, hypothesis)
}

data class WerScore(val wer: Double, val referenceWords: Int, val errors: Int, val hypothesis: String)

/**
 * Median WER across draws. The hypothesis and error count come from the upper of the
 * two central draws, so the number is not the last sample and the text is a real draw.
 */
fun medianWer(draws: List<WerScore>): WerScore {
    require(draws.isNotEmpty())
    val med = median(draws.map { it.wer })
    val sorted = draws.sortedBy { it.wer }
    return sorted[sorted.size / 2].copy(wer = med)
}

/**
 * PocketTTS frame ceiling for one piece. 0 means leave sherpa-onnx's default alone.
 * A positive ceiling is ceil(max(floor, intercept + slope * chars)). 4 + 0.8 * 28 is
 * 26.4 and ceils to 27. A piece of at most [shortChars] characters is raised to
 * [shortMinFrames] when that floor is higher.
 */
fun frameBudget(
    chars: Int,
    intercept: Double,
    slope: Double,
    floor: Int,
    shortChars: Int,
    shortMinFrames: Int,
): Int {
    val curveOn = slope > 0.0 || intercept > 0.0 || floor > 0
    val shortOn = shortChars > 0 && chars <= shortChars && shortMinFrames > 0
    if (!curveOn && !shortOn) return 0
    var frames = 0
    if (curveOn) {
        val linear = intercept + slope * chars.toDouble()
        frames = ceil(maxOf(floor.toDouble(), linear)).toInt()
        if (frames < 4) frames = 4
    }
    if (shortOn && shortMinFrames > frames) frames = shortMinFrames
    return frames
}

fun werTokens(text: String): List<String> {
    val cleaned = StringBuilder()
    for (ch in text.lowercase()) {
        if (ch.isLetterOrDigit() || ch == '\'') cleaned.append(ch)
        else cleaned.append(' ')
    }
    return cleaned.toString().split(' ').filter { it.isNotEmpty() }
}

fun editDistance(ref: List<String>, hyp: List<String>): Int {
    val rows = ref.size + 1
    val cols = hyp.size + 1
    var prev = IntArray(cols) { it }
    val cur = IntArray(cols)
    for (r in 1 until rows) {
        cur[0] = r
        for (c in 1 until cols) {
            val cost = if (ref[r - 1] == hyp[c - 1]) 0 else 1
            cur[c] = minOf(cur[c - 1] + 1, prev[c] + 1, prev[c - 1] + cost)
        }
        val swap = prev
        prev = cur.copyOf()
        for (c in 0 until cols) swap[c] = 0
    }
    return prev[cols - 1]
}

fun expectedAudioSeconds(chars: Int, charsPerSecond: Double, minSeconds: Double): Double {
    val fromChars = chars / charsPerSecond
    return if (fromChars < minSeconds) minSeconds else fromChars
}

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

data class Regression(
    val lineId: String,
    val baseMedian: Double,
    val candMedian: Double,
    val ratio: Double,
    val pValue: Double,
    val regressed: Boolean,
)

/**
 * A line regresses when the candidate's warm TTFA median is more than 10% above
 * the baseline and the one-sided Mann–Whitney p-value is under 0.05.
 */
fun ttfaRegressions(baseline: Json, candidate: Json): List<Regression> {
    val baseLines = linesById(baseline)
    val candLines = linesById(candidate)
    val out = ArrayList<Regression>()
    for ((id, base) in baseLines) {
        val cand = candLines[id] ?: throw JsonException("candidate is missing line $id")
        val baseSamples = samples(base, "ttfa_ms")
        val candSamples = samples(cand, "ttfa_ms")
        val baseMed = median(baseSamples)
        val candMed = median(candSamples)
        val ratio = if (baseMed == 0.0) Double.POSITIVE_INFINITY else candMed / baseMed
        val p = mannWhitneyGreaterP(baseSamples, candSamples)
        out.add(
            Regression(
                lineId = id,
                baseMedian = baseMed,
                candMedian = candMed,
                ratio = ratio,
                pValue = p,
                regressed = ratio > 1.10 && p < 0.05,
            ),
        )
    }
    return out
}

private fun linesById(root: Json): Map<String, Json> {
    val lines = root.field("lines").arr()
    return lines.associate { line -> line.field("id").str() to line }
}

private fun samples(line: Json, name: String): List<Double> {
    val block = line.field(name)
    val raw = block.field("samples").arr()
    if (raw.isEmpty()) throw JsonException("${line.field("id").str()} $name has no samples")
    return raw.map { it.num() }
}
