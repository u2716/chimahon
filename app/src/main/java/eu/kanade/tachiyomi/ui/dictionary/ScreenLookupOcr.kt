package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import chimahon.ocr.OcrResult
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.fullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedLineIndices
import eu.kanade.tachiyomi.ui.reader.viewer.uniformCharOffset
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

private val measurementPaint = Paint().apply {
    typeface = Typeface.DEFAULT
    textSize = 100f
}

internal fun getLineOffsets(text: String, vertical: Boolean): FloatArray {
    val lineLen = text.length
    if (lineLen == 0) return floatArrayOf(0f)

    val offsets = FloatArray(lineLen + 1)
    offsets[0] = 0f

    val sizes = FloatArray(lineLen)
    if (vertical) {
        // For vertical text, approximate character height using measureText.
        // CJK square characters: width ≈ height.
        for (i in 0 until lineLen) {
            sizes[i] = measurementPaint.measureText(text, i, i + 1).coerceAtLeast(1f)
        }
    } else {
        measurementPaint.getTextWidths(text, sizes)
    }

    // Shrink weights of edge punctuation characters.
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
 * Returns the tap position as an offset into [orderedFullText] (reading order).
 * Computes ordered offset directly from (lineIndex, charInLine) to avoid raw->ordered ambiguity for charInLine == 0.
 */
internal fun OcrTextBlock.screenLookupCharOffset(tapX: Float, tapY: Float, lineIndex: Int? = null): Int {
    val geometries = lineGeometries
    if (geometries != null && geometries.size == lines.size) {
        if (lineIndex != null && lineIndex in geometries.indices) {
            val geo = geometries[lineIndex]
            val line = lines[lineIndex]
            val lineLen = line.length.coerceAtLeast(1)
            val geoWidth = (geo.xmax - geo.xmin).coerceAtLeast(0.001f)
            val geoHeight = (geo.ymax - geo.ymin).coerceAtLeast(0.001f)

            val offsets = getLineOffsets(line, vertical)
            val frac = if (vertical) {
                (tapY - geo.ymin) / geoHeight
            } else {
                (tapX - geo.xmin) / geoWidth
            }.coerceIn(0f, 1f)

            val charInLine = offsets.binarySearch(frac).let {
                if (it < 0) (-it - 2).coerceIn(0, lineLen - 1) else it.coerceIn(0, lineLen - 1)
            }

            val orderedIndices = orderedLineIndices()
            val orderedLineStart = orderedIndices
                .takeWhile { it != lineIndex }
                .sumOf { lines[it].length }
            return (orderedLineStart + charInLine).coerceIn(0, orderedFullText.length - 1)
        }

        for (i in geometries.indices) {
            val geo = geometries[i]
            val line = lines[i]
            val inLine = if (vertical) {
                tapX >= geo.xmin && tapX <= geo.xmax
            } else {
                tapY >= geo.ymin && tapY <= geo.ymax
            }
            if (inLine) {
                val lineLen = line.length.coerceAtLeast(1)
                val geoWidth = (geo.xmax - geo.xmin).coerceAtLeast(0.001f)
                val geoHeight = (geo.ymax - geo.ymin).coerceAtLeast(0.001f)

                val offsets = getLineOffsets(line, vertical)
                val frac = if (vertical) {
                    (tapY - geo.ymin) / geoHeight
                } else {
                    (tapX - geo.xmin) / geoWidth
                }.coerceIn(0f, 1f)

                val charInLine = offsets.binarySearch(frac).let {
                    if (it < 0) (-it - 2).coerceIn(0, lineLen - 1) else it.coerceIn(0, lineLen - 1)
                }

                val orderedIndices = orderedLineIndices()
                val orderedLineStart = orderedIndices
                    .takeWhile { it != i }
                    .sumOf { lines[it].length }
                return (orderedLineStart + charInLine).coerceIn(0, orderedFullText.length - 1)
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
