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

fun cropBitmap(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return bitmap
    val x = (left * w).toInt().coerceIn(0, w - 1)
    val y = (top * h).toInt().coerceIn(0, h - 1)
    val r = (right * w).toInt().coerceIn(x + 1, w)
    val b = (bottom * h).toInt().coerceIn(y + 1, h)
    return Bitmap.createBitmap(bitmap, x, y, r - x, b - y)
}

fun cropAroundAnchor(
    bitmap: Bitmap,
    anchorX: Float,
    anchorY: Float,
    anchorWidth: Float,
    anchorHeight: Float,
    aspectX: Int,
    aspectY: Int,
    paddingFactor: Float = 1.5f,
): Bitmap {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val cx = ((anchorX + anchorWidth / 2f) / bw).coerceIn(0f, 1f)
    val cy = ((anchorY + anchorHeight / 2f) / bh).coerceIn(0f, 1f)
    val textW = (anchorWidth / bw * paddingFactor).coerceAtLeast(0.01f)
    val textH = (anchorHeight / bh * paddingFactor).coerceAtLeast(0.01f)
    val pixelRatio = if (aspectY > 0) aspectX.toFloat() / aspectY.toFloat() else 1f
    val normRatio = pixelRatio * bh / bw
    var cropW: Float
    var cropH: Float
    if (textW / textH > normRatio) {
        cropH = textH; cropW = textH * normRatio
    } else {
        cropW = textW; cropH = textW / normRatio
    }
    val minSize = 0.20f
    val maxSize = 0.80f
    if (cropW < minSize || cropH < minSize) {
        val scale = minSize / minOf(cropW, cropH).coerceAtLeast(0.001f)
        cropW *= scale; cropH *= scale
    }
    if (cropW > maxSize || cropH > maxSize) {
        val scale = maxSize / maxOf(cropW, cropH)
        cropW *= scale; cropH *= scale
    }
    val maxHalfW = minOf(cx, 1f - cx)
    val maxHalfH = minOf(cy, 1f - cy)
    var halfW = cropW / 2f
    var halfH = cropH / 2f
    if (halfW > maxHalfW) {
        halfW = maxHalfW
        halfH = halfW / normRatio
    }
    if (halfH > maxHalfH) {
        halfH = maxHalfH
        halfW = halfH * normRatio
    }
    halfW = halfW.coerceAtMost(maxHalfW)
    halfH = halfH.coerceAtMost(maxHalfH)
    val left = (cx - halfW).coerceAtLeast(0f)
    val top = (cy - halfH).coerceAtLeast(0f)
    val right = (cx + halfW).coerceAtMost(1f)
    val bottom = (cy + halfH).coerceAtMost(1f)
    return cropBitmap(bitmap, left, top, right, bottom)
}
