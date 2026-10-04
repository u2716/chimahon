package eu.kanade.tachiyomi.ui.reader.viewer

import androidx.compose.ui.geometry.Rect
import chimahon.ocr.extractOcrLookupText
import chimahon.ocr.extractWholeWord
import chimahon.ocr.isOcrLookupStartChar

data class OcrTextBlock(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val lines: List<String>,
    val vertical: Boolean = false,
    val lineGeometries: List<OcrLineGeometry>? = null,
    val language: String = "",
)

data class OcrLineGeometry(
    val xmin: Float,
    val ymin: Float,
    val xmax: Float,
    val ymax: Float,
    val rotation: Float = 0f,
)

// ── normalized → canvas-pixel helpers ────────────────────────────────────────

/**
 * Canvas-pixel rect of the line box, expanded around its center by [boxScaleX]/[boxScaleY].
 * Shared by drawing, hit-testing, and match-highlight positioning so they never disagree.
 */
fun OcrLineGeometry.toPxRect(
    boxScaleX: Float,
    boxScaleY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Rect {
    val cx = (xmin + xmax) / 2f
    val cy = (ymin + ymax) / 2f
    val w = (xmax - xmin).coerceAtLeast(0.001f) * boxScaleX
    val h = (ymax - ymin).coerceAtLeast(0.001f) * boxScaleY
    val left = (cx - w / 2f) * canvasWidth
    val top = (cy - h / 2f) * canvasHeight
    return Rect(left, top, left + w * canvasWidth, top + h * canvasHeight)
}

/** Canvas-pixel rect of the whole block. Used only when a block has no line geometries. */
fun OcrTextBlock.toPxRect(canvasWidth: Float, canvasHeight: Float): Rect =
    Rect(
        left = xmin * canvasWidth,
        top = ymin * canvasHeight,
        right = xmax * canvasWidth,
        bottom = ymax * canvasHeight,
    )

/** True when ([tapX], [tapY]) falls inside the scaled line box. */
fun OcrLineGeometry.hitTest(
    tapX: Float,
    tapY: Float,
    boxScaleX: Float,
    boxScaleY: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Boolean {
    val r = toPxRect(boxScaleX, boxScaleY, canvasWidth, canvasHeight)
    return tapX >= r.left && tapX <= r.right && tapY >= r.top && tapY <= r.bottom
}

/**
 * Ordered (reading-order) start offset in characters for each raw line index, or null when
 * [orderedLineIndices] can't produce a consistent permutation. Precomputed once per block
 * so the match-highlight loop doesn't recompute it per line.
 */
internal fun OcrTextBlock.orderedLineStarts(): IntArray? {
    if (lines.isEmpty()) return IntArray(0)
    val ordered = orderedLineIndices()
    if (ordered.size != lines.size) return null
    val starts = IntArray(lines.size)
    var acc = 0
    for (lineIdx in ordered) {
        if (lineIdx !in lines.indices) return null
        starts[lineIdx] = acc
        acc += lines[lineIdx].length
    }
    return starts
}

// ── text helpers (unchanged) ─────────────────────────────────────────────────

val OcrTextBlock.fullText: String
    get() = lines.joinToString("")

val OcrTextBlock.orderedFullText: String
    get() = orderedLineIndices().joinToString("") { index -> lines[index] }

val OcrTextBlock.displayText: String
    get() {
        if (vertical) return lines.joinToString("")
        val out = mutableListOf(lines.firstOrNull() ?: return "")
        for (i in 1 until lines.size) {
            out.add(trimLineOverlap(out.last(), lines[i]))
        }
        return out.joinToString(" ")
    }

val OcrTextBlock.orderedDisplayText: String
    get() {
        val indices = orderedLineIndices()
        if (vertical) return indices.joinToString("") { lines[it] }
        val ordered = indices.map { lines[it] }
        val out = mutableListOf(ordered.firstOrNull() ?: return "")
        for (i in 1 until ordered.size) {
            out.add(trimLineOverlap(out.last(), ordered[i]))
        }
        return out.joinToString(" ")
    }

fun OcrTextBlock.toOrderedOffset(rawOffset: Int): Int {
    if (lines.isEmpty()) return 0

    val safeRawOffset = rawOffset.coerceIn(0, fullText.length)
    var rawLineStart = 0
    var rawLineIndex = lines.lastIndex
    var offsetInLine = 0

    for (i in lines.indices) {
        val lineLength = lines[i].length
        val rawLineEnd = rawLineStart + lineLength
        if (safeRawOffset <= rawLineEnd) {
            rawLineIndex = i
            offsetInLine = (safeRawOffset - rawLineStart).coerceIn(0, lineLength)
            break
        }
        rawLineStart = rawLineEnd
    }

    val orderedIndices = orderedLineIndices()
    val orderedLineStart = orderedIndices
        .takeWhile { it != rawLineIndex }
        .sumOf { lines[it].length }

    return (orderedLineStart + offsetInLine).coerceIn(0, orderedFullText.length)
}

internal fun OcrTextBlock.orderedLineIndices(): List<Int> {
    val geometries = lineGeometries
    if (lines.size <= 1 || geometries == null || geometries.size != lines.size) {
        return lines.indices.toList()
    }

    return if (vertical) {
        lines.indices.sortedWith(
            compareByDescending<Int> { geometries[it].centerX }
                .thenBy { geometries[it].ymin },
        )
    } else {
        orderedLineIndicesHorizontal(geometries)
    }
}

/**
 * Horizontal reading order: cluster lines into rows by y-tolerance, then sort
 * within each row by xmin. A naive compareBy(centerY).thenBy(xmin) fails when
 * two lines on the same visual row have slightly different y-centers — the
 * nonzero y-difference locks the sort and the xmin tiebreaker is never used,
 * producing arbitrary order for side-by-side fragments of a wrapped line.
 */
private fun orderedLineIndicesHorizontal(
    geometries: List<OcrLineGeometry>,
): List<Int> {
    if (geometries.isEmpty()) return emptyList()

    val indicesByY = geometries.indices.sortedBy { geometries[it].centerY }
    val rows = ArrayList<List<Int>>()
    var currentRow = ArrayList<Int>()
    var rowCenterY = 0f
    var rowHeight = 0f

    for (i in indicesByY) {
        val geo = geometries[i]
        val cy = geo.centerY
        val h = geo.ymax - geo.ymin
        if (currentRow.isEmpty()) {
            currentRow.add(i)
            rowCenterY = cy
            rowHeight = h
        } else {
            val tolerance = 0.5f * maxOf(rowHeight, h)
            if (kotlin.math.abs(cy - rowCenterY) < tolerance) {
                currentRow.add(i)
                rowHeight = maxOf(rowHeight, h)
                rowCenterY = (rowCenterY + cy) / 2f
            } else {
                rows.add(currentRow)
                currentRow = ArrayList<Int>()
                currentRow.add(i)
                rowCenterY = cy
                rowHeight = h
            }
        }
    }
    if (currentRow.isNotEmpty()) rows.add(currentRow)

    return rows.flatMap { row -> row.sortedBy { geometries[it].xmin } }
}

private val OcrLineGeometry.centerX: Float
    get() = (xmin + xmax) / 2f

private val OcrLineGeometry.centerY: Float
    get() = (ymin + ymax) / 2f

internal fun isLookupStartChar(char: Char): Boolean {
    return isOcrLookupStartChar(char)
}

internal fun extractOcrLookupString(text: String, start: Int): String {
    return extractOcrLookupText(text, start)
}

internal fun OcrTextBlock.extractLookupString(global: Int, wholeWord: Boolean): String {
    if (!wholeWord) return extractOcrLookupText(fullText, global)
    val (lineStart, lineEnd) = lineBoundariesFor(global)
    return extractWholeWord(fullText, global, lineStart, lineEnd)
}

internal fun OcrTextBlock.lineBoundariesFor(offset: Int): Pair<Int, Int> {
    if (lines.isEmpty()) return 0 to 0
    var lineStart = 0
    for (line in lines) {
        val lineEnd = lineStart + line.length
        if (offset < lineEnd) return lineStart to lineEnd
        lineStart = lineEnd
    }
    return lineStart to fullText.length
}

internal fun uniformCharOffset(
    block: OcrTextBlock,
    localX: Float,
    localY: Float,
    screenW: Float,
    screenH: Float,
): Int {
    if (block.vertical) {
        val columns = block.lines.ifEmpty { listOf(block.fullText) }
        if (columns.isEmpty()) return 0

        val columnWidth = (screenW / columns.size.coerceAtLeast(1)).coerceAtLeast(1f)
        val maxChars = columns.maxOfOrNull { it.length }?.coerceAtLeast(1) ?: 1
        val rowHeight = (screenH / maxChars).coerceAtLeast(1f)
        val contentTop = (screenH - rowHeight * maxChars) / 2f

        val fromRight = (screenW - localX).coerceIn(0f, screenW)
        val columnIndex = (fromRight / columnWidth)
            .toInt()
            .coerceIn(0, columns.lastIndex)

        val columnText = columns[columnIndex]
        val charsInColumn = columnText.length.coerceAtLeast(1)
        val yInColumn = (localY - contentTop)
            .coerceIn(0f, (rowHeight * charsInColumn - 0.001f).coerceAtLeast(0f))
        val charIndex = (yInColumn / rowHeight)
            .toInt()
            .coerceIn(0, charsInColumn - 1)

        return columns.take(columnIndex).sumOf { it.length } + charIndex
    }

    val lineCount = block.lines.size.coerceAtLeast(1)
    val lineIndex = (localY / (screenH / lineCount))
        .toInt().coerceIn(0, lineCount - 1)
    val line = block.lines[lineIndex]
    val charIndex = (localX / (screenW / line.length.coerceAtLeast(1)))
        .toInt().coerceIn(0, line.length - 1)
    return block.lines.take(lineIndex).sumOf { it.length } + charIndex
}

private fun trimLineOverlap(prev: String, curr: String): String {
    val a = prev.trimEnd()
    val b = curr.trimStart()
    if (a.length < 4 || b.length < 4) return curr.trimStart()
    val maxLen = minOf(a.length, b.length)
    for (len in maxLen downTo 4) {
        if (a.takeLast(len) != b.take(len)) continue
        val beforeWord = len == a.length || a[a.length - len - 1].isWhitespace()
        val afterWord = len == b.length || b[len].isWhitespace()
        if (beforeWord && afterWord) {
            return b.substring(len).trimStart()
        }
    }
    return curr.trimStart()
}
