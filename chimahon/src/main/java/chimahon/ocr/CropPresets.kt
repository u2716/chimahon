package chimahon.ocr

object CropPresets {

    data class AspectRatioPreset(
        val key: String,
        val label: String,
        val x: Int,
        val y: Int,
    )

    /**
     * Aspect ratio presets for the Anki screenshot. All entries are landscape-oriented
     * (`x >= y`); a portrait screenshot is treated as the same shape rotated 90°, so a
     * `16:9` preset on a 1080x2400 screenshot produces a 9:16 (1080x1920) crop.
     *
     * Only shapes whose reduced form is distinct are listed — `9:16`, `3:4` and `2:3`
     * are the same shapes as their landscape twins and would duplicate behavior under
     * a different label.
     */
    val ASPECT_RATIO_PRESETS = listOf(
        AspectRatioPreset("1:1", "1:1", 1, 1),
        AspectRatioPreset("4:3", "4:3", 4, 3),
        AspectRatioPreset("3:2", "3:2", 3, 2),
        AspectRatioPreset("16:9", "16:9", 16, 9),
    )

    private val byKey = ASPECT_RATIO_PRESETS.associateBy { it.key }

    fun aspectByKey(key: String): AspectRatioPreset? = byKey[key]
}
