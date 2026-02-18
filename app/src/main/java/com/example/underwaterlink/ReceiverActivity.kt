package com.example.underwaterlink

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.util.Size
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class ReceiverActivity : AppCompatActivity() {

    // ── Timing windows ────────────────────────────────────────────────────────
    //
    // Target: 200 ms ON + 100 ms OFF.
    //
    // Why such wide windows?
    //   At 30 fps a frame is 33 ms; at 15 fps (common in low light) it is 67 ms.
    //   We use the sensor capture timestamp (not wall clock), so the error is at most
    //   one frame period on each edge — ±67 ms worst-case.
    //   ON window: 200 ± 80  → 120–280 extended to 80–400 for extra margin.
    //   OFF window: 100 ± 70 → 30–170  extended to 20–250 for extra margin.
    //
    // Data symbols (25–70 ms ON) all fall well below ON_MIN, so they are never
    // confused with the start marker.

    companion object {
        private const val ON_MIN_MS  =  80L
        private const val ON_MAX_MS  = 400L
        private const val OFF_MIN_MS =  20L
        private const val OFF_MAX_MS = 250L

        // EMA weight applied to each bright frame while LOCKED.
        // 0.15 means the box moves ~63% of the way to the flash's actual position
        // after ~10 bright frames — smooth but responsive to real motion.
        private const val TRACKING_ALPHA = 0.15f
    }

    // ── State machine ─────────────────────────────────────────────────────────
    private enum class RxState { SCANNING, SAW_BRIGHT, SAW_LONG_ON, LOCKED }

    private var rxState       = RxState.SCANNING
    private var onStartMs     = 0L      // sensor timestamp when bright pulse started
    private var offStartMs    = 0L      // sensor timestamp when OFF gap started
    private var candidateRect: RectF? = null
    private var lockedRect:    RectF? = null

    // Debug state (written by analysis thread, read via snapshot on UI thread)
    private var lastOnMs         = 0L
    private var lastOffMs        = 0L
    private var lastRejectReason = ""
    private var prevFrameTimeMs  = 0L   // for computing live frame rate

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var roiOverlay:  RoiOverlayView
    private lateinit var debugText:   TextView
    private lateinit var resetButton: Button

    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else finish() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receiver)

        previewView = findViewById(R.id.previewView)
        roiOverlay  = findViewById(R.id.roiOverlay)
        debugText   = findViewById(R.id.debugText)
        resetButton = findViewById(R.id.resetButton)

        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        // Post reset to the analysis executor — same thread as processFrame, no race.
        resetButton.setOnClickListener {
            analysisExecutor.execute { doReset() }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Camera setup ──────────────────────────────────────────────────────────
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Small analysis resolution so the pixel scan finishes quickly and
            // does not inflate the gap between sensor capture and our timestamp read.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(320, 240),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()

            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                // ── Use the SENSOR capture timestamp, not the analysis time.
                // imageInfo.timestamp is nanoseconds from the camera HAL (monotonic).
                // Differences between frames of the same use case correctly represent
                // elapsed time between captures, free from analysis-pipeline latency.
                val frameTimeMs = imageProxy.imageInfo.timestamp / 1_000_000L

                val bright = findBrightRegion(imageProxy)
                val imgW   = imageProxy.width
                val imgH   = imageProxy.height
                val rot    = imageProxy.imageInfo.rotationDegrees
                imageProxy.close()   // release buffer before any heavy work

                // Frame-interval for debug fps display
                val frameIntervalMs = if (prevFrameTimeMs > 0) frameTimeMs - prevFrameTimeMs else 0L
                prevFrameTimeMs = frameTimeMs

                processFrame(bright, frameTimeMs)

                // Snapshot all mutable state before crossing to the UI thread
                val uiRect       = if (rxState == RxState.LOCKED) lockedRect else candidateRect
                val isLocked     = rxState == RxState.LOCKED
                val snapState    = rxState
                val snapOnMs     = lastOnMs
                val snapOffMs    = lastOffMs
                val snapReason   = lastRejectReason
                val snapOnStart  = onStartMs
                val snapOffStart = offStartMs

                runOnUiThread {
                    roiOverlay.setRoi(uiRect, imgW, imgH, rot, isLocked)
                    updateDebug(
                        state        = snapState,
                        lastOnMs     = snapOnMs,
                        lastOffMs    = snapOffMs,
                        frameTimeMs  = frameTimeMs,
                        onStartMs    = snapOnStart,
                        offStartMs   = snapOffStart,
                        currentBright= bright,
                        shownRect    = uiRect,
                        rejectReason = snapReason,
                        frameIntervalMs = frameIntervalMs
                    )
                }
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    // ── State machine ─────────────────────────────────────────────────────────
    /**
     * All calls are on the single-threaded [analysisExecutor], so no synchronisation needed.
     *
     * @param bright Normalized bright-region rect, or null (dark frame).
     * @param frameTimeMs Sensor capture time in milliseconds.
     */
    private fun processFrame(bright: RectF?, frameTimeMs: Long) {
        when (rxState) {

            RxState.SCANNING -> {
                if (bright != null) {
                    onStartMs     = frameTimeMs
                    candidateRect = bright
                    rxState       = RxState.SAW_BRIGHT
                }
            }

            RxState.SAW_BRIGHT -> {
                if (bright != null) {
                    // Still ON — accumulate the rect to cover any slight motion
                    candidateRect = union(candidateRect, bright)
                } else {
                    // Went dark — evaluate the ON duration
                    val onDur = frameTimeMs - onStartMs
                    lastOnMs  = onDur
                    if (onDur in ON_MIN_MS..ON_MAX_MS) {
                        // Duration matches ~200 ms start marker → wait for the OFF gap
                        offStartMs = frameTimeMs
                        rxState    = RxState.SAW_LONG_ON
                    } else {
                        lastRejectReason = "ON=${onDur}ms not in [${ON_MIN_MS}–${ON_MAX_MS}]"
                        rxState       = RxState.SCANNING
                        candidateRect = null
                    }
                }
            }

            RxState.SAW_LONG_ON -> {
                val offDur = frameTimeMs - offStartMs
                when {
                    bright != null -> {
                        // Light came back — evaluate the OFF gap duration
                        lastOffMs = offDur
                        if (offDur in OFF_MIN_MS..OFF_MAX_MS) {
                            // ✅ START MARKER CONFIRMED — commit the flash position
                            lockedRect       = RectF(candidateRect)
                            rxState          = RxState.LOCKED
                            lastRejectReason = ""
                        } else {
                            // Wrong OFF gap — try treating this new bright as a fresh start
                            lastRejectReason = "OFF=${offDur}ms not in [${OFF_MIN_MS}–${OFF_MAX_MS}]"
                            onStartMs     = frameTimeMs
                            candidateRect = bright
                            rxState       = RxState.SAW_BRIGHT
                        }
                    }
                    offDur > 600L -> {
                        // No light for 600 ms — TX probably stopped, go back to scanning
                        lastRejectReason = "OFF timeout (${offDur}ms)"
                        rxState       = RxState.SCANNING
                        candidateRect = null
                    }
                    // Still in the OFF gap — keep waiting
                }
            }

            RxState.LOCKED -> {
                // Gradually track the flash as it moves.
                // The TX flickers during data (25–70 ms ON per 100 ms symbol), so bright
                // frames arrive frequently even during normal transmission.
                // Each bright frame nudges lockedRect toward the current detection via EMA.
                // Dark frames (the OFF gaps between symbols) are ignored — the box stays put.
                if (bright != null) {
                    lockedRect = emaRect(lockedRect!!, bright)
                }
            }
        }
    }

    /** Must be called on the analysis executor thread. */
    private fun doReset() {
        rxState          = RxState.SCANNING
        candidateRect    = null
        lockedRect       = null
        lastOnMs         = 0L
        lastOffMs        = 0L
        lastRejectReason = ""
        prevFrameTimeMs  = 0L
        runOnUiThread {
            roiOverlay.setRoi(null, 1, 1, 0, false)
            debugText.text = "State: SCANNING\nWaiting for start marker…"
        }
    }

    // ── Debug display ─────────────────────────────────────────────────────────
    private fun updateDebug(
        state:           RxState,
        lastOnMs:        Long,
        lastOffMs:       Long,
        frameTimeMs:     Long,
        onStartMs:       Long,
        offStartMs:      Long,
        currentBright:   RectF?,
        shownRect:       RectF?,
        rejectReason:    String,
        frameIntervalMs: Long
    ) {
        val sb = StringBuilder()
        val fps = if (frameIntervalMs > 0) 1000L / frameIntervalMs else 0L
        sb.append("State: ${state.name}  (${fps}fps / ${frameIntervalMs}ms/frame)\n")

        // Show live ongoing measurement so you can see what's happening frame-by-frame
        when (state) {
            RxState.SAW_BRIGHT -> {
                val curOn = frameTimeMs - onStartMs
                sb.append("ON so far : ${curOn}ms  (need ${ON_MIN_MS}–${ON_MAX_MS})\n")
            }
            RxState.SAW_LONG_ON -> {
                val curOff = frameTimeMs - offStartMs
                sb.append("ON was    : ${lastOnMs}ms ✓\n")
                sb.append("OFF so far: ${curOff}ms  (need ${OFF_MIN_MS}–${OFF_MAX_MS})\n")
            }
            RxState.LOCKED -> {
                sb.append("ON  confirmed: ${lastOnMs}ms\n")
                sb.append("OFF confirmed: ${lastOffMs}ms\n")
                sb.append("Tracking: EMA α=${TRACKING_ALPHA} per bright frame\n")
            }
            RxState.SCANNING -> {
                if (lastOnMs > 0)  sb.append("Last ON : ${lastOnMs}ms\n")
                if (lastOffMs > 0) sb.append("Last OFF: ${lastOffMs}ms\n")
            }
        }

        if (rejectReason.isNotEmpty()) {
            sb.append("REJECT: $rejectReason\n")
        }

        sb.append(
            if (currentBright != null)
                String.format("Frame: bright  w=%.2f h=%.2f", currentBright.width(), currentBright.height())
            else
                "Frame: dark"
        )

        debugText.text = sb
    }

    // ── Image analysis ────────────────────────────────────────────────────────
    /**
     * Two-pass Y-plane scan.
     *   Pass 1 — find peak luma. Return null if peak < 180 (not torch-bright).
     *   Pass 2 — bounding box of pixels within 30 luma of the peak.
     *
     * ByteBuffer.get(int) is an absolute read; no rewind needed between passes.
     */
    private fun findBrightRegion(imageProxy: ImageProxy): RectF? {
        val plane     = imageProxy.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val w = imageProxy.width
        val h = imageProxy.height

        var maxVal = 0
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = buffer.get(row * rowStride + col).toInt() and 0xFF
                if (v > maxVal) maxVal = v
            }
        }
        if (maxVal < 180) return null

        val threshold = maxVal - 30
        var minX = w;  var maxX = -1
        var minY = h;  var maxY = -1
        for (row in 0 until h) {
            for (col in 0 until w) {
                val v = buffer.get(row * rowStride + col).toInt() and 0xFF
                if (v >= threshold) {
                    if (col < minX) minX = col
                    if (col > maxX) maxX = col
                    if (row < minY) minY = row
                    if (row > maxY) maxY = row
                }
            }
        }
        if (maxX < 0) return null

        return RectF(
            minX.toFloat() / w,
            minY.toFloat() / h,
            (maxX + 1).toFloat() / w,
            (maxY + 1).toFloat() / h
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun union(a: RectF?, b: RectF): RectF =
        if (a == null) RectF(b)
        else RectF(
            minOf(a.left,   b.left),
            minOf(a.top,    b.top),
            maxOf(a.right,  b.right),
            maxOf(a.bottom, b.bottom)
        )

    /**
     * Exponential moving average between [current] and [target].
     * Each call moves [current] by [TRACKING_ALPHA] fraction toward [target].
     * Applied to all four edges independently so the box can grow/shrink/move smoothly.
     */
    private fun emaRect(current: RectF, target: RectF): RectF {
        val a = TRACKING_ALPHA
        val b = 1f - a
        return RectF(
            current.left   * b + target.left   * a,
            current.top    * b + target.top    * a,
            current.right  * b + target.right  * a,
            current.bottom * b + target.bottom * a
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}
