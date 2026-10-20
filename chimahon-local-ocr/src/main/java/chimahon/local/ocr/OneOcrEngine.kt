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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OneOCR engine: Microsoft Windows 11 Snipping Tool OCR, extracted to portable
 * ONNX by pasitgut/one-ocr and bropines/oneocr-onnx-python.
 *
 * Pipeline (mirrors Python engine.py):
 *   detector -> (optional classifier) -> recognizer -> CTC decode
 *
 * Detector:  RGB, [0..255] range, no normalization, dynamic [1,3,H,W] with
 *            H,W rounded to a multiple of 32.  Aux tensor im_info = [H, W, 1.0].
 * Recognizer: BGR, [0..1] range (channel order differs from detector).
 *             Height 60, dynamic width; seq_lengths = [W/4].
 * Vocabulary: "<idx>: <char>" format; blank class is the last index.
 *             Small vocabs are remapped to 1..N skipping [0, 13, 16, 28, 33]
 *             to match the reference Python implementation.
 *
 * The classifier model (script_classifier.onnx) is not used: the profile
 * language selects the recognizer, so per-crop script detection is not needed.
 */
class OneOcrEngine(private val context: Context) : OcrEngine {

    override val name: String = "OneOCR"

    companion object {
        const val DIR = "one_ocr"
        const val MAX_SIDE = 1536
        const val REC_HEIGHT = 60
        const val SCORE_THRESHOLD = 0.5f
        const val LINK_THRESHOLD = 0f
        const val REC_MAX_WIDTH = 2048

        val requiredFiles: List<String> = listOf(
            "detector/text_detector.onnx",
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

    private fun specFor(language: OcrLanguage): Spec? = when (language) {
        OcrLanguage.JAPANESE, OcrLanguage.CHINESE, OcrLanguage.KOREAN, OcrLanguage.CANTONESE ->
            Spec("recognizers/recognizer_cjk.onnx", "vocab/vocab_cjk.txt", 32632)
        OcrLanguage.ARABIC, OcrLanguage.PERSIAN ->
            Spec("recognizers/recognizer_arabic.onnx", "vocab/vocab_arabic.txt", 221)
        OcrLanguage.BULGARIAN, OcrLanguage.RUSSIAN, OcrLanguage.UKRAINIAN, OcrLanguage.MONGOLIAN ->
            Spec("recognizers/recognizer_cyrillic.onnx", "vocab/vocab_cyrillic.txt", 548)
        OcrLanguage.GREEK ->
            Spec("recognizers/recognizer_greek.onnx", "vocab/vocab_greek.txt", 179)
        OcrLanguage.HEBREW ->
            Spec("recognizers/recognizer_hebrew.onnx", "vocab/vocab_hebrew.txt", 244)
        OcrLanguage.HINDI ->
            Spec("recognizers/recognizer_devanagari.onnx", "vocab/vocab_devanagari.txt", 237)
        OcrLanguage.THAI ->
            Spec("recognizers/recognizer_thai.onnx", "vocab/vocab_thai.txt", 199)
        OcrLanguage.ENGLISH, OcrLanguage.SPANISH, OcrLanguage.FRENCH, OcrLanguage.GERMAN,
        OcrLanguage.PORTUGUESE, OcrLanguage.CZECH, OcrLanguage.DANISH, OcrLanguage.ESTONIAN,
        OcrLanguage.FINNISH, OcrLanguage.HUNGARIAN, OcrLanguage.INDONESIAN, OcrLanguage.ITALIAN,
        OcrLanguage.LATIN, OcrLanguage.LATVIAN, OcrLanguage.MALTESE, OcrLanguage.DUTCH,
        OcrLanguage.NORWEGIAN, OcrLanguage.POLISH, OcrLanguage.ROMANIAN, OcrLanguage.SWEDISH,
        OcrLanguage.TAGALOG, OcrLanguage.TURKISH, OcrLanguage.VIETNAMESE, OcrLanguage.WELSH ->
            Spec("recognizers/recognizer_latin.onnx", "vocab/vocab_latin.txt", 415)
        else -> null
    }

    private var env: OrtEnvironment? = null
    private var detector: OrtSession? = null
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
        recSessions.values.forEach { runCatching { it.close() } }; recSessions.clear()
        vocabs.clear()
        initialized = false
    }

    override suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        if (!initialized) return emptyList()
        val spec = specFor(language) ?: return emptyList()
        val rec = ensureRecSession(spec.rec) ?: return emptyList()
        val vocab = ensureVocab(spec.vocab) ?: return emptyList()

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val origW = bmp.width; val origH = bmp.height
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
                val m = Matrix().apply { postRotate(90f) }
                crop = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, m, true)
            }

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
                    // featuremap_cond intentionally omitted (has a default
                    // initializer; the Python engine does the same).
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
        val newW = min(REC_MAX_WIDTH, max(16, (REC_HEIGHT * aspect).roundToInt()))
        val resized = Bitmap.createScaledBitmap(crop, newW, REC_HEIGHT, true)
        val pixels = IntArray(newW * REC_HEIGHT)
        resized.getPixels(pixels, 0, newW, 0, 0, newW, REC_HEIGHT)
        val plane = newW * REC_HEIGHT
        val chw = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            // Recognizer: BGR, [0..1].  Channel order differs from detector.
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
                        // Model output shape is [T, 1, C] (timesteps-first).
                        // Iterate every timestep; logits[t][0] is the C-length
                        // score row for that step.
                        val logits = (r.get(0) as OnnxTensor).value as Array<Array<FloatArray>>
                        val blank = vocabSize - 1
                        val sb = StringBuilder()
                        var prev = -1
                        for (t in logits.indices) {
                            val row = logits[t][0]
                            var best = 0
                            var bestV = row[0]
                            for (k in 1 until row.size) if (row[k] > bestV) { bestV = row[k]; best = k }
                            if (best != prev && best != blank) {
                                if (best == 0) sb.append(' ')
                                else sb.append(vocab[best] ?: "")
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
}
