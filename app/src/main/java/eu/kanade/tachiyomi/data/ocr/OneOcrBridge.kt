package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import chimahon.ocr.EngineLine
import chimahon.ocr.OcrEngine
import chimahon.ocr.OcrLanguage
import eu.kanade.tachiyomi.BuildConfig
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class OneOcrBridge(private val context: Context) {

    private var engine: OcrEngine? = null

    val isAvailable: Boolean get() = BuildConfig.HAS_LOCAL_OCR
    var isInitialized: Boolean = false
        private set

    fun init(): Boolean {
        if (!isAvailable) return false
        if (isInitialized) return true
        try {
            val clazz = Class.forName("chimahon.local.ocr.OneOcrEngine")
            val ctor = clazz.getConstructor(Context::class.java)
            val instance = ctor.newInstance(context) as OcrEngine
            clazz.getMethod("init").invoke(instance)
            val initialized = clazz.getMethod("isInitialized").invoke(instance) as Boolean
            if (initialized) {
                engine = instance
                isInitialized = true
                logcat { "OneOcrBridge: OneOcrEngine initialized successfully" }
            } else {
                logcat(LogPriority.WARN) { "OneOcrBridge: OneOcrEngine.init() returned not initialized" }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "OneOcrBridge: failed to init OneOcrEngine" }
        }
        return isInitialized
    }

    suspend fun recognize(bytes: ByteArray, language: OcrLanguage): List<EngineLine> {
        if (!isInitialized) return emptyList()
        return engine?.recognize(bytes, language) ?: emptyList()
    }

    fun destroy() {
        if (engine != null) {
            try {
                engine!!.javaClass.getMethod("destroy").invoke(engine)
            } catch (_: Exception) {}
            engine = null
            isInitialized = false
        }
    }
}
