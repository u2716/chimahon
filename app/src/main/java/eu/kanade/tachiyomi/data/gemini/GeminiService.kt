package eu.kanade.tachiyomi.data.gemini

import android.graphics.Bitmap
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.dictionary.DictionaryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader

sealed interface GeminiError {
    object NoApiKey : GeminiError
    object NoInput : GeminiError
    object CustomEndpointBlank : GeminiError
    object CustomEndpointInvalid : GeminiError
    data class Http(val code: Int, val message: String) : GeminiError
    data class Generic(val message: String) : GeminiError
}

class HttpAnalysisException(val code: Int, override val message: String) : Exception("Error: $code $message")

class GeminiService(
    private val client: OkHttpClient = Injekt.get<NetworkHelper>().client,
    private val preferences: DictionaryPreferences = Injekt.get(),
) {
    fun buildUrl(model: String, customEndpoint: String): String {
        return if (model == GeminiConfig.MODEL_CUSTOM) {
            val raw = customEndpoint.trim()
            require(raw.isNotBlank()) { "CUSTOM_ENDPOINT_BLANK" }
            val httpUrl = raw.toHttpUrlOrNull()
            require(httpUrl != null && httpUrl.isHttps) { "CUSTOM_ENDPOINT_INVALID" }

            val pathSegments = httpUrl.pathSegments.toMutableList()
            if (pathSegments.isNotEmpty()) {
                val lastIndex = pathSegments.size - 1
                val lastSegment = pathSegments[lastIndex]
                val cleanedLastSegment = lastSegment
                    .removeSuffix(GeminiConfig.STREAM_ENDPOINT_SUFFIX)
                    .removeSuffix(":generateContent")
                pathSegments[lastIndex] = cleanedLastSegment + GeminiConfig.STREAM_ENDPOINT_SUFFIX
            }

            httpUrl.newBuilder()
                .apply {
                    encodedPath("/")
                    pathSegments.forEach { addPathSegment(it) }
                }
                .build()
                .toString()
        } else {
            "${GeminiConfig.BASE_URL}$model${GeminiConfig.STREAM_ENDPOINT_SUFFIX}"
        }
    }

    fun streamAnalysis(
        text: String?,
        screenshot: Bitmap?,
        forceText: Boolean = false,
    ): Flow<String> = flow {
        val apiKey = preferences.geminiApiKey().get().trim()
        if (apiKey.isBlank()) {
            throw IllegalStateException("API_KEY_BLANK")
        }

        val model = preferences.geminiModel().get()
        val customEndpoint = preferences.geminiCustomEndpoint().get()
        val sendScreenshot = preferences.geminiSendScreenshot().get()
        val prompt = preferences.geminiPrompt().get().ifBlank { GeminiConfig.DEFAULT_PROMPT }
        val url = buildUrl(model, customEndpoint)

        val cleanedText = text?.trim().orEmpty()
        val truncatedText = if (cleanedText.length > GeminiConfig.MAX_OCR_TEXT_CHARS) {
            cleanedText.take(GeminiConfig.MAX_OCR_TEXT_CHARS) + "\n...[truncated]"
        } else {
            cleanedText
        }

        val useScreenshot = !forceText && sendScreenshot
        if (!useScreenshot && truncatedText.isBlank()) {
            throw IllegalArgumentException("NO_INPUT")
        }

        val partsArray = JSONArray()
        if (useScreenshot && screenshot != null) {
            partsArray.put(JSONObject().apply { put("text", prompt) })
            val stream = ByteArrayOutputStream()
            screenshot.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val base64Data = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            partsArray.put(JSONObject().apply {
                put("inline_data", JSONObject().apply {
                    put("mime_type", "image/jpeg")
                    put("data", base64Data)
                })
            })
        } else {
            partsArray.put(JSONObject().apply { put("text", "$prompt\n\n$truncatedText") })
        }

        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", partsArray)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .addHeader(GeminiConfig.HEADER_API_KEY, apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.newCall(request)
        val coroutineJob = currentCoroutineContext()[Job]
        val cancellationHandle = coroutineJob?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    val errorMessage = parseErrorMessage(errorBody).ifBlank { response.message }
                    throw HttpAnalysisException(response.code, errorMessage)
                }

                val responseStream = response.body?.byteStream() ?: return@use
                // Gemini's streamGenerateContent API returns incremental delta chunks for
                // each candidate part in the response stream, so accumulating chunks via
                // `content += delta` in the caller is the expected and correct behavior.
                parseResponseStream(responseStream) { delta ->
                    emit(delta)
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }.flowOn(Dispatchers.IO)

    suspend fun parseResponseStream(inputStream: InputStream, onDelta: suspend (String) -> Unit) {
        val reader = JsonReader(InputStreamReader(inputStream, "UTF-8")).apply { isLenient = true }
        if (reader.peek() == JsonToken.BEGIN_ARRAY) {
            reader.beginArray()
            while (reader.hasNext()) {
                parseCandidateChunk(reader, onDelta)
            }
            reader.endArray()
        } else if (reader.peek() == JsonToken.BEGIN_OBJECT) {
            parseCandidateChunk(reader, onDelta)
        }
    }

    suspend fun parseCandidateChunk(reader: JsonReader, onDelta: suspend (String) -> Unit) {
        reader.beginObject()
        while (reader.hasNext()) {
            if (reader.nextName() == "candidates" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "content" && reader.peek() == JsonToken.BEGIN_OBJECT) {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                if (reader.nextName() == "parts" && reader.peek() == JsonToken.BEGIN_ARRAY) {
                                    reader.beginArray()
                                    while (reader.hasNext()) {
                                        reader.beginObject()
                                        while (reader.hasNext()) {
                                            if (reader.nextName() == "text" && reader.peek() == JsonToken.STRING) {
                                                onDelta(reader.nextString())
                                            } else {
                                                reader.skipValue()
                                            }
                                        }
                                        reader.endObject()
                                    }
                                    reader.endArray()
                                } else {
                                    reader.skipValue()
                                }
                            }
                            reader.endObject()
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                reader.endArray()
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
    }

    fun parseErrorMessage(json: String): String {
        return try {
            val obj = JSONObject(json)
            if (obj.has("error")) {
                obj.getJSONObject("error").optString("message", "")
            } else {
                ""
            }
        } catch (e: Exception) {
            logcat { "Failed to parse error body: ${e.message}" }
            ""
        }
    }
}
