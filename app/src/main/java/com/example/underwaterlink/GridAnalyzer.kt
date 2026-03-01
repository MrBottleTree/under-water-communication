package com.example.underwaterlink

import java.nio.ByteBuffer

data class GridResult(
    val cols: Int,
    val rows: Int,
    val brightness: FloatArray,          // [numGrids], 0.0–1.0 normalised
    val confidence: FloatArray,          // [numGrids], amplitude-based (0 = inactive)
    val otsuThreshold: IntArray,         // [numGrids], unused (kept for API compat)
    val topGrids: List<Int>,             // indices of top-K voting grids
    val topGridHistograms: List<FloatArray>, // dummy (kept for API compat)
    val compositeBrightness: Float,      // global maximum brightness
    val bitVote: Float,                  // −1.0 = dark, +1.0 = bright, 0.0 = stable
    val activeGridCount: Int,            // number of grids with significant amplitude
    val largestClusterSize: Int          // size of the BFS cluster used for voting
)

/**
 * Per-Grid Rolling-Window Analyzer for the optical RX channel.
 *
 * For each grid cell, maintains a rolling window of [windowSize] (default 3) brightness
 * values. The window amplitude (max − min) indicates whether that grid recently transitioned.
 *
 * ## Why this works with RxBitDecoder's spike-latch model
 *
 * With [windowSize] = 3 at 20 fps the three possible steady-state histories are:
 *
 *   [B, B, B]  →  amplitude = 0  →  bitVote = 0.0  (stable bright, no vote)
 *   [D, D, D]  →  amplitude = 0  →  bitVote = 0.0  (stable dark, no vote)
 *   [D, D, B]  →  amplitude > 0, current = B > mid  →  bitVote ≈ +1.0  (RISE spike)
 *   [B, B, D]  →  amplitude > 0, current = D < mid  →  bitVote ≈ −1.0  (FALL spike)
 *
 * Each edge produces a spike lasting exactly N−1 = 2 frames (100 ms), then returns to
 * exactly 0.0.  RxBitDecoder's edge-triggered Schmitt trigger catches the first spike and
 * ignores the adapted baseline — this combination is the design intent documented in CLAUDE.md.
 *
 * ## BFS clustering
 * Only grids whose amplitude exceeds [confidenceThreshold] are "active". A BFS from the
 * highest-amplitude active grid collects all spatially connected active grids (the torch
 * region). The top-[topK] grids from that cluster cast an amplitude-weighted vote.
 * Unrelated scene transitions (person walking, light flicker) that are not connected to
 * the torch region are excluded.
 *
 * ## Initialisation
 * On the very first frame after creation or [reset], all history slots are filled with the
 * first frame's brightness values. This eliminates a zero-initialisation artefact where
 * normal ambient brightness would appear as a large transition.
 */
class GridAnalyzer(
    val imageWidth: Int = 640,
    val imageHeight: Int = 480,
    val gridSize: Int = 8,
    var alpha: Float = 0.05f,               // kept for API compat (unused in this impl)
    var confidenceThreshold: Float = 0.05f,
    var topK: Int = 4,
    var minClusterSize: Int = 2             // kept for API compat (unused in this impl)
) {
    val cols     = imageWidth  / gridSize
    val rows     = imageHeight / gridSize
    val numGrids = cols * rows

    // ── Per-frame scratch buffers ─────────────────────────────────────────────

    private val brightness    = FloatArray(numGrids)
    private val confidence    = FloatArray(numGrids)  // = amplitude for active grids
    private val voteArr       = FloatArray(numGrids)  // signed amplitude vote
    private val otsuThreshold = IntArray(numGrids)    // unused; kept for GridResult API

    // ── Rolling-window state ──────────────────────────────────────────────────

    /** Always true — this implementation exclusively uses the rolling-window method. */
    var useRollingWindow: Boolean = true

    /**
     * Number of frames in each grid's brightness history.
     *
     * Default 3: produces ±1.0 spikes for exactly 2 frames at transitions, 0.0 in all
     * adapted states.  Increasing N widens the spike (more frames of ±1) and also
     * lengthens the dead-band needed for the baseline to return to 0.
     *
     * Clamped to [1, 20].
     */
    var windowSize: Int = 3
        set(value) {
            field = value.coerceIn(1, 20)
            allocateHistory()
        }

    /**
     * Flat per-grid circular buffer.
     * Access pattern: `gridHistory[gridIdx * windowSize + slotIdx]`
     * Re-allocated whenever [windowSize] changes (via [allocateHistory]).
     */
    private var gridHistory  = FloatArray(numGrids * windowSize)
    private var historyHead  = 0   // next write slot in [0, windowSize)
    private var historyCount = 0   // frames stored; voting requires == windowSize

    // ── BFS scratch (pre-allocated to avoid per-frame GC) ────────────────────

    private val visited    = BooleanArray(numGrids)
    private val isActive   = BooleanArray(numGrids)
    private val bfsQueue   = IntArray(numGrids)   // BFS frontier
    private val clusterBuf = IntArray(numGrids)   // grids in the current BFS cluster
    private val takenBuf   = BooleanArray(numGrids) // top-K selection scratch

    init { allocateHistory() }

    private fun allocateHistory() {
        gridHistory  = FloatArray(numGrids * windowSize)
        historyHead  = 0
        historyCount = 0
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun processFrame(buffer: ByteBuffer, rowStride: Int, cropX: Int = 0, cropY: Int = 0): GridResult {

        // ── 1. Per-grid brightness from Y-plane luma ─────────────────────────
        val scale = 1f / (gridSize * gridSize * 255f)
        var globalMaxBright = 0f
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                var sum = 0
                for (py in 0 until gridSize) {
                    val lineStart = (cropY + row * gridSize + py) * rowStride + (cropX + col * gridSize)
                    for (px in 0 until gridSize) {
                        sum += buffer.get(lineStart + px).toInt() and 0xFF
                    }
                }
                val b   = sum * scale
                val idx = row * cols + col
                brightness[idx] = b
                if (b > globalMaxBright) globalMaxBright = b
            }
        }

        // ── 2. Update rolling window ─────────────────────────────────────────
        //
        // First frame: fill ALL history slots with the current brightness so that
        // amplitude = 0 on frame 1 (no false transition from zero-init).
        if (historyCount == 0) {
            for (i in 0 until numGrids) {
                val b    = brightness[i]
                val base = i * windowSize
                for (j in 0 until windowSize) gridHistory[base + j] = b
            }
            historyHead  = 1 % windowSize   // next write goes to slot 1
            historyCount = windowSize        // window is immediately full
        } else {
            val slot = historyHead
            for (i in 0 until numGrids) gridHistory[i * windowSize + slot] = brightness[i]
            historyHead = (historyHead + 1) % windowSize
            if (historyCount < windowSize) historyCount++
        }

        // ── 3. Per-grid amplitude and signed vote ────────────────────────────
        //
        //   amplitude = max(window) − min(window)
        //   vote      = +amplitude if current > midpoint, −amplitude otherwise
        //   active    = amplitude > confidenceThreshold
        confidence.fill(0f)
        voteArr.fill(0f)
        var activeCount = 0

        for (i in 0 until numGrids) {
            val base = i * windowSize
            var gMin = 1f; var gMax = 0f
            for (j in 0 until windowSize) {
                val v = gridHistory[base + j]
                if (v < gMin) gMin = v
                if (v > gMax) gMax = v
            }
            val amp = gMax - gMin
            if (amp > confidenceThreshold) {
                val mid       = (gMin + gMax) * 0.5f
                voteArr[i]    = if (brightness[i] > mid) amp else -amp
                confidence[i] = amp
                activeCount++
            }
        }

        // ── 4. BFS cluster + top-K vote ──────────────────────────────────────
        //
        // Start BFS from the grid with the highest confidence (most likely the torch).
        // Collect all spatially connected active grids → largest coherent source.
        // Select the top-K by confidence from that cluster and compute a weighted vote.
        val topGridsList: MutableList<Int> = mutableListOf()
        var bitVote    = 0f
        var clusterSize = 0

        if (activeCount > 0) {
            var anchorIdx = -1; var maxConf = 0f
            for (i in 0 until numGrids) {
                isActive[i] = confidence[i] > 0f
                visited[i]  = false
                if (confidence[i] > maxConf) { maxConf = confidence[i]; anchorIdx = i }
            }

            // BFS from anchor
            var qHead = 0; var qTail = 0; var cSize = 0
            bfsQueue[qTail++] = anchorIdx
            visited[anchorIdx] = true
            while (qHead < qTail) {
                val curr = bfsQueue[qHead++]
                clusterBuf[cSize++] = curr
                val r = curr / cols; val c = curr % cols
                if (r > 0)        { val n = curr - cols; if (isActive[n] && !visited[n]) { visited[n] = true; bfsQueue[qTail++] = n } }
                if (r < rows - 1) { val n = curr + cols; if (isActive[n] && !visited[n]) { visited[n] = true; bfsQueue[qTail++] = n } }
                if (c > 0)        { val n = curr - 1;    if (isActive[n] && !visited[n]) { visited[n] = true; bfsQueue[qTail++] = n } }
                if (c < cols - 1) { val n = curr + 1;    if (isActive[n] && !visited[n]) { visited[n] = true; bfsQueue[qTail++] = n } }
            }
            clusterSize = cSize

            // Top-K from cluster by confidence (O(cSize × topK) selection; topK is tiny)
            for (ci in 0 until cSize) takenBuf[ci] = false
            var sumVotes = 0f; var sumAmp = 0f
            repeat(minOf(topK, cSize)) {
                var best = -1; var bestConf = 0f
                for (ci in 0 until cSize) {
                    if (!takenBuf[ci] && confidence[clusterBuf[ci]] > bestConf) {
                        bestConf = confidence[clusterBuf[ci]]; best = ci
                    }
                }
                if (best >= 0) {
                    takenBuf[best] = true
                    val g = clusterBuf[best]
                    topGridsList.add(g)
                    sumVotes += voteArr[g]
                    sumAmp   += confidence[g]
                }
            }
            bitVote = if (sumAmp > 0f) (sumVotes / sumAmp).coerceIn(-1f, 1f) else 0f
        }

        // Dummy histogram list — keeps the GridResult API intact for the debug UI.
        val dummyHistograms = (0 until 4).map { FloatArray(256) }

        return GridResult(
            cols                = cols,
            rows                = rows,
            brightness          = brightness.copyOf(),
            confidence          = confidence.copyOf(),
            otsuThreshold       = otsuThreshold,
            topGrids            = topGridsList,
            topGridHistograms   = dummyHistograms,
            compositeBrightness = globalMaxBright,
            bitVote             = bitVote,
            activeGridCount     = activeCount,
            largestClusterSize  = clusterSize
        )
    }

    fun reset() {
        brightness.fill(0f)
        allocateHistory()
    }
}
