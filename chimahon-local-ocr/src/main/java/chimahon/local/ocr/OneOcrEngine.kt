package chimahon.local.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import chimahon.ocr.EngineLine
import chimahon.ocr.NormalizedBBox
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import chimahon.ocr.WritingDirection
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OneOCR engine: Microsoft Windows 11 Snipping Tool OCR, extracted to portable
 * ONNX by pasitgut/one-ocr and bropines/oneocr-onnx-python.
 *
 * Pipeline (mirrors bropines/oneocr/engine.py):
 *   detector -> per-crop classifier -> recognizer -> CTC decode
 *
 * Detector:   RGB [0..255] no normalization, dynamic [1,3,H,W] with H,W % 32.
 * Classifier: BGR [0..1], [1, 3, 60, 200]; picks the recognizer per crop so a
 *             single page can mix scripts (e.g. Latin chapter title above a
 *             Japanese conversation bubble).
 * Recognizer: BGR [0..1], [1, 3, 60, W] with seq_lengths = W/4.
 * Vocab:      "<idx>: <char>" file, raw index == model class index.
 */
class OneOcrEngine(private val context: Context) : OcrEngine {

    override val name: String = "OneOCR"

    companion object {
        const val DIR = "one_ocr"
        const val MAX_SIDE = 1536
        const val REC_HEIGHT = 60
        const val SCORE_THRESHOLD = 0.5f
        const val LINK_THRESHOLD = 0f
        const val REC_WIDTH_SAFETY_CAP = 8192

        val requiredFiles: List<String> = listOf(
            "detector/text_detector.onnx",
            "classifier/script_classifier.onnx",
            "recognizers/recognizer_cjk.onnx",
            "recognizers/recognizer_latin.onnx",
            "recognizers/recognizer_cyrillic.onnx",
            "recognizers/recognizer_arabic.onnx",
            "recognizers/recognizer_devanagari.onnx",
            "recognizers/recognizer_hebrew.onnx",
            "recognizers/recognizer_thai.onnx",
            "recognizers/recognizer_greek.onnx",
            "vocab/vocab_cjk.txt",
            "vocab/vocab_latin.txt",
            "vocab/vocab_cyrillic.txt",
            "vocab/vocab_arabic.txt",
            "vocab/vocab_devanagari.txt",
            "vocab/vocab_hebrew.txt",
            "vocab/vocab_thai.txt",
            "vocab/vocab_greek.txt",
        )
    }

    private data class Spec(val rec: String, val vocab: String, val vocabSize: Int)

    private data class ScriptInfo(val name: String, val vocabSize: Int)

    // Matches bropines/common.py SCRIPT_METADATA. Keys are +1 relative to the
    // classifier's argmax output (see engine.py: SCRIPT_METADATA.get(id+1)).
    private val SCRIPT_METADATA = mapOf(
        1 to ScriptInfo("cjk", 32632),
        2 to ScriptInfo("cyrillic", 548),
        3 to ScriptInfo("latin", 415),
        4 to ScriptInfo("arabic", 221),
        5 to ScriptInfo("devanagari", 237),
        6 to ScriptInfo("hebrew", 244),
        7 to ScriptInfo("thai", 199),
        8 to ScriptInfo("bengali", 201),
        9 to ScriptInfo("greek", 179),
    )

    private fun specForScript(scriptName: String): Spec? = when (scriptName) {
        "cjk" -> Spec("recognizers/recognizer_cjk.onnx", "vocab/vocab_cjk.txt", 32632)
        "cyrillic" -> Spec("recognizers/recognizer_cyrillic.onnx", "vocab/vocab_cyrillic.txt", 548)
        "latin" -> Spec("recognizers/recognizer_latin.onnx", "vocab/vocab_latin.txt", 415)
        "arabic" -> Spec("recognizers/recognizer_arabic.onnx", "vocab/vocab_arabic.txt", 221)
        "devanagari" -> Spec("recognizers/recognizer_devanagari.onnx", "vocab/vocab_devanagari.txt", 237)
        "hebrew" -> Spec("recognizers/recognizer_hebrew.onnx", "vocab/vocab_hebrew.txt", 244)
        "thai" -> Spec("recognizers/recognizer_thai.onnx", "vocab/vocab_thai.txt", 199)
        "greek" -> Spec("recognizers/recognizer_greek.onnx", "vocab/vocab_greek.txt", 179)
        else -> null // bengali — no recognizer in this build
    }

    private fun specForLanguage(language: OcrLanguage): Spec? = when (language) {
        OcrLanguage.JAPANESE, OcrLanguage.CHINESE, OcrLanguage.KOREAN, OcrLanguage.CANTONESE ->
            specForScript("cjk")
        OcrLanguage.ARABIC, OcrLanguage.PERSIAN -> specForScript("arabic")
        OcrLanguage.BULGARIAN, OcrLanguage.RUSSIAN, OcrLanguage.UKRAINIAN, OcrLanguage.MONGOLIAN ->
            specForScript("cyrillic")
        OcrLanguage.GREEK -> specForScript("greek")
        OcrLanguage.HEBREW -> specForScript("hebrew")
        OcrLanguage.HINDI -> specForScript("devanagari")
        OcrLanguage.THAI -> specForScript("thai")
        OcrLanguage.ENGLISH, OcrLanguage.SPANISH, OcrLanguage.FRENCH, OcrLanguage.GERMAN,
        OcrLanguage.PORTUGUESE, OcrLanguage.CZECH, OcrLanguage.DANISH, OcrLanguage.ESTONIAN,
        OcrLanguage.FINNISH, OcrLanguage.HUNGARIAN, OcrLanguage.INDONESIAN, OcrLanguage.ITALIAN,
        OcrLanguage.LATIN, OcrLanguage.LATVIAN, OcrLanguage.MALTESE, OcrLanguage.DUTCH,
        OcrLanguage.NORWEGIAN, OcrLanguage.POLISH, OcrLanguage.ROMANIAN, OcrLanguage.SWEDISH,
        OcrLanguage.TAGALOG, OcrLanguage.TURKISH, OcrLanguage.VIETNAMESE, OcrLanguage.WELSH ->
            specForScript("latin")
        else -> null
    }

    private var env: OrtEnvironment? = null
    private var detector: OrtSession? = null
    private var classifier: OrtSession? = null
    private val recSessions = mutableMapOf<String, OrtSession>()
    private val vocabs = mutableMapOf<String, Map<Int, String>>()

    @Volatile private var initialized = false
    private val lock = Any()

    private fun baseDir() = File(context.filesDir, DIR)

    fun init(): Boolean = synchronized(lock) {
        if (initialized) return true
        try {
            val detFile = File(baseDir(), "detector/text_detector.onnx")
            if (!detFile.exists()) return false
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            detector = e.createSession(detFile.absolutePath, opts)
            opts.close()
            env = e
            initialized = true
            true
        } catch (t: Throwable) {
            runCatching { detector?.close() }; detector = null
            false
        }
    }

    fun isInitialized(): Boolean = initialized

    fun destroy() = synchronized(lock) {
        runCatching { detector?.close() }; detector = null
        runCatching { classifier?.close() }; classifier = null
        recSessions.values.forEach { runCatching { it.close() } }; recSessions.clear()
        vocabs.clear()
        initialized = false
    }

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        if (!initialized) return emptyList()

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val origW = bmp.width
        val origH = bmp.height
        if (origW == 0 || origH == 0) return emptyList()

        val scale = min(MAX_SIDE.toFloat() / max(origW, origH), 1.0f)
        val targetW = max(32, ((origW * scale) / 32f).roundToInt() * 32)
        val targetH = max(32, ((origH * scale) / 32f).roundToInt() * 32)
        val sx = targetW.toFloat() / origW
        val sy = targetH.toFloat() / origH

        val det = runDetector(bmp, targetW, targetH) ?: return emptyList()

        var sumH = 0; var sumV = 0
        for (r in det.scoresH.indices) for (c in det.scoresH[r].indices)
            if (det.scoresH[r][c] > 0.5f) sumH++
        for (r in det.scoresV.indices) for (c in det.scoresV[r].indices)
            if (det.scoresV[r][c] > 0.5f) sumV++
        val isVertical = sumV > sumH

        val lines = if (isVertical) {
            OneOcrAlgorithms.segmentLines(det.scoresV, det.linksV, sx, sy,
                SCORE_THRESHOLD, LINK_THRESHOLD).sortedByDescending { it.x }
        } else {
            OneOcrAlgorithms.segmentLines(det.scoresH, det.linksH, sx, sy,
                SCORE_THRESHOLD, LINK_THRESHOLD).sortedBy { it.y }
        }

        val fallbackSpec = specForLanguage(language)

        val out = ArrayList<EngineLine>(lines.size)
        for (line in lines) {
            val pad = 2
            val x1 = max(0, line.x.toInt() - pad)
            val y1 = max(0, line.y.toInt() - pad)
            val x2 = min(origW, (line.x + line.w).toInt() + pad)
            val y2 = min(origH, (line.y + line.h).toInt() + pad)
            if (x2 <= x1 || y2 <= y1) continue

            var crop = Bitmap.createBitmap(bmp, x1, y1, x2 - x1, y2 - y1)
            if (isVertical) {
                // Match PIL Image.rotate(90) which is counter-clockwise.
                // Android Matrix.postRotate positive is clockwise, so use -90.
                val m = Matrix().apply { postRotate(-90f) }
                crop = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)
            }

            // Per-crop classification: pick recognizer from the crop's script.
            // Falls back to the profile language if classifier is missing or
            // says an unsupported script (bengali).
            val classified = classifyCrop(crop)
            val spec = classified?.let { specForScript(it.name) } ?: fallbackSpec ?: continue

            val rec = ensureRecSession(spec.rec) ?: continue
            val vocab = ensureVocab(spec.vocab) ?: continue

            val text = runRecognizer(rec, crop, vocab, spec.vocabSize)
            if (text.isBlank()) continue

            out.add(EngineLine(
                text = text,
                bbox = NormalizedBBox(
                    left = (x1.toDouble() / origW).coerceIn(0.0, 1.0),
                    top = (y1.toDouble() / origH).coerceIn(0.0, 1.0),
                    right = (x2.toDouble() / origW).coerceIn(0.0, 1.0),
                    bottom = (y2.toDouble() / origH).coerceIn(0.0, 1.0),
                ),
                writingDirection = if (isVertical) WritingDirection.TTB else WritingDirection.LTR,
                language = language,
            ))
        }
        return out
    }

    // ------------------------------------------------------------------------
    // Detector
    // ------------------------------------------------------------------------

    private data class DetOut(
        val scoresH: Array<FloatArray>, val scoresV: Array<FloatArray>,
        val linksH: Array<Array<FloatArray>>, val linksV: Array<Array<FloatArray>>,
    )

    @Suppress("UNCHECKED_CAST")
    private fun runDetector(bitmap: Bitmap, targetW: Int, targetH: Int): DetOut? {
        val det = detector ?: return null
        val e = env ?: return null
        val resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        val plane = targetW * targetH
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            // Detector: RGB, raw [0..255], no /255.
            chw[i]             = ((p ushr 16) and 0xFF).toFloat()
            chw[plane + i]     = ((p ushr 8)  and 0xFF).toFloat()
            chw[2 * plane + i] = (p           and 0xFF).toFloat()
        }
        val imInfo = floatArrayOf(targetH.toFloat(), targetW.toFloat(), 1.0f)

        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(chw),
                longArrayOf(1, 3, targetH.toLong(), targetW.toLong())).use { dataT ->
                OnnxTensor.createTensor(e, FloatBuffer.wrap(imInfo),
                    longArrayOf(1, 3)).use { infoT ->
                    det.run(mapOf("data" to dataT, "im_info" to infoT)).use { r ->
                        val sh = ((r.get(0) as OnnxTensor).value as Array<Array<Array<FloatArray>>>)[0][0]
                        val sv = ((r.get(2) as OnnxTensor).value as Array<Array<Array<FloatArray>>>)[0][0]
                        val lh = ((r.get(4) as OnnxTensor).value as Array<Array<Array<FloatArray>>>)[0]
                        val lv = ((r.get(5) as OnnxTensor).value as Array<Array<Array<FloatArray>>>)[0]
                        DetOut(sh, sv, lh, lv)
                    }
                }
            }
        } catch (t: Throwable) { null }
    }

    // ------------------------------------------------------------------------
    // Classifier
    // ------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun classifyCrop(crop: Bitmap): ScriptInfo? {
        val clf = ensureClassifierSession() ?: return null
        val e = env ?: return null
        val resized = crop.resizeBicubic(200, 60)
        val pixels = IntArray(200 * 60)
        resized.getPixels(pixels, 0, 200, 0, 0, 200, 60)
        val plane = 200 * 60
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            // Classifier: BGR, [0..1].
            chw[i]             = (p           and 0xFF) / 255f
            chw[plane + i]     = ((p ushr 8)  and 0xFF) / 255f
            chw[2 * plane + i] = ((p ushr 16) and 0xFF) / 255f
        }
        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(chw),
                longArrayOf(1, 3, 60, 200)).use { dataT ->
                clf.run(mapOf("data" to dataT)).use { r ->
                    // output[3] is script_id_score [1, 1, 10].
                    val scores = (r.get(3) as OnnxTensor).value as Array<Array<FloatArray>>
                    val row = scores[0][0]
                    var best = 0
                    var bestV = row[0]
                    for (k in 1 until row.size) if (row[k] > bestV) { bestV = row[k]; best = k }
                    // Python: SCRIPT_METADATA.get(id + 1, SCRIPT_METADATA[3])
                    SCRIPT_METADATA[best + 1] ?: SCRIPT_METADATA[3]
                }
            }
        } catch (t: Throwable) { null }
    }

    private fun ensureClassifierSession(): OrtSession? {
        classifier?.let { return it }
        synchronized(lock) {
            classifier?.let { return it }
            val f = File(baseDir(), "classifier/script_classifier.onnx")
            if (!f.exists()) return null
            val e = env ?: return null
            return try {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val s = e.createSession(f.absolutePath, opts)
                opts.close()
                classifier = s
                s
            } catch (t: Throwable) { null }
        }
    }

    // ------------------------------------------------------------------------
    // Recognizer
    // ------------------------------------------------------------------------

    private fun ensureRecSession(relPath: String): OrtSession? {
        recSessions[relPath]?.let { return it }
        synchronized(lock) {
            recSessions[relPath]?.let { return it }
            val f = File(baseDir(), relPath)
            if (!f.exists()) return null
            val e = env ?: return null
            return try {
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                val s = e.createSession(f.absolutePath, opts)
                opts.close()
                recSessions[relPath] = s
                s
            } catch (t: Throwable) { null }
        }
    }

    private fun ensureVocab(relPath: String): Map<Int, String>? {
        vocabs[relPath]?.let { return it }
        synchronized(lock) {
            vocabs[relPath]?.let { return it }
            val f = File(baseDir(), relPath)
            if (!f.exists()) return null
            return try {
                val v = OneOcrAlgorithms.loadVocab(f)
                vocabs[relPath] = v
                v
            } catch (t: Throwable) { null }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runRecognizer(
        rec: OrtSession, crop: Bitmap, vocab: Map<Int, String>, vocabSize: Int,
    ): String {
        val e = env ?: return ""
        val aspect = crop.width.toFloat() / crop.height.coerceAtLeast(1)
        val newW = min(REC_WIDTH_SAFETY_CAP, max(16, (REC_HEIGHT * aspect).roundToInt()))
        val resized = crop.resizeBicubic(newW, REC_HEIGHT)
        val pixels = IntArray(newW * REC_HEIGHT)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, REC_HEIGHT)
        val plane = newW * REC_HEIGHT
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            // Recognizer: BGR, [0..1].
            chw[i]             = (p           and 0xFF) / 255f
            chw[plane + i]     = ((p ushr 8)  and 0xFF) / 255f
            chw[2 * plane + i] = ((p ushr 16) and 0xFF) / 255f
        }
        val seqLen = intArrayOf(newW / 4)

        return try {
            OnnxTensor.createTensor(e, FloatBuffer.wrap(chw),
                longArrayOf(1, 3, REC_HEIGHT.toLong(), newW.toLong())).use { dataT ->
                OnnxTensor.createTensor(e, IntBuffer.wrap(seqLen),
                    longArrayOf(1)).use { lenT ->
                    rec.run(mapOf("data" to dataT, "seq_lengths" to lenT)).use { r ->
                        // Recognizer output shape differs by ONNX export:
                        //   [T, 1, C]  (timesteps-first)  OR
                        //   [1, T, C]  (batch-first)  <- bropines uses this
                        // Detect at runtime and iterate the timestep axis.
                        val logits = (r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                        val batchFirst = logits.size == 1
                        val tDim = if (batchFirst) logits[0].size else logits.size
                        val blank = vocabSize - 1
                        val sb = StringBuilder()
                        var prev = -1
                        for (t in 0 until tDim) {
                            val row = if (batchFirst) logits[0][t] else logits[t][0]
                            var best = 0
                            var bestV = row[0]
                            for (k in 1 until row.size) if (row[k] > bestV) { bestV = row[k]; best = k }
                            if (best != prev && best != blank) {
                                val ch = vocab[best] ?: ""
                                when {
                                    best == 0 -> sb.append(' ')
                                    ch.isEmpty() -> if (vocabSize <= 1000) sb.append(' ')
                                    else -> sb.append(ch)
                                }
                            }
                            prev = best
                        }
                        val raw = sb.toString()
                        if (vocabSize > 1000) raw
                        else raw.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
                    }
                }
            }
        } catch (t: Throwable) { "" }
    }

    // ------------------------------------------------------------------------
    // Bicubic resize (matches PIL's BICUBIC + antialias-on-downscale)
    // ------------------------------------------------------------------------

    /**
     * Separable bicubic (Catmull-Rom kernel) with antialias widening on
     * downscale. Matches Pillow's BICUBIC default for crop resizes.
     *
     * Intentionally used only for small crops (recognizer + classifier), not
     * for the whole-page detector input which needs the faster bilinear path
     * in Bitmap.createScaledBitmap.
     */
    private fun Bitmap.resizeBicubic(dstW: Int, dstH: Int): Bitmap {
        if (dstW == width && dstH == height) return this
        val srcW = width
        val srcH = height
        val srcPixels = IntArray(srcW * srcH)
        getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val dstPixels = IntArray(dstW * dstH)

        val scaleX = srcW.toDouble() / dstW
        val scaleY = srcH.toDouble() / dstH
        val supportX = if (scaleX < 1.0) 2.0 else 2.0 * scaleX
        val supportY = if (scaleY < 1.0) 2.0 else 2.0 * scaleY

        fun cubic(t: Double): Double {
            val at = abs(t)
            return when {
                at <= 1.0 -> (1.5 * at - 2.5) * at * at + 1.0
                at < 2.0 -> ((-0.5 * at + 2.5) * at - 4.0) * at + 2.0
                else -> 0.0
            }
        }

        for (dy in 0 until dstH) {
            val cy = (dy + 0.5) * scaleY - 0.5
            val y0 = floor(cy - supportY).toInt()
            val y1 = ceil(cy + supportY).toInt()
            val yWeights = DoubleArray(max(0, y1 - y0 + 1))
            var ySum = 0.0
            for (y in y0..y1) {
                val w = cubic((y - cy) / (supportY / 2.0))
                yWeights[y - y0] = w
                ySum += w
            }
            if (ySum == 0.0) continue
            for (i in yWeights.indices) yWeights[i] /= ySum

            for (dx in 0 until dstW) {
                val cx = (dx + 0.5) * scaleX - 0.5
                val x0 = floor(cx - supportX).toInt()
                val x1 = ceil(cx + supportX).toInt()

                var rSum = 0.0; var gSum = 0.0; var bSum = 0.0; var wSum = 0.0
                for (y in y0..y1) {
                    if (y < 0 || y >= srcH) continue
                    val wy = yWeights[y - y0]
                    if (wy == 0.0) continue
                    for (x in x0..x1) {
                        if (x < 0 || x >= srcW) continue
                        val wx = cubic((x - cx) / (supportX / 2.0))
                        if (wx == 0.0) continue
                        val w = wx * wy
                        val p = srcPixels[y * srcW + x]
                        rSum += w * ((p ushr 16) and 0xFF)
                        gSum += w * ((p ushr 8) and 0xFF)
                        bSum += w * (p and 0xFF)
                        wSum += w
                    }
                }
                if (wSum == 0.0) continue
                val r = (rSum / wSum).roundToInt().coerceIn(0, 255)
                val g = (gSum / wSum).roundToInt().coerceIn(0, 255)
                val b = (bSum / wSum).roundToInt().coerceIn(0, 255)
                dstPixels[dy * dstW + dx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(dstPixels, dstW, dstH, Bitmap.Config.ARGB_8888)
    }
}
