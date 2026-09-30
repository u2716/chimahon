package eu.kanade.tachiyomi.ui.dictionary

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import chimahon.ocr.OcrHitTester
import eu.kanade.tachiyomi.ui.reader.viewer.OcrLineGeometry
import eu.kanade.tachiyomi.ui.reader.viewer.OcrTextBlock
import eu.kanade.tachiyomi.ui.reader.viewer.extractOcrLookupString
import eu.kanade.tachiyomi.ui.reader.viewer.hitTest
import eu.kanade.tachiyomi.ui.reader.viewer.isLookupStartChar
import eu.kanade.tachiyomi.ui.reader.viewer.orderedFullText
import eu.kanade.tachiyomi.ui.reader.viewer.orderedLineStarts
import eu.kanade.tachiyomi.ui.reader.viewer.toPxRect

data class OcrSelection(
    val block: OcrTextBlock,
    val lookupString: String,
    val sentence: String,
    val sentenceOffset: Int,
    val anchorX: Float,
    val anchorY: Float,
    val anchorWidth: Float,
    val anchorHeight: Float,
)

/** Anchor rectangle (canvas pixels) used to position the lookup popup. */
data class BlockAnchor(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

fun OcrTextBlock.toAnchor(canvasWidth: Float, canvasHeight: Float): BlockAnchor = BlockAnchor(
    x = xmin * canvasWidth,
    y = ymin * canvasHeight,
    width = (xmax - xmin) * canvasWidth,
    height = (ymax - ymin) * canvasHeight,
)

/**
 * Resolve a block tap into a lookup selection.
 *
 * Returns null when the tap didn't land on a lookup-start character, produced a blank
 * lookup string, or matched the current selection (no-op). Callers own side effects.
 */
fun resolveOcrTap(
    tapped: OcrTextBlock,
    tapX: Float,
    tapY: Float,
    lineIndex: Int?,
    canvasWidth: Float,
    canvasHeight: Float,
    currentSelection: OcrSelection?,
): OcrSelection? {
    val charOffset = tapped.screenLookupCharOffset(tapX, tapY, lineIndex)
    val text = tapped.orderedFullText
    if (charOffset !in text.indices) return null
    if (currentSelection?.block == tapped && currentSelection.sentenceOffset == charOffset) return null
    if (!isLookupStartChar(text[charOffset])) return null
    val lookupString = extractOcrLookupString(text, charOffset)
    if (lookupString.isBlank()) return null
    val anchor = tapped.toAnchor(canvasWidth, canvasHeight)
    return OcrSelection(
        block = tapped,
        lookupString = lookupString,
        sentence = text,
        sentenceOffset = charOffset,
        anchorX = anchor.x,
        anchorY = anchor.y,
        anchorWidth = anchor.width,
        anchorHeight = anchor.height,
    )
}

private val borderColor = Color(0, 170, 255, 180)
private val activeFillColor = Color.White.copy(alpha = 0.25f)
private val inactiveFillColor = Color.White.copy(alpha = 0.10f)

@Composable
fun OcrBlockCanvas(
    blocks: List<OcrTextBlock>,
    boxScaleX: Float,
    boxScaleY: Float,
    activeBlock: OcrTextBlock?,
    activeMatchCount: Int,
    activeMatchOffset: Int,
    selection: OcrSelection?,
    onBlockTapped: (OcrTextBlock, Float, Float, Int?) -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(blocks, boxScaleX, boxScaleY) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val tappedMatch = blocks
                        .asSequence()
                        .flatMap { block ->
                            val geos = block.lineGeometries
                            if (geos != null && geos.size == block.lines.size) {
                                geos.asSequence().mapIndexed { idx, geo -> Triple(block, idx, geo) }
                            } else {
                                sequenceOf(Triple(block, -1, null))
                            }
                        }
                        .filter { (block, _, geo) ->
                            if (geo != null) {
                                geo.hitTest(offset.x, offset.y, boxScaleX, boxScaleY, w, h)
                            } else {
                                val r = block.toPxRect(w, h)
                                offset.x in r.left..r.right && offset.y in r.top..r.bottom
                            }
                        }
                        .minByOrNull { (_, _, geo) ->
                            geo?.let { (it.xmax - it.xmin) * (it.ymax - it.ymin) } ?: Float.MAX_VALUE
                        }

                    if (tappedMatch == null) {
                        onEmptyTap()
                    } else {
                        val (tappedBlock, lineIndex, _) = tappedMatch
                        val tapX = (offset.x / w).coerceIn(0f, 1f)
                        val tapY = (offset.y / h).coerceIn(0f, 1f)
                        onBlockTapped(tappedBlock, tapX, tapY, lineIndex.takeIf { it >= 0 })
                    }
                }
            },
    ) {
        blocks.forEach { block ->
            val isActive = block == activeBlock
            val geos = block.lineGeometries

            if (geos != null && geos.size == block.lines.size) {
                val orderedStarts = block.orderedLineStarts()
                geos.forEachIndexed { geoIndex, geo ->
                    drawOcrRect(geo.toPxRect(boxScaleX, boxScaleY, size.width, size.height), isActive)
                    if (isActive && activeMatchCount > 0 && selection != null) {
                        drawMatchHighlight(
                            block = block,
                            geo = geo,
                            geoIndex = geoIndex,
                            orderedLineStart = orderedStarts?.getOrNull(geoIndex),
                            activeMatchCount = activeMatchCount,
                            activeMatchOffset = activeMatchOffset,
                            selection = selection,
                            boxScaleX = boxScaleX,
                            boxScaleY = boxScaleY,
                            highlightColor = highlightColor,
                        )
                    }
                }
            } else {
                drawOcrRect(block.toPxRect(size.width, size.height), isActive)
            }
        }
    }
}

private fun DrawScope.drawOcrRect(rect: Rect, isActive: Boolean) {
    drawRect(
        color = if (isActive) activeFillColor else inactiveFillColor,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
    )
    drawRect(
        color = borderColor,
        topLeft = Offset(rect.left, rect.top),
        size = Size(rect.width, rect.height),
        style = Stroke(width = if (isActive) 2.dp.toPx() else 1.dp.toPx()),
    )
}

private fun DrawScope.drawMatchHighlight(
    block: OcrTextBlock,
    geo: OcrLineGeometry,
    geoIndex: Int,
    orderedLineStart: Int?,
    activeMatchCount: Int,
    activeMatchOffset: Int,
    selection: OcrSelection,
    boxScaleX: Float,
    boxScaleY: Float,
    highlightColor: Color,
) {
    if (orderedLineStart == null) return
    val lineText = block.lines.getOrNull(geoIndex) ?: return
    val lineLen = lineText.length
    val lineEnd = orderedLineStart + lineLen

    val absStart = selection.sentenceOffset + activeMatchOffset
    val absEnd = absStart + activeMatchCount
    if (absStart >= lineEnd || absEnd <= orderedLineStart) return

    val overlapL = maxOf(absStart, orderedLineStart)
    val overlapR = minOf(absEnd, lineEnd)

    // Per-line orientation: a horizontal line inside a vertical block (or vice versa)
    // must be highlighted along its own reading axis, matching how taps are resolved.
    val lineVertical = OcrHitTester.isLineVertical(
        block.vertical, geo.xmin, geo.ymin, geo.xmax, geo.ymax,
    )

    val offsets = getLineOffsets(lineText, lineVertical)
    val startIndex = (overlapL - orderedLineStart).coerceIn(0, lineLen)
    val endIndex = (overlapR - orderedLineStart).coerceIn(0, lineLen)
    val startFrac = offsets[startIndex]
    val endFrac = offsets[endIndex]

    val r = geo.toPxRect(boxScaleX, boxScaleY, size.width, size.height)
    // Undo the box-scale padding so the highlight tracks the actual glyph cells.
    val origW = r.width / boxScaleX
    val origH = r.height / boxScaleY
    val padX = (r.width - origW) / 2f
    val padY = (r.height - origH) / 2f

    if (lineVertical) {
        drawRect(
            color = highlightColor,
            topLeft = Offset(r.left, r.top + padY + origH * startFrac),
            size = Size(r.width, origH * (endFrac - startFrac)),
        )
    } else {
        drawRect(
            color = highlightColor,
            topLeft = Offset(r.left + padX + origW * startFrac, r.top),
            size = Size(origW * (endFrac - startFrac), r.height),
        )
    }
}

@Composable
fun OcrStatusOverlay(
    isLoading: Boolean,
    error: String?,
    loadingText: String,
    modifier: Modifier = Modifier,
) {
    if (!isLoading && error == null) return
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(loadingText)
            } else {
                Text(error.orEmpty())
            }
        }
    }
}

/**
 * Crops [bitmap] to the largest rectangle matching the given aspect ratio that fits
 * inside it, and centers the crop. Nothing is scaled or padded — the result touches
 * the bitmap's edges on the non-cropped axis.
 *
 * The preset is interpreted in the screenshot's native orientation: a landscape bitmap
 * uses [aspectX]:[aspectY] as-is; a portrait bitmap uses the rotated form
 * [aspectY]:[aspectX] so a "16:9" preset on a tall screenshot yields a 9:16 crop.
 * Square presets (`aspectX == aspectY`) are orientation-agnostic.
 *
 * Examples:
 *  - 1080x2400 + 16:9 → 1080x1920 (240 px trimmed from top and bottom)
 *  - 2400x1080 + 16:9 → 1920x1080 (240 px trimmed from left and right)
 *  - 1080x2400 + 1:1  → 1080x1080 (660 px trimmed from top and bottom)
 *
 * The tapped word is not consulted; the crop is always centered. A tap that lands in
 * the trimmed strip will not appear in the resulting image.
 */
fun centerCropToAspect(bitmap: Bitmap, aspectX: Int, aspectY: Int): Bitmap {
    if (aspectX <= 0 || aspectY <= 0) return bitmap
    val bw = bitmap.width
    val bh = bitmap.height
    if (bw <= 0 || bh <= 0) return bitmap

    // Portrait screenshots get the rotated form of the preset.
    val targetX: Int
    val targetY: Int
    if (aspectX == aspectY) {
        targetX = aspectX
        targetY = aspectY
    } else if (bh > bw) {
        targetX = aspectY
        targetY = aspectX
    } else {
        targetX = aspectX
        targetY = aspectY
    }

    // Cross-multiply (Long to avoid overflow on huge bitmaps) to decide which axis trims.
    val bitmapIsWiderThanTarget = bw.toLong() * targetY > bh.toLong() * targetX

    val cropW: Int
    val cropH: Int
    if (bitmapIsWiderThanTarget) {
        // Trim width, keep full height.
        cropH = bh
        cropW = (bh.toLong() * targetX / targetY).toInt().coerceAtMost(bw)
    } else {
        // Trim height, keep full width.
        cropW = bw
        cropH = (bw.toLong() * targetY / targetX).toInt().coerceAtMost(bh)
    }

    if (cropW == bw && cropH == bh) return bitmap
    val left = (bw - cropW) / 2
    val top = (bh - cropH) / 2
    return Bitmap.createBitmap(bitmap, left, top, cropW, cropH)
}
