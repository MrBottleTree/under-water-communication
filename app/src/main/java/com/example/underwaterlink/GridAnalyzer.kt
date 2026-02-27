package com.example.underwaterlink

import java.nio.ByteBuffer

data class GridResult(
    val cols: Int,
    val rows: Int,
    val brightness: FloatArray,          // [numGrids], 0.0–1.0 normalised
    val confidence: FloatArray,          // [numGrids], 0.0–1.0 (normalised Otsu variance)
    val otsuThreshold: IntArray,         // [numGrids], 0–255 raw bin index
    val topGrids: List<Int>,             // indices of selected top-k grids
    val topGridHistograms: List<FloatArray>, // copy of histogram for each topGrid (up to 3)
    val compositeBrightness: Float,      // confidence-weighted mean brightness, 0.0–1.0
    val bitVote: Float,                  // −1.0 = dark, +1.0 = bright, ≈0 = uncertain
    val activeGridCount: Int,            // grids with confidence >= threshold
    val largestClusterSize: Int          // grids in biggest connected component
)

/**
 * Per-frame grid analysis for the optical RX channel.
 *
 * Every frame the camera delivers is divided into [gridSize]×[gridSize] blocks.
 * For each block we maintain a decaying histogram of brightness values (0–255).
 * Otsu's method on that histogram gives:
 *   • a threshold separating "dark" from "bright"
 *   • an inter-class variance that serves as our confidence score
 *     (high = bimodal = this grid is seeing the flashing LED)
 *
 * The top-k highest-confidence grids that form a spatially connected cluster
 * are used for a confidence-weighted bit vote each frame.
 */
class GridAnalyzer(
    val imageWidth: Int = 640,
    val imageHeight: Int = 480,
    val gridSize: Int = 8,              // smaller = finer grid = better at distance
    var alpha: Float = 0.05f,           // histogram decay: larger = faster adaptation
    var confidenceThreshold: Float = 0.15f,
    var topK: Int = 8,
    var minClusterSize: Int = 2
) {
    val cols = imageWidth / gridSize    // e.g. 40 for 640px
    val rows = imageHeight / gridSize   // e.g. 30 for 480px
    val numGrids = cols * rows

    // ── Histogram state ───────────────────────────────────────────────────────

    // Probability-mass histograms (values sum ≈ 1). Used by both modes.
    private val histograms    = Array(numGrids) { FloatArray(256) }
    private val brightness    = FloatArray(numGrids)
    private val confidence    = FloatArray(numGrids)
    private val otsuThreshold = IntArray(numGrids)

    // Maximum possible Otsu inter-class variance:
    //   equal halves at extremes 0 and 255 → 0.5 * 0.5 * 255² = 16 256.25
    private val MAX_OTSU_VAR = 16256.25f

    // ── Rolling-window mode ────────────────────────────────────────────────────
    //
    // When useRollingWindow = true the histogram is computed from a circular
    // buffer of the last windowSize brightness bins (uniform weight, no decay).
    // Switching mode or changing windowSize resets the window buffers.

    var useRollingWindow: Boolean = false
        set(value) { field = value; if (value) ensureWindow() else clearWindow() }

    var windowSize: Int = 30
        set(value) {
            field = value.coerceIn(1, 1000)
            if (useRollingWindow) allocateWindow()   // re-alloc with new size
        }

    // Allocated lazily when useRollingWindow is enabled
    private var winBins:    Array<ByteArray>? = null  // [numGrids][windowSize] — bin index 0–255
    private var winCounts:  Array<IntArray>?  = null  // [numGrids][256]        — frequency counts
    private var winHeads:   IntArray?         = null  // circular-buffer head per grid
    private var winFilled:  IntArray?         = null  // frames in buffer (ramps up to windowSize)

    private fun ensureWindow() {
        val b = winBins
        if (b == null || b.size != numGrids || b[0].size != windowSize) allocateWindow()
    }

    private fun allocateWindow() {
        winBins   = Array(numGrids) { ByteArray(windowSize) }
        winCounts = Array(numGrids) { IntArray(256) }
        winHeads  = IntArray(numGrids)
        winFilled = IntArray(numGrids)
        for (h in histograms) h.fill(0f)  // also clear the probability histograms
    }

    private fun clearWindow() {
        winBins   = null
        winCounts = null
        winHeads  = null
        winFilled = null
    }

    private val neighbors = arrayOf(
        intArrayOf(-1, 0), intArrayOf(1, 0),
        intArrayOf(0, -1), intArrayOf(0, 1)
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun processFrame(buffer: ByteBuffer, rowStride: Int, cropX: Int = 0, cropY: Int = 0): GridResult {
        extractBrightness(buffer, rowStride, cropX, cropY)
        updateHistograms()
        computeAllOtsu()

        val clusters = findClusters()
        val largest = clusters.maxByOrNull { it.size } ?: emptyList()
        val topGrids = pickTopGrids(largest)
        // Always produce exactly 4 histogram snapshots (pad with zeroed arrays)
        val topHistograms = (0 until 4).map { i ->
            if (i < topGrids.size) histograms[topGrids[i]].copyOf() else FloatArray(256)
        }

        return GridResult(
            cols = cols,
            rows = rows,
            brightness = brightness.copyOf(),
            confidence = confidence.copyOf(),
            otsuThreshold = otsuThreshold.copyOf(),
            topGrids = topGrids,
            topGridHistograms = topHistograms,
            compositeBrightness = computeComposite(topGrids),
            bitVote = computeBitVote(topGrids),
            activeGridCount = confidence.count { it >= confidenceThreshold },
            largestClusterSize = largest.size
        )
    }

    fun reset() {
        for (h in histograms) h.fill(0f)
        brightness.fill(0f)
        confidence.fill(0f)
        otsuThreshold.fill(0)
        winCounts?.forEach { it.fill(0) }
        winBins?.forEach   { it.fill(0) }
        winHeads?.fill(0)
        winFilled?.fill(0)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Average Y-plane luma for each grid cell. cropX/cropY offset into a larger buffer for digital zoom. */
    private fun extractBrightness(buffer: ByteBuffer, rowStride: Int, cropX: Int, cropY: Int) {
        val scale = 1f / (gridSize * gridSize * 255f)
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var sum = 0
                for (py in 0 until gridSize) {
                    val lineStart = (cropY + row * gridSize + py) * rowStride + (cropX + col * gridSize)
                    for (px in 0 until gridSize) {
                        sum += buffer.get(lineStart + px).toInt() and 0xFF
                    }
                }
                brightness[row * cols + col] = sum * scale
            }
        }
    }

    /** Dispatch to the active histogram update mode. */
    private fun updateHistograms() {
        if (useRollingWindow) updateHistogramsWindow() else updateHistogramsAlpha()
    }

    /** Alpha-decay mode: hist[bin] = hist[bin]*(1−α) + α (exponential moving average). */
    private fun updateHistogramsAlpha() {
        val decay = 1f - alpha
        for (g in 0 until numGrids) {
            val hist = histograms[g]
            val bin  = (brightness[g] * 255).toInt().coerceIn(0, 255)
            for (i in hist.indices) hist[i] *= decay
            hist[bin] += alpha
        }
    }

    /**
     * Rolling-window mode: maintain a circular buffer of the last [windowSize] bin indices.
     * The probability histogram is the uniform average over those bins (no decay).
     * O(1) per grid per frame via running counts.
     */
    private fun updateHistogramsWindow() {
        val bufs   = winBins    ?: return
        val counts = winCounts  ?: return
        val heads  = winHeads   ?: return
        val filled = winFilled  ?: return

        for (g in 0 until numGrids) {
            val newBin = (brightness[g] * 255).toInt().coerceIn(0, 255)
            val head   = heads[g]

            if (filled[g] >= windowSize) {
                // Evict oldest entry
                val oldBin = bufs[g][head].toInt() and 0xFF
                counts[g][oldBin]--
            } else {
                filled[g]++
            }

            // Insert new entry
            counts[g][newBin]++
            bufs[g][head] = newBin.toByte()
            heads[g] = (head + 1) % windowSize

            // Normalise to probability histogram (same format as alpha mode)
            val total = filled[g].toFloat()
            val hist  = histograms[g]
            for (b in 0..255) hist[b] = counts[g][b] / total
        }
    }

    private fun computeAllOtsu() {
        for (g in 0 until numGrids) {
            val (thresh, variance) = otsu(histograms[g])
            otsuThreshold[g] = thresh
            confidence[g] = (variance / MAX_OTSU_VAR).coerceIn(0f, 1f)
        }
    }

    /**
     * Otsu's method on a probability-mass histogram.
     * Returns (threshold bin 0–255, inter-class variance).
     */
    private fun otsu(hist: FloatArray): Pair<Int, Float> {
        var total = 0f
        var sum = 0f
        for (i in hist.indices) {
            total += hist[i]
            sum += i * hist[i]
        }
        if (total == 0f) return 128 to 0f

        var wB = 0f
        var sumB = 0f
        var maxVar = 0f
        var threshold = 0

        for (t in hist.indices) {
            wB += hist[t]
            if (wB == 0f) continue
            val wF = total - wB
            if (wF <= 0f) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val d = mB - mF
            val v = wB * wF * d * d
            if (v > maxVar) {
                maxVar = v
                threshold = t
            }
        }
        return threshold to maxVar
    }

    /** BFS flood-fill to find spatially connected groups of high-confidence grids. */
    private fun findClusters(): List<List<Int>> {
        val active = BooleanArray(numGrids) { confidence[it] >= confidenceThreshold }
        val visited = BooleanArray(numGrids)
        val result = mutableListOf<List<Int>>()

        for (start in 0 until numGrids) {
            if (!active[start] || visited[start]) continue
            val cluster = mutableListOf<Int>()
            val queue = ArrayDeque<Int>()
            queue.add(start)
            visited[start] = true
            while (queue.isNotEmpty()) {
                val g = queue.removeFirst()
                cluster.add(g)
                val r = g / cols
                val c = g % cols
                for (n in neighbors) {
                    val nr = r + n[0]; val nc = c + n[1]
                    if (nr < 0 || nr >= rows || nc < 0 || nc >= cols) continue
                    val ng = nr * cols + nc
                    if (active[ng] && !visited[ng]) {
                        visited[ng] = true
                        queue.add(ng)
                    }
                }
            }
            if (cluster.size >= minClusterSize) result.add(cluster)
        }
        return result
    }

    /** Top-k grids by confidence from the given cluster (or all active if cluster empty). */
    private fun pickTopGrids(cluster: List<Int>): List<Int> {
        val pool = if (cluster.isNotEmpty()) cluster
        else (0 until numGrids).filter { confidence[it] >= confidenceThreshold }
        return pool.sortedByDescending { confidence[it] }.take(topK)
    }

    /** Confidence-weighted mean brightness across the top grids. */
    private fun computeComposite(topGrids: List<Int>): Float {
        if (topGrids.isEmpty()) return 0.5f
        var wSum = 0f; var wTot = 0f
        for (g in topGrids) {
            wSum += confidence[g] * brightness[g]
            wTot += confidence[g]
        }
        return if (wTot > 0f) wSum / wTot else 0.5f
    }

    /**
     * Signed confidence-weighted vote.
     * Each grid contributes weight = confidence × |distance from its own Otsu threshold|.
     * Positive grids (above threshold) add to bright; negative (below) add to dark.
     * Returns value in [−1, +1].
     */
    private fun computeBitVote(topGrids: List<Int>): Float {
        if (topGrids.isEmpty()) return 0f
        var bright = 0f; var dark = 0f
        for (g in topGrids) {
            val normBright = brightness[g]
            val normThresh = otsuThreshold[g] / 255f
            val dist = Math.abs(normBright - normThresh) * 2f  // 0 at boundary, 1 at max
            val vote = confidence[g] * dist
            if (normBright > normThresh) bright += vote else dark += vote
        }
        val total = bright + dark
        return if (total > 0f) (bright - dark) / total else 0f
    }
}
