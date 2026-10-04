package chimahon.local.ocr

import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin algorithms ported from meikiocr/ocr.py.
 *
 * Kept in a separate file with no Android or ONNX Runtime imports so the
 * unit tests in MeikiOcrEngineTest can run on plain JVM.
 */

internal data class MeikiCharCandidate(
    var char: Char,
    val bbox: IntArray,
    var conf: Float,
    val intervalStart: Int,
    val intervalEnd: Int,
)

private const val OVERLAP_THRESHOLD = 0.3f
private const val EPSILON = 1e-6f

// Copied verbatim from ocr.py SWAPPED_PAIRS. Some Japanese vertical-text
// fonts trip the recognizer into producing these two-glyph swaps; the
// pipeline fixes them by literal substitution.
private val SWAPPED_PAIRS: Map<String, String> = mapOf(
    "儡傀" to "傀儡",
    "談冗" to "冗談",
    "汰淘" to "淘汰",
    "沱滂" to "滂沱",
    "攣痙" to "痙攣",
    "酊酩" to "酩酊",
    "麭麺" to "麺麭",
    "哭慟" to "慟哭",
)

internal fun isPunctuationChar(ch: Char): Boolean = when (Character.getType(ch)) {
    Character.CONNECTOR_PUNCTUATION.toInt(),
    Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(),
    Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
    Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
    -> true
    else -> false
}

/**
 * Greedy interval NMS matching the Python implementation: sort by confidence
 * descending, accept a candidate if its interval does not overlap any accepted
 * interval by more than OVERLAP_THRESHOLD (relative to the shorter interval).
 * Returns accepted candidates sorted by interval start (reading order).
 */
internal fun nmsCandidates(
    candidatesIn: List<MeikiCharCandidate>,
    punctConfFactor: Float,
): List<MeikiCharCandidate> {
    if (candidatesIn.isEmpty()) return emptyList()

    // Work on copies so the caller's list is not mutated.
    val sorted = candidatesIn.map {
        MeikiCharCandidate(it.char, it.bbox.copyOf(), it.conf, it.intervalStart, it.intervalEnd)
    }.toMutableList()

    if (punctConfFactor != 1.0f) {
        for (c in sorted) {
            if (isPunctuationChar(c.char)) c.conf *= punctConfFactor
        }
    }

    sorted.sortByDescending { it.conf }
    val accepted = ArrayList<MeikiCharCandidate>(sorted.size)

    for (cand in sorted) {
        val i1c = cand.intervalStart
        val i2c = cand.intervalEnd
        val lenC = (i2c - i1c).toFloat() + EPSILON
        var overlaps = false
        for (a in accepted) {
            val i1a = a.intervalStart
            val i2a = a.intervalEnd
            if (i1c >= i2a || i1a >= i2c) continue
            val interStart = max(i1c, i1a)
            val interEnd = min(i2c, i2a)
            val interLen = max(0, interEnd - interStart).toFloat()
            val lenA = (i2a - i1a).toFloat() + EPSILON
            val minLen = min(lenC, lenA)
            if (interLen / minLen > OVERLAP_THRESHOLD) {
                overlaps = true
                break
            }
        }
        if (!overlaps) accepted += cand
    }

    accepted.sortBy { it.intervalStart }
    return accepted
}

/**
 * Fixes the eight known two-glyph swaps. Mirrors _fix_swapped_pairs: scans the
 * assembled text for each wrong pair, replaces it, and swaps the two
 * corresponding entries in the char list.
 */
internal fun fixSwappedPairs(
    text: String,
    chars: MutableList<MeikiCharCandidate>,
): String {
    var t = text
    for ((wrong, correct) in SWAPPED_PAIRS) {
        val idx = t.indexOf(wrong)
        if (idx != -1 && idx + 1 < chars.size) {
            t = t.substring(0, idx) + correct + t.substring(idx + 2)
            val tmp = chars[idx].char
            chars[idx].char = chars[idx + 1].char
            chars[idx + 1].char = tmp
        }
    }
    return t
}

/**
 * Builds a [3, canvasH, canvasW] float32 CHW tensor from an ARGB_8888 pixel
 * array. Content occupies [0, contentH) x [0, contentW); the rest is zero
 * padding. Channel order is B, G, R (OpenCV BGR convention, /255).
 *
 * Mirrors _preprocess_for_detection + _preprocess_for_recognition in ocr.py.
 */
internal fun buildChwTensor(
    pixels: IntArray,
    contentW: Int,
    contentH: Int,
    canvasW: Int,
    canvasH: Int,
): FloatArray {
    val total = canvasW * canvasH
    val arr = FloatArray(3 * total)
    val cw = contentW.coerceAtMost(canvasW)
    val ch = contentH.coerceAtMost(canvasH)
    for (y in 0 until ch) {
        val srcRow = y * contentW
        val dstRow = y * canvasW
        for (x in 0 until cw) {
            val c = pixels[srcRow + x]
            val r = ((c ushr 16) and 0xFF) / 255f
            val g = ((c ushr 8) and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val idx = dstRow + x
            arr[idx] = b
            arr[total + idx] = g
            arr[2 * total + idx] = r
        }
    }
    return arr
}
