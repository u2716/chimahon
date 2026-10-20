package chimahon.local.ocr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class OneOcrEngineTest {

    @Test
    fun `segmentLines drops groups smaller than minPixels`() {
        val scores = Array(3) { Array(3) { 0f } }
        scores[1][1] = 1f; scores[1][2] = 1f; scores[2][1] = 1f
        val links = Array(8) { Array(3) { Array(3) { 1f } } }
        assertEquals(0, OneOcrAlgorithms.segmentLines(scores, links, 1f, 1f).size)
    }

    @Test
    fun `segmentLines yields one line for solid block`() {
        val size = 5
        val scores = Array(size) { Array(size) { 0f } }
        for (r in 1..3) for (c in 1..3) scores[r][c] = 1f
        val links = Array(8) { Array(size) { Array(size) { 1f } } }
        val lines = OneOcrAlgorithms.segmentLines(scores, links, 1f, 1f)
        assertEquals(1, lines.size)
        assertTrue(lines[0].w > 0f && lines[0].h > 0f)
    }

    @Test
    fun `loadVocab remaps small scripts`(@TempDir tempDir: File) {
        val file = File(tempDir, "vocab.txt")
        file.writeText("1: A\n2: B\n3: C\n")
        val map = OneOcrAlgorithms.loadVocab(file)
        assertEquals(3, map.size)
        assertEquals("A", map[1])
        assertEquals("C", map[3])
    }

    @Test
    fun `loadVocab preserves raw indices for large scripts`(@TempDir tempDir: File) {
        val file = File(tempDir, "vocab.txt")
        val lines = (1..1500).joinToString("\n") { "$it: x" }
        file.writeText(lines)
        val map = OneOcrAlgorithms.loadVocab(file)
        assertEquals(1500, map.size)
        assertEquals("x", map[1])
        assertEquals("x", map[1500])
        assertNotNull(map[1000])
    }
}
