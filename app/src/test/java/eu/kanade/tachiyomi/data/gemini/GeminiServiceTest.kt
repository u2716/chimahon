package eu.kanade.tachiyomi.data.gemini

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class GeminiServiceTest {

    private val service = GeminiService(
        client = okhttp3.OkHttpClient(),
        preferences = io.mockk.mockk(relaxed = true),
    )

    @Test
    fun buildUrl_standardModel_formatsCorrectly() {
        val url = service.buildUrl(GeminiConfig.MODEL_FLASH_LATEST, "")
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:streamGenerateContent",
            url,
        )
    }

    @Test
    fun buildUrl_customHttpsEndpoint_formatsCorrectly() {
        val url = service.buildUrl(GeminiConfig.MODEL_CUSTOM, "https://api.custom.com/v1/models/gemini")
        assertEquals("https://api.custom.com/v1/models/gemini:streamGenerateContent", url)
    }

    @Test
    fun buildUrl_customWithExistingActionSuffix_stripsAndAppendsStreamSuffix() {
        val url = service.buildUrl(
            GeminiConfig.MODEL_CUSTOM,
            "https://api.custom.com/v1/models/gemini:generateContent",
        )
        assertEquals("https://api.custom.com/v1/models/gemini:streamGenerateContent", url)
    }

    @Test
    fun buildUrl_customWithQueryParams_preservesParams() {
        val url = service.buildUrl(
            GeminiConfig.MODEL_CUSTOM,
            "https://api.custom.com/v1/models/gemini?auth=token",
        )
        assertEquals("https://api.custom.com/v1/models/gemini:streamGenerateContent?auth=token", url)
    }

    @Test
    fun buildUrl_customBlankEndpoint_throwsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.buildUrl(GeminiConfig.MODEL_CUSTOM, "")
        }
        assertEquals("CUSTOM_ENDPOINT_BLANK", exception.message)
    }

    @Test
    fun buildUrl_customNonHttpsEndpoint_throwsException() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            service.buildUrl(GeminiConfig.MODEL_CUSTOM, "http://api.custom.com")
        }
        assertEquals("CUSTOM_ENDPOINT_INVALID", exception.message)
    }

    @Test
    fun parseResponseStream_successfulJsonChunks_emitsDelta() = runBlocking {
        val jsonChunk = """
            [
              {"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]},
              {"candidates":[{"content":{"parts":[{"text":"World!"}]}}]}
            ]
        """.trimIndent()

        val deltas = mutableListOf<String>()
        service.parseResponseStream(ByteArrayInputStream(jsonChunk.toByteArray())) { delta ->
            deltas.add(delta)
        }

        assertEquals(listOf("Hello ", "World!"), deltas)
    }

    @Test
    fun parseErrorMessage_extractsMessage() {
        val errorJson = """{"error":{"code":400,"message":"Invalid API key provided","status":"INVALID_ARGUMENT"}}"""
        val message = service.parseErrorMessage(errorJson)
        assertEquals("Invalid API key provided", message)
    }

    @Test
    fun parseResponseStream_emptyOrMissingCandidates_handlesGracefully() = runBlocking {
        val emptyJson = """[{"promptFeedback":{"blockReason":"SAFETY"}}]"""
        val deltas = mutableListOf<String>()
        service.parseResponseStream(ByteArrayInputStream(emptyJson.toByteArray())) { delta ->
            deltas.add(delta)
        }
        assertEquals(emptyList<String>(), deltas)
    }
}
