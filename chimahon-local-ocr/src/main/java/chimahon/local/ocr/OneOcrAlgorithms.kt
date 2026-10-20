package chimahon.local.ocr

import java.io.File

data class LineBox(val x: Float, val y: Float, val w: Float, val h: Float)

/**
 * Pure-Kotlin helpers ported from pasitgut/one-ocr:
 *   detector.get_segmented_lines  -> segmentLines
 *   Vocabulary.load_clean         -> loadVocab
 * No Android or ONNX imports so it is JVM-testable.
 */
object OneOcrAlgorithms {

    private val NEIGHBORS = arrayOf(
        intArrayOf(-1, -1), intArrayOf(-1, 0), intArrayOf(-1, 1),
        intArrayOf(0, 1),   intArrayOf(1, 1),  intArrayOf(1, 0),
        intArrayOf(1, -1),  intArrayOf(0, -1),
    )

    fun segmentLines(
        scores: Array<FloatArray>,
        links: Array<Array<FloatArray>>,
        sx: Float,
        sy: Float,
        scoreThreshold: Float = 0.5f,
        linkThreshold: Float = 0f,
    ): List<LineBox> {
        val h = scores.size
        val w = if (h > 0) scores[0].size else 0
        if (h == 0 || w == 0) return emptyList()

        val n = h * w
        val parent = IntArray(n) { -1 }
        val active = BooleanArray(n)

        fun idx(r: Int, c: Int) = r * w + c
        fun find(i: Int): Int {
            var j = i
            while (parent[j] != j) j = parent[j]
            var k = i
            while (parent[k] != k) { val nx = parent[k]; parent[k] = j; k = nx }
            return j
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (r in 0 until h) for (c in 0 until w) {
            if (scores[r][c] > scoreThreshold) { val i = idx(r, c); active[i] = true; parent[i] = i }
        }
        for (r in 0 until h) for (c in 0 until w) {
            if (!active[idx(r, c)]) continue
            for (k in 0 until 8) {
                val nr = r + NEIGHBORS[k][0]
                val nc = c + NEIGHBORS[k][1]
                if (nr < 0 || nc < 0 || nr >= h || nc >= w) continue
                if (!active[idx(nr, nc)]) continue
                if (links[k][r][c] > linkThreshold) union(idx(r, c), idx(nr, nc))
            }
        }

        val groups = HashMap<Int, MutableList<IntArray>>()
        for (r in 0 until h) for (c in 0 until w) {
            val i = idx(r, c)
            if (!active[i]) continue
            groups.getOrPut(find(i)) { mutableListOf() }.add(intArrayOf(r, c))
        }

        val stride = 4f
        val out = mutableListOf<LineBox>()
        for ((_, pixels) in groups) {
            if (pixels.size < 5) continue
            var minR = Int.MAX_VALUE; var maxR = Int.MIN_VALUE
            var minC = Int.MAX_VALUE; var maxC = Int.MIN_VALUE
            for (px in pixels) {
                val r = px[0]; val c = px[1]
                if (r < minR) minR = r; if (r > maxR) maxR = r
                if (c < minC) minC = c; if (c > maxC) maxC = c
            }
            val x = minC * stride / sx
            val y = minR * stride / sy
            val bw = (maxC - minC + 1) * stride / sx
            val bh = (maxR - minR + 1) * stride / sy
            if (bw < 10f || bh < 10f) continue
            out.add(LineBox(x, y, bw, bh))
        }
        return out
    }

    fun loadVocab(path: File): Map<Int, String> {
        val raw = mutableListOf<Pair<Int, String>>()
        for (line in path.readLines()) {
            if (line.isBlank()) continue
            val ci = line.indexOf(':')
            if (ci < 0) continue
            val idx = line.substring(0, ci).trim().toIntOrNull() ?: continue
            var ch = line.substring(ci + 1)
            if (ch.startsWith(" ")) ch = ch.substring(1)
            raw.add(idx to ch)
        }
        raw.sortBy { it.first }
        if (raw.size > 1000) return raw.toMap()

        val skip = setOf(0, 13, 16, 28, 33)
        val out = mutableMapOf<Int, String>()
        var cleanIdx = 1
        for ((idx, ch) in raw) {
            if (idx in skip) continue
            out[cleanIdx] = ch
            cleanIdx++
        }
        return out
    }
}
