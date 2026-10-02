package eu.kanade.tachiyomi.data.gemini

object GeminiConfig {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    const val STREAM_ENDPOINT_SUFFIX = ":streamGenerateContent"
    const val HEADER_API_KEY = "x-goog-api-key"

    const val MODEL_FLASH_LITE_LATEST = "gemini-flash-lite-latest"
    const val MODEL_FLASH_LATEST = "gemini-flash-latest"
    const val MODEL_GEMMA_4_26B = "gemma-4-26b-a4b-it"
    const val MODEL_GEMMA_4_31B = "gemma-4-31b-it"
    const val MODEL_CUSTOM = "custom"

    const val MAX_OCR_TEXT_CHARS = 12000

    val DEFAULT_PROMPT = """
        You are an expert Japanese linguist.
        Translate the following Japanese text to English.
        Then, provide a detailed breakdown of the grammar, identifying particles, verb conjugations, and any idiomatic expressions.
        Format your response clearly with a 'Source Text' section, a 'Translation' section and a 'Grammar Explanation' section.
    """.trimIndent()
}
