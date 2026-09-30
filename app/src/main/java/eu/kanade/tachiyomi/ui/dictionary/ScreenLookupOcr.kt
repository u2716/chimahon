package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import chimahon.ocr.OcrHitTester
import chimahon.ocr.OcrResult
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedLineStarts
import eu.kanade.tachiyomi.ui.reader.viewer.uniformCharOffset
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

// Global mutable Paint is fine: getLineOffsets only runs on the Compose draw/UI thread.
private val measurementPaint = Paint().apply {
    typeface = Typeface.DEFAULT
    textSize = 100f
}

/**
 * Small kana occupy roughly 70–80% of an em vertically in vertical Japanese. When we
 * approximate per-character height by measuring width (square glyph assumption), these
 * would otherwise be treated as full-size and shift tap offsets toward the next char.
 */
private val SMALL_KANA: Set<Char> = setOf(
    'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'っ', 'ゃ', 'ゅ', 'ょ', 'ゎ', 'ゕ', 'ゖ',
    'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ッ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ',
)
private const val SMALL_KANA_SCALE = 0.75f

internal fun getLineOffsets(text: String, vertical: Boolean): FloatArray {
    val lineLen = text.length
    if (lineLen == 0) return floatArrayOf(0f)

    val offsets = FloatArray(lineLen + 1)
    offsets[0] = 0f

    val sizes = FloatArray(lineLen)
    if (vertical) {
        // Approximate per-character height using the glyph's advance width. This is
        // exact for full-size CJK (square glyphs) and corrected for small kana below.
        for (i in 0 until lineLen) {
            var size = measurementPaint.measureText(text, i, i + 1).coerceAtLeast(1f)
            if (text[i] in SMALL_KANA) size *= SMALL_KANA_SCALE
            sizes[i] = size
        }
    } else {
        measurementPaint.getTextWidths(text, sizes)
    }

    // Edge punctuation: a tight OCR line box bounds the ink, not the em slot. The first
    // and last characters' effective extent inside that box is therefore smaller when
    // they're brackets or trailing punctuation. Mid-line punctuation sits inside the
    // box and needs no shrink.
    if (lineLen > 0) {
        val firstChar = text[0]
        if (firstChar in "「『（〈《【") {
            sizes[0] *= 0.3f
        }
        val lastChar = text[lineLen - 1]
        if (lastChar in "。、！・゛゜」』）〉》】") {
            sizes[lineLen - 1] *= 0.3f
        }
    }

    val total = sizes.sum().coerceAtLeast(0.001f)
    var current = 0f
    for (i in 0 until lineLen) {
        current += sizes[i]
        offsets[i + 1] = current / total
    }
    return offsets
}

/**
 * Index of the character covering [frac] in a monotonic offsets array. Linear because
 * the array is tiny and may contain duplicates (zero-width chars), which makes
 * binarySearch non-deterministic.
 */
private fun charIndexForFraction(offsets: FloatArray, frac: Float, lineLen: Int): Int {
    if (lineLen <= 0) return 0
    for (i in 0 until lineLen) {
        if (frac < offsets[i + 1]) return i
    }
    return lineLen - 1
}

internal fun Bitmap.toScreenLookupOcrPngBytes(maxPixels: Int = 3_000_000): ByteArray {
    val sourcePixels = width.toLong() * height.toLong()
    val bitmapForOcr = if (sourcePixels > maxPixels) {
        val scale = sqrt(maxPixels.toDouble() / sourcePixels.toDouble())
        Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        this
    }

    return ByteArrayOutputStream().use { output ->
        bitmapForOcr.compress(Bitmap.CompressFormat.PNG, 100, output)
        if (bitmapForOcr !== this) bitmapForOcr.recycle()
        output.toByteArray()
    }
}

internal fun List<OcrResult>.toScreenLookupBlocks(language: String): List<OcrTextBlock> {
    return mapNotNull { result ->
        val bbox = result.tightBoundingBox
        val xmin = bbox.x.toFloat().coerceIn(0f, 1f)
        val ymin = bbox.y.toFloat().coerceIn(0f, 1f)
        val xmax = (bbox.x + bbox.width).toFloat().coerceIn(0f, 1f)
        val ymax = (bbox.y + bbox.height).toFloat().coerceIn(0f, 1f)
        val lines = result.text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        if (xmax <= xmin || ymax <= ymin || lines.isEmpty()) {
            null
        } else {
            OcrTextBlock(
                xmin = xmin,
                ymin = ymin,
                xmax = xmax,
                ymax = ymax,
                lines = lines,
                vertical = result.forcedOrientation == "vertical",
                lineGeometries = result.constituentBoxes?.map { lineBox ->
                    OcrLineGeometry(
                        xmin = lineBox.x.toFloat().coerceIn(0f, 1f),
                        ymin = lineBox.y.toFloat().coerceIn(0f, 1f),
                        xmax = (lineBox.x + lineBox.width).toFloat().coerceIn(0f, 1f),
                        ymax = (lineBox.y + lineBox.height).toFloat().coerceIn(0f, 1f),
                        rotation = (lineBox.rotation ?: 0.0).toFloat(),
                    )
                },
                language = language,
            )
        }
    }
}

/**
 * Per-line orientation. The block flag is the default; a line whose box is clearly taller
 * than it is wide is treated as vertical regardless. Matches the reader's overlay logic
 * so mixed pages (vertical prose + a horizontal sfx line) resolve taps consistently.
 */
private fun OcrTextBlock.lineIsVertical(geo: OcrLineGeometry): Boolean =
    OcrHitTester.isLineVertical(vertical, geo.xmin, geo.ymin, geo.xmax, geo.ymax)

private fun OcrTextBlock.charOffsetInLine(
    lineIndex: Int,
    tapX: Float,
    tapY: Float,
    orderedStarts: IntArray,
): Int {
    val geo = lineGeometries?.getOrNull(lineIndex) ?: return 0
    val line = lines[lineIndex]
    val lineLen = line.length.coerceAtLeast(1)
    val geoWidth = (geo.xmax - geo.xmin).coerceAtLeast(0.001f)
    val geoHeight = (geo.ymax - geo.ymin).coerceAtLeast(0.001f)
    val lineVertical = lineIsVertical(geo)
    val offsets = getLineOffsets(line, lineVertical)
    val frac = if (lineVertical) {
        (tapY - geo.ymin) / geoHeight
    } else {
        (tapX - geo.xmin) / geoWidth
    }.coerceIn(0f, 1f)
    return orderedStarts[lineIndex] + charIndexForFraction(offsets, frac, lineLen)
}

/** True when ([tapX], [tapY]) falls inside the reading-axis band of line [i]. */
private fun OcrTextBlock.tapInLineBand(i: Int, tapX: Float, tapY: Float): Boolean {
    val geo = lineGeometries?.getOrNull(i) ?: return false
    return if (lineIsVertical(geo)) {
        tapX >= geo.xmin && tapX <= geo.xmax
    } else {
        tapY >= geo.ymin && tapY <= geo.ymax
    }
}

/**
 * Returns the tap position as an offset into [orderedFullText] (reading order).
 * Computes the ordered offset directly from (lineIndex, charInLine) to avoid raw→ordered
 * ambiguity when charInLine == 0.
 */
internal fun OcrTextBlock.screenLookupCharOffset(tapX: Float, tapY: Float, lineIndex: Int? = null): Int {
    val geometries = lineGeometries
    if (geometries != null && geometries.size == lines.size) {
        val orderedStarts = orderedLineStarts()
        if (orderedStarts != null) {
            val targetLine = when {
                lineIndex != null && lineIndex in geometries.indices -> lineIndex
                else -> geometries.indices.firstOrNull { tapInLineBand(it, tapX, tapY) }
            }
            if (targetLine != null) {
                return charOffsetInLine(targetLine, tapX, tapY, orderedStarts)
                    .coerceIn(0, orderedFullText.length - 1)
            }
        }
    }

    val blockWidth = (xmax - xmin).coerceAtLeast(0.001f)
    val blockHeight = (ymax - ymin).coerceAtLeast(0.001f)
    val localX = (tapX - xmin).coerceIn(0f, blockWidth)
    val localY = (tapY - ymin).coerceIn(0f, blockHeight)
    return uniformCharOffset(this, localX, localY, blockWidth, blockHeight)
        .coerceIn(0, fullText.lastIndex.coerceAtLeast(0))
}
