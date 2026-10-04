package chimahon.local.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import chimahon.ocr.EngineLine
import chimahon.ocr.NormalizedBBox
import chimahon.ocr.OcrBitmapDecoder
import chimahon.ocr.OcrLanguage
import chimahon.ocr.WritingDirection
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore

/**
 * On-device OCR for Japanese using the meikiocr pipeline
 * (github.com/rtr46/meikiocr).
 *
 * Three ONNX models are executed through onnxruntime-android:
 *   - meiki.text.detect.v0.1.960x544.onnx       text-line detection
 *   - meiki.text.rec.v0.960x32.onnx             horizontal recognition
 *   - meiki.text.rec.v0.vertical.32x480.onnx    vertical recognition
 *
 * The pipeline is a faithful Kotlin port of meikiocr/ocr.py:
 *
 *   1. Resize the page to fit 960x544, pad top-left, normalize to BGR/255,
 *      layout CHW, float32.
 *   2. Run the detector. Output boxes are in the *original* image pixel
 *      coordinate space (the model was trained with orig_target_sizes).
 *   3. Split detections into horizontal (w>=h) and vertical (h>w) groups.
 *   4. Preprocess each crop:
 *        horizontal -> resize to height 32, pad to 960x32
 *        vertical   -> resize to width 32, split into overlapping segments
 *                      if the scaled height exceeds 480px, pad each segment
 *                      to 32x480
 *   5. Run the appropriate recognizer on each crop (batch size 1 for
 *      predictable memory usage on mobile).
 *   6. Map each character box back to original pixel space.
 *   7. Interval NMS per line, sort by interval start, assemble text,
 *      apply the swapped-pair fix.
 *   8. Emit EngineLine with normalized bbox + TTB/LTR direction.
 *
 * Meiki is Japanese-only; the recognition vocabulary is fixed Japanese. The
 * engine accepts any OcrLanguage but always emits Japanese-aware text.
 */
class MeikiOcrEngine(context: Context) : chimahon.ocr.OcrEngine, Closeable {

    private val TAG = "MeikiOcrEngine"
    private val appContext = context.applicationContext

    override val name: String = "Meiki OCR"

    @Volatile
    private var isInitialized = false

    private val lock = Any()
    private val inferPermits = Semaphore(INFER_PERMITS)

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var vrecSession: OrtSession? = null

    private val deviceThreads: Int by lazy {
        detectPerformanceCores().coerceIn(1, 4)
    }

    // ────────────────────────── init / destroy ──────────────────────────

    fun init(): Boolean = synchronized(lock) {
        if (isInitialized) return true

        val modelDir = File(appContext.filesDir, MODEL_DIR)
        val detFile = File(modelDir, DET_MODEL)
        val recFile = File(modelDir, REC_MODEL)
        val vrecFile = File(modelDir, VREC_MODEL)

        if (!detFile.isFile || detFile.length() == 0L ||
            !recFile.isFile || recFile.length() == 0L ||
            !vrecFile.isFile || vrecFile.length() == 0L
        ) {
            Log.e(TAG, "Missing meiki ONNX files in ${modelDir.absolutePath}")
            return false
        }

        val start = SystemClock.elapsedRealtime()
        try {
            val environment = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(deviceThreads)
            }
            detSession = environment.createSession(detFile.absolutePath, opts)
            recSession = environment.createSession(recFile.absolutePath, opts)
            vrecSession = environment.createSession(vrecFile.absolutePath, opts)
            env = environment
            isInitialized = true
            Log.i(TAG, "Meiki OCR initialized in ${SystemClock.elapsedRealtime() - start}ms")
        } catch (e: Throwable) {
            Log.e(TAG, "Meiki OCR init failed", e)
            runCatching { detSession?.close() }
            runCatching { recSession?.close() }
            runCatching { vrecSession?.close() }
            detSession = null
            recSession = null
            vrecSession = null
            return false
        }
        return true
    }

    fun isInitialized(): Boolean = isInitialized

    fun destroy() {
        runBlocking {
            repeat(INFER_PERMITS) { inferPermits.acquire() }
        }
        try {
            synchronized(lock) {
                if (isInitialized) {
                    runCatching { detSession?.close() }
                    runCatching { recSession?.close() }
                    runCatching { vrecSession?.close() }
                    detSession = null
                    recSession = null
                    vrecSession = null
                    isInitialized = false
                    Log.i(TAG, "Meiki OCR destroyed")
                }
            }
        } finally {
            repeat(INFER_PERMITS) { inferPermits.release() }
        }
    }

    override fun close() = destroy()

    // ────────────────────────── public entry ──────────────────────────

    override suspend fun recognize(
        bytes: ByteArray,
        language: OcrLanguage,
    ): List<EngineLine> {
        inferPermits.acquire()
        try {
            if (!isInitialized) init()
            if (!isInitialized) return emptyList()

            val bitmap = try {
                OcrBitmapDecoder.decode(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "decode failed", e)
                return emptyList()
            }
            return try {
                runPipeline(bitmap, language)
            } finally {
                bitmap.recycle()
            }
        } finally {
            inferPermits.release()
        }
    }

    // ────────────────────────── pipeline ──────────────────────────

    private fun runPipeline(bitmap: Bitmap, language: OcrLanguage): List<EngineLine> {
        val imgW = bitmap.width
        val imgH = bitmap.height
        if (imgW <= 0 || imgH <= 0) return emptyList()

        val detections = runDetection(bitmap)
        if (detections.isEmpty()) return emptyList()

        val hIdx = ArrayList<Int>(detections.size)
        val vIdx = ArrayList<Int>(detections.size)
        detections.forEachIndexed { i, d ->
            val w = d.x2 - d.x1
            val h = d.y2 - d.y1
            if (w <= 0 || h <= 0) return@forEachIndexed
            if (h > w) vIdx += i else hIdx += i
        }

        val results = arrayOfNulls<LineResult>(detections.size)
        if (hIdx.isNotEmpty()) processGroup(bitmap, detections, hIdx, isVertical = false, out = results)
        if (vIdx.isNotEmpty()) processGroup(bitmap, detections, vIdx, isVertical = true, out = results)

        return results.filterNotNull().mapNotNull { it.toEngineLine(imgW, imgH, language) }
    }

    // ────────────────────────── detection ──────────────────────────

    private data class DetBox(
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val conf: Float,
    )

    private fun runDetection(bitmap: Bitmap): List<DetBox> {
        val e = env ?: return emptyList()
        val wOrig = bitmap.width
        val hOrig = bitmap.height

        val scale = min(
            INPUT_DET_WIDTH.toFloat() / wOrig,
            INPUT_DET_HEIGHT.toFloat() / hOrig,
        )
        val wResized = (wOrig * scale).roundToInt().coerceAtLeast(1)
        val hResized = (hOrig * scale).roundToInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, wResized, hResized, true)
        val pixels = IntArray(wResized * hResized)
        resized.getPixels(pixels, 0, wResized, 0, 0, wResized, hResized)
        if (resized !== bitmap) resized.recycle()

        val chw = buildChwTensor(pixels, wResized, hResized, INPUT_DET_WIDTH, INPUT_DET_HEIGHT)

        val imagesTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(chw),
            longArrayOf(1L, 3L, INPUT_DET_HEIGHT.toLong(), INPUT_DET_WIDTH.toLong()),
        )
        val sizesArr = longArrayOf(
            (INPUT_DET_WIDTH / scale).roundToInt().toLong(),
            (INPUT_DET_HEIGHT / scale).roundToInt().toLong(),
        )
        val sizesTensor = OnnxTensor.createTensor(
            e,
            LongBuffer.wrap(sizesArr),
            longArrayOf(1L, 2L),
        )

        val session = detSession ?: return emptyList()
        val inputNames = session.inputNames.toList()
        if (inputNames.size < 2) {
            imagesTensor.close()
            sizesTensor.close()
            Log.e(TAG, "detector has ${inputNames.size} inputs, expected 2")
            return emptyList()
        }

        try {
            val inputs = mapOf(
                inputNames[0] to imagesTensor,
                inputNames[1] to sizesTensor,
            )
            val result = session.run(inputs)
            result.use { r ->
                // Python: `_, boxes, scores = session.run(...)`
                val boxesT = r.get(1) as OnnxTensor
                val scoresT = r.get(2) as OnnxTensor
                val boxesFlat = tensorToFloats(boxesT) ?: return emptyList()
                val scoresFlat = tensorToFloats(scoresT) ?: return emptyList()
                val n = scoresFlat.size
                if (boxesFlat.size < n * 4) return emptyList()
                val out = ArrayList<DetBox>(n)
                val maxX = wOrig.toFloat()
                val maxY = hOrig.toFloat()
                for (i in 0 until n) {
                    val conf = scoresFlat[i]
                    if (conf < DET_CONF_THRESHOLD) continue
                    val b = i * 4
                    val x1 = boxesFlat[b].coerceIn(0f, maxX).toInt()
                    val y1 = boxesFlat[b + 1].coerceIn(0f, maxY).toInt()
                    val x2 = boxesFlat[b + 2].coerceIn(0f, maxX).toInt()
                    val y2 = boxesFlat[b + 3].coerceIn(0f, maxY).toInt()
                    if (x2 > x1 && y2 > y1) out += DetBox(x1, y1, x2, y2, conf)
                }
                out.sortBy { it.y1 }
                return out
            }
        } finally {
            imagesTensor.close()
            sizesTensor.close()
        }
    }

    // ────────────────────────── crop preprocessing ──────────────────────────

    private data class HorizontalCrop(
        val tensorArray: FloatArray,
        val origX1: Int, val origY1: Int, val origX2: Int, val origY2: Int,
        val effectiveW: Int, val effectiveH: Int,
    )

    private data class VerticalCrop(
        val tensorArray: FloatArray,
        val origX1: Int, val origY1: Int, val origX2: Int, val origY2: Int,
        val effectiveH: Int,
    )

    private fun preprocessHorizontalCrop(bitmap: Bitmap, box: DetBox): HorizontalCrop? {
        val cropW = box.x2 - box.x1
        val cropH = box.y2 - box.y1
        if (cropW <= 0 || cropH <= 0) return null

        var newH = INPUT_REC_HEIGHT
        val scaleH = newH.toFloat() / cropH
        var newW = (cropW * scaleH).roundToInt().coerceAtLeast(1)
        if (newW > INPUT_REC_WIDTH) {
            val scaleW = INPUT_REC_WIDTH.toFloat() / newW
            newW = INPUT_REC_WIDTH
            newH = (newH * scaleW).roundToInt().coerceAtLeast(1)
        }

        val crop = Bitmap.createBitmap(bitmap, box.x1, box.y1, cropW, cropH)
        val resized = Bitmap.createScaledBitmap(crop, newW, newH, true)
        if (crop !== bitmap) crop.recycle()
        val pixels = IntArray(newW * newH)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, newH)
        if (resized !== bitmap) resized.recycle()

        return HorizontalCrop(
            tensorArray = buildChwTensor(pixels, newW, newH, INPUT_REC_WIDTH, INPUT_REC_HEIGHT),
            origX1 = box.x1, origY1 = box.y1, origX2 = box.x2, origY2 = box.y2,
            effectiveW = newW, effectiveH = newH,
        )
    }

    private fun preprocessVerticalCrops(bitmap: Bitmap, box: DetBox): List<VerticalCrop> {
        val x1 = box.x1; val y1 = box.y1; val x2 = box.x2; val y2 = box.y2
        val cropW = x2 - x1
        val cropH = y2 - y1
        if (cropW <= 0 || cropH <= 0) return emptyList()

        val scale = INPUT_VREC_WIDTH.toFloat() / cropW
        val hScaledFull = cropH * scale

        val yStarts = ArrayList<Float>()
        val segmentHOrig: Float
        val maxHScaled: Int

        if (hScaledFull > INPUT_VREC_HEIGHT) {
            maxHScaled = VREC_MAX_CONTENT_HEIGHT
            segmentHOrig = VREC_MAX_CONTENT_HEIGHT / scale
            val strideOrig = (VREC_MAX_CONTENT_HEIGHT - VREC_OVERLAP_PX) / scale
            var currY = y1.toFloat()
            while (currY + segmentHOrig < y2) {
                yStarts += currY
                currY += strideOrig
            }
            val lastY = y2 - segmentHOrig
            if (yStarts.isEmpty() || lastY > yStarts.last() + 1.0f) {
                yStarts += lastY
            }
        } else {
            maxHScaled = INPUT_VREC_HEIGHT
            segmentHOrig = (y2 - y1).toFloat()
            yStarts += y1.toFloat()
        }

        val out = ArrayList<VerticalCrop>(yStarts.size)
        for (sy1f in yStarts) {
            val sy1 = sy1f.roundToInt()
            val sy2 = minOf((sy1f + segmentHOrig).roundToInt(), y2)
            val segH = sy2 - sy1
            if (segH <= 0) continue
            val segNewH = minOf((segH * scale).roundToInt(), maxHScaled).coerceAtLeast(1)

            val crop = Bitmap.createBitmap(bitmap, x1, sy1, cropW, segH)
            val resized = Bitmap.createScaledBitmap(crop, INPUT_VREC_WIDTH, segNewH, true)
            if (crop !== bitmap) crop.recycle()
            val pixels = IntArray(INPUT_VREC_WIDTH * segNewH)
            resized.getPixels(pixels, 0, INPUT_VREC_WIDTH, 0, 0, INPUT_VREC_WIDTH, segNewH)
            if (resized !== bitmap) resized.recycle()

            out += VerticalCrop(
                tensorArray = buildChwTensor(pixels, INPUT_VREC_WIDTH, segNewH, INPUT_VREC_WIDTH, INPUT_VREC_HEIGHT),
                origX1 = x1, origY1 = sy1, origX2 = x2, origY2 = sy2,
                effectiveH = segNewH,
            )
        }
        return out
    }

    // ────────────────────────── recognition ──────────────────────────

    private class RecOutput(
        val numQueries: Int,
        val labels: IntArray,
        val boxes: FloatArray,
        val scores: FloatArray,
    )

    private fun runRecognitionSingle(crop: FloatArray, isVertical: Boolean): RecOutput? {
        val e = env ?: return null
        val h = if (isVertical) INPUT_VREC_HEIGHT else INPUT_REC_HEIGHT
        val w = if (isVertical) INPUT_VREC_WIDTH else INPUT_REC_WIDTH

        val imagesTensor = OnnxTensor.createTensor(
            e,
            FloatBuffer.wrap(crop),
            longArrayOf(1L, 3L, h.toLong(), w.toLong()),
        )
        val sizesTensor = OnnxTensor.createTensor(
            e,
            LongBuffer.wrap(longArrayOf(w.toLong(), h.toLong())),
            longArrayOf(1L, 2L),
        )

        val session = if (isVertical) vrecSession else recSession
        if (session == null) {
            imagesTensor.close()
            sizesTensor.close()
            return null
        }
        val inputNames = session.inputNames.toList()
        if (inputNames.size < 2) {
            imagesTensor.close()
            sizesTensor.close()
            return null
        }

        try {
            val inputs = mapOf(
                inputNames[0] to imagesTensor,
                inputNames[1] to sizesTensor,
            )
            val result = session.run(inputs)
            result.use { r ->
                val labelsT = r.get(0) as OnnxTensor
                val boxesT = r.get(1) as OnnxTensor
                val scoresT = r.get(2) as OnnxTensor
                return parseRecOutput(labelsT, boxesT, scoresT)
            }
        } finally {
            imagesTensor.close()
            sizesTensor.close()
        }
    }

    private fun parseRecOutput(
        labelsT: OnnxTensor,
        boxesT: OnnxTensor,
        scoresT: OnnxTensor,
    ): RecOutput {
        val scoresFlat = tensorToFloats(scoresT)
            ?: return RecOutput(0, IntArray(0), FloatArray(0), FloatArray(0))
        val boxesFlat = tensorToFloats(boxesT)
            ?: return RecOutput(0, IntArray(0), FloatArray(0), FloatArray(0))
        val labelsFlat = tensorToInts(labelsT)
            ?: return RecOutput(0, IntArray(0), FloatArray(0), FloatArray(0))
        return RecOutput(scoresFlat.size, labelsFlat, boxesFlat, scoresFlat)
    }

    // ---- ONNX Runtime tensor readers ----
    //
    // getValue() returns the tensor contents as nested Java primitive arrays
    // matching the tensor shape (FLOAT [1,N,4] -> float[][][], INT64 [1,N] ->
    // long[][]). It is stable across ONNX Runtime versions, unlike the
    // getFloatBuffer()/getLongBuffer()/getIntBuffer() accessors whose return
    // type changed in 1.30.0 and broke Kotlin interop. We flatten the nested
    // arrays into contiguous 1D arrays.

    private fun tensorToFloats(t: OnnxTensor): FloatArray? = try {
        collectFloats(t.value)
    } catch (_: Throwable) {
        null
    }

    private fun collectFloats(v: Any?): FloatArray? = when (v) {
        null -> null
        is FloatArray -> v
        is java.nio.FloatBuffer -> {
            val dup = v.duplicate()
            val out = FloatArray(dup.remaining())
            dup.get(out)
            out
        }
        is Array<*> -> {
            val pieces = ArrayList<FloatArray>(v.size)
            var total = 0
            for (item in v) {
                val sub = collectFloats(item) ?: return null
                pieces += sub
                total += sub.size
            }
            val out = FloatArray(total)
            var pos = 0
            for (p in pieces) {
                System.arraycopy(p, 0, out, pos, p.size)
                pos += p.size
            }
            out
        }
        else -> null
    }

    private fun tensorToInts(t: OnnxTensor): IntArray? = try {
        collectInts(t.value)
    } catch (_: Throwable) {
        null
    }

    private fun collectInts(v: Any?): IntArray? = when (v) {
        null -> null
        is IntArray -> v
        is LongArray -> {
            val out = IntArray(v.size)
            for (i in v.indices) out[i] = v[i].toInt()
            out
        }
        is FloatArray -> {
            val out = IntArray(v.size)
            for (i in v.indices) out[i] = v[i].toInt()
            out
        }
        is Array<*> -> {
            val pieces = ArrayList<IntArray>(v.size)
            var total = 0
            for (item in v) {
                val sub = collectInts(item) ?: return null
                pieces += sub
                total += sub.size
            }
            val out = IntArray(total)
            var pos = 0
            for (p in pieces) {
                System.arraycopy(p, 0, out, pos, p.size)
                pos += p.size
            }
            out
        }
        is Number -> intArrayOf(v.toInt())
        else -> null
    }

    // ────────────────────────── per-group processing ──────────────────────────

    private data class CropMeta(
        val lineIdx: Int,
        val origX1: Int, val origY1: Int, val origX2: Int, val origY2: Int,
        val effectiveW: Int, val effectiveH: Int,
    )

    private data class LineResult(
        val text: String,
        val chars: List<MeikiCharCandidate>,
        val isVertical: Boolean,
    )

    private fun processGroup(
        bitmap: Bitmap,
        dets: List<DetBox>,
        indices: List<Int>,
        isVertical: Boolean,
        out: Array<LineResult?>,
    ) {
        val candidatesByLine = HashMap<Int, MutableList<MeikiCharCandidate>>()

        for (di in indices) {
            val det = dets[di]
            if (isVertical) {
                for (vc in preprocessVerticalCrops(bitmap, det)) {
                    val meta = CropMeta(
                        lineIdx = di,
                        origX1 = vc.origX1, origY1 = vc.origY1,
                        origX2 = vc.origX2, origY2 = vc.origY2,
                        effectiveW = INPUT_VREC_WIDTH, effectiveH = vc.effectiveH,
                    )
                    val rec = runRecognitionSingle(vc.tensorArray, isVertical = true) ?: continue
                    distributeCropOutput(rec, meta, isVertical = true, candidatesByLine)
                }
            } else {
                val hc = preprocessHorizontalCrop(bitmap, det) ?: continue
                val meta = CropMeta(
                    lineIdx = di,
                    origX1 = hc.origX1, origY1 = hc.origY1,
                    origX2 = hc.origX2, origY2 = hc.origY2,
                    effectiveW = hc.effectiveW, effectiveH = hc.effectiveH,
                )
                val rec = runRecognitionSingle(hc.tensorArray, isVertical = false) ?: continue
                distributeCropOutput(rec, meta, isVertical = false, candidatesByLine)
            }
        }

        for (lineIdx in indices) {
            val candidates = candidatesByLine[lineIdx] ?: continue
            val line = nmsAndBuildLine(candidates, isVertical) ?: continue
            out[lineIdx] = line
        }
    }

    private fun distributeCropOutput(
        output: RecOutput,
        meta: CropMeta,
        isVertical: Boolean,
        candidatesByLine: HashMap<Int, MutableList<MeikiCharCandidate>>,
    ) {
        val n = output.numQueries
        if (n == 0) return
        val cropW = meta.origX2 - meta.origX1
        val cropH = meta.origY2 - meta.origY1
        if (cropW <= 0 || cropH <= 0) return
        val candidates = candidatesByLine.getOrPut(meta.lineIdx) { mutableListOf() }

        for (q in 0 until n) {
            val score = output.scores[q]
            if (score < REC_CONF_THRESHOLD) continue
            val label = output.labels[q]
            if (label <= 0 || label > 0x10FFFF) continue
            val char = try {
                String(Character.toChars(label)).firstOrNull()
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue

            val boxBase = q * 4
            val rx1 = output.boxes[boxBase]
            val ry1 = output.boxes[boxBase + 1]
            val rx2 = output.boxes[boxBase + 2]
            val ry2 = output.boxes[boxBase + 3]

            if (!isVertical) {
                val effW = meta.effectiveW.toFloat()
                if (rx1 >= effW) continue
                val crx1 = min(rx1, effW)
                val crx2 = min(rx2, effW)
                val cx1 = (crx1 / effW) * cropW
                val cx2 = (crx2 / effW) * cropW
                val cy1 = (ry1 / INPUT_REC_HEIGHT) * cropH
                val cy2 = (ry2 / INPUT_REC_HEIGHT) * cropH
                val gx1 = meta.origX1 + cx1.toInt()
                val gy1 = meta.origY1 + cy1.toInt()
                val gx2 = meta.origX1 + cx2.toInt()
                val gy2 = meta.origY1 + cy2.toInt()
                if (gx2 <= gx1) continue
                candidates += MeikiCharCandidate(
                    char = char,
                    bbox = intArrayOf(gx1, gy1, gx2, gy2),
                    conf = score,
                    intervalStart = gx1,
                    intervalEnd = gx2,
                )
            } else {
                val effH = meta.effectiveH.toFloat()
                if (ry1 >= effH) continue
                val cry1 = min(ry1, effH)
                val cry2 = min(ry2, effH)
                val cx1 = (rx1 / INPUT_VREC_WIDTH) * cropW
                val cx2 = (rx2 / INPUT_VREC_WIDTH) * cropW
                val cy1 = (cry1 / effH) * cropH
                val cy2 = (cry2 / effH) * cropH
                val gx1 = meta.origX1 + cx1.toInt()
                val gy1 = meta.origY1 + cy1.toInt()
                val gx2 = meta.origX1 + cx2.toInt()
                val gy2 = meta.origY1 + cy2.toInt()
                if (gy2 <= gy1) continue
                candidates += MeikiCharCandidate(
                    char = char,
                    bbox = intArrayOf(gx1, gy1, gx2, gy2),
                    conf = score,
                    intervalStart = gy1,
                    intervalEnd = gy2,
                )
            }
        }
    }

    private fun nmsAndBuildLine(
        candidatesIn: MutableList<MeikiCharCandidate>,
        isVertical: Boolean,
    ): LineResult? {
        val accepted = nmsCandidates(candidatesIn, PUNCT_CONF_FACTOR)
        if (accepted.isEmpty()) return null
        val mutableAccepted = accepted.toMutableList()
        var text = buildString { mutableAccepted.forEach { append(it.char) } }
        text = fixSwappedPairs(text, mutableAccepted)
        return LineResult(text = text, chars = mutableAccepted, isVertical = isVertical)
    }

    // ────────────────────────── EngineLine conversion ──────────────────────────

    private fun LineResult.toEngineLine(imgW: Int, imgH: Int, language: OcrLanguage): EngineLine? {
        if (chars.isEmpty()) return null
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (c in chars) {
            if (c.bbox[0] < left) left = c.bbox[0]
            if (c.bbox[1] < top) top = c.bbox[1]
            if (c.bbox[2] > right) right = c.bbox[2]
            if (c.bbox[3] > bottom) bottom = c.bbox[3]
        }
        if (right <= left || bottom <= top) return null
        val normBbox = NormalizedBBox(
            left = left.toDouble() / imgW,
            top = top.toDouble() / imgH,
            right = right.toDouble() / imgW,
            bottom = bottom.toDouble() / imgH,
        )
        val direction = if (isVertical) WritingDirection.TTB else WritingDirection.LTR
        return EngineLine(
            text = text,
            bbox = normBbox,
            writingDirection = direction,
            language = language,
        )
    }

    // ────────────────────────── helpers ──────────────────────────

    private fun detectPerformanceCores(): Int {
        return try {
            val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val freqs = ArrayList<Long>()
            for (i in 0 until total) {
                val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                if (f.isFile && f.canRead()) {
                    f.readText().trim().toLongOrNull()?.takeIf { it > 0 }?.let { freqs += it }
                }
            }
            if (freqs.isNotEmpty()) {
                val mx = freqs.max()
                val big = freqs.count { it >= (mx * 0.85).toLong() }
                if (big in 1..total) return big
            }
            if (total >= 8) 4 else (total / 2).coerceAtLeast(1)
        } catch (_: Throwable) {
            4.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        }
    }

    companion object {
        private const val MODEL_DIR = "meiki_ocr"

        // Must match the filenames uploaded to chimahon-local-models/meiki_ocr/
        private const val DET_MODEL = "meiki.text.detect.v0.1.960x544.onnx"
        private const val REC_MODEL = "meiki.text.rec.v0.960x32.onnx"
        private const val VREC_MODEL = "meiki.text.rec.v0.vertical.32x480.onnx"

        // Detection input dims (from ocr.py)
        private const val INPUT_DET_WIDTH = 960
        private const val INPUT_DET_HEIGHT = 544

        // Horizontal recognition
        private const val INPUT_REC_HEIGHT = 32
        private const val INPUT_REC_WIDTH = 960

        // Vertical recognition
        private const val INPUT_VREC_WIDTH = 32
        private const val INPUT_VREC_HEIGHT = 480
        private const val VREC_MAX_CONTENT_HEIGHT = 420
        private const val VREC_OVERLAP_PX = 64

        // Thresholds matching ocr.py defaults, with demo's punct factor.
        private const val DET_CONF_THRESHOLD = 0.5f
        private const val REC_CONF_THRESHOLD = 0.1f
        private const val PUNCT_CONF_FACTOR = 0.2f

        // Same convention as PaddleOcrEngine: 1 permit on <=4-core devices,
        // 2 on beefier ones.
        private val INFER_PERMITS: Int =
            if (Runtime.getRuntime().availableProcessors() >= 8) 2 else 1
    }
}
