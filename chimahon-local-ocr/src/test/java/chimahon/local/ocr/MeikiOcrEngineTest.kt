package chimahon.local.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure-Kotlin algorithms ported from meikiocr/ocr.py.
 * These do not exercise ONNX Runtime or the Android framework.
 */
class MeikiOcrEngineTest {

    private fun candidate(
        char: Char,
        intervalStart: Int,
        intervalEnd: Int,
        conf: Float,
    ) = MeikiCharCandidate(
        char = char,
        bbox = intArrayOf(intervalStart, 0, intervalEnd, 10),
        conf = conf,
        intervalStart = intervalStart,
        intervalEnd = intervalEnd,
    )

    // ── NMS ──────────────────────────────────────────────────────

    @Test
    fun `empty input yields empty output`() {
        assertTrue(nmsCandidates(emptyList(), 1.0f).isEmpty())
    }

    @Test
    fun `non overlapping candidates all survive`() {
        val input = listOf(
            candidate('あ', 0, 10, 0.9f),
            candidate('い', 20, 30, 0.8f),
            candidate('う', 40, 50, 0.7f),
        )
        val out = nmsCandidates(input, 1.0f)
        assertEquals(listOf('あ', 'い', 'う'), out.map { it.char })
    }

    @Test
    fun `overlapping candidates keep the higher confidence one`() {
        // Two candidates covering the same interval; the higher-conf one wins.
        val input = listOf(
            candidate('X', 0, 100, 0.5f),
            candidate('Y', 10, 90, 0.9f),
        )
        val out = nmsCandidates(input, 1.0f)
        assertEquals(1, out.size)
        assertEquals('Y', out.single().char)
    }

    @Test
    fun `nms returns accepted candidates sorted by interval start`() {
        val input = listOf(
            candidate('c', 200, 300, 0.9f),
            candidate('a', 0, 50, 0.9f),
            candidate('b', 100, 150, 0.9f),
        )
        val out = nmsCandidates(input, 1.0f)
        assertEquals(listOf('a', 'b', 'c'), out.map { it.char })
    }

    @Test
    fun `punct_conf_factor downweights punctuation`() {
        // Two overlapping candidates: a punctuation with high conf and a normal
        // char with lower conf. With punctConfFactor < 1 the punctuation loses.
        val input = listOf(
            candidate('、', 0, 100, 0.9f),
            candidate('あ', 0, 100, 0.5f),
        )
        val out = nmsCandidates(input, 0.2f)
        assertEquals(1, out.size)
        assertEquals('あ', out.single().char)
    }

    // ── swapped pairs ────────────────────────────────────────────

    @Test
    fun `fixSwappedPairs corrects a known wrong pair`() {
        val chars = mutableListOf(
            candidate('儡', 0, 10, 1f),
            candidate('傀', 10, 20, 1f),
            candidate('の', 20, 30, 1f),
        )
        val corrected = fixSwappedPairs("儡傀の", chars)
        assertEquals("傀儡の", corrected)
        assertEquals('傀', chars[0].char)
        assertEquals('儡', chars[1].char)
    }

    @Test
    fun `fixSwappedPairs leaves unknown text alone`() {
        val chars = mutableListOf(
            candidate('あ', 0, 10, 1f),
            candidate('い', 10, 20, 1f),
        )
        val corrected = fixSwappedPairs("あい", chars)
        assertEquals("あい", corrected)
    }

    // ── CHW tensor builder ───────────────────────────────────────

    @Test
    fun `buildChwTensor lays out BGR planes with zero padding`() {
        // 2x1 content in a 2x2 canvas. Second row of canvas is zero padding.
        // Pixels are ARGB ints: 0xFFRRGGBB.
        val pixels = intArrayOf(
            0xFF112233.toInt(), // R=0x11 G=0x22 B=0x33
            0xFF445566.toInt(), // R=0x44 G=0x55 B=0x66
        )
        val arr = buildChwTensor(pixels, contentW = 2, contentH = 1, canvasW = 2, canvasH = 2)
        // total = 4, so array size 12. Channel 0 = B, 1 = G, 2 = R.
        assertEquals(12, arr.size)
        // Row 0, col 0
        assertTrue(kotlin.math.abs(arr[0] - 0x33 / 255f) < 1e-6f)
        assertTrue(kotlin.math.abs(arr[1] - 0x66 / 255f) < 1e-6f)
        // Row 0, col 1
        assertTrue(kotlin.math.abs(arr[4] - 0x22 / 255f) < 1e-6f)
        assertTrue(kotlin.math.abs(arr[5] - 0x55 / 255f) < 1e-6f)
        // Channel 2 (R)
        assertTrue(kotlin.math.abs(arr[8] - 0x11 / 255f) < 1e-6f)
        assertTrue(kotlin.math.abs(arr[9] - 0x44 / 255f) < 1e-6f)
        // Padding row 1 is all zero
        assertEquals(0f, arr[2])
        assertEquals(0f, arr[3])
        assertEquals(0f, arr[6])
        assertEquals(0f, arr[7])
        assertEquals(0f, arr[10])
        assertEquals(0f, arr[11])
    }

    // ── punctuation classifier ──────────────────────────────────

    @Test
    fun `isPunctuationChar matches CJK punctuation`() {
        assertTrue(isPunctuationChar('。'))
        assertTrue(isPunctuationChar('、'))
        assertTrue(isPunctuationChar('「'))
        assertTrue(isPunctuationChar('」'))
    }

    @Test
    fun `isPunctuationChar rejects kana and kanji`() {
        assertTrue(!isPunctuationChar('あ'))
        assertTrue(!isPunctuationChar('漢'))
        assertTrue(!isPunctuationChar('A'))
    }
}
