package com.example.underwaterlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class TestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UnderwaterTest"

        // ── Bit periods ────────────────────────────────────────────────────────
        private const val BIT_PERIOD_NS    = 100_000_000L  // 100ms — C1 (fast)
        private const val C2_BIT_PERIOD_NS = 300_000_000L  // 300ms — C2 (slow)

        // ── RX detection (async, no clock sync required) ───────────────────────
        //
        // The receiver measures edge-to-edge intervals and inspects the MINIMUM
        // value in a sliding window.  This works at any phase in the stream.
        //
        // Physical model at 20 fps (50 ms/frame):
        //   Consecutive edge intervals alternate between two types:
        //     Type-A (rising→falling, includes uncertain-zone delay):
        //       C1: 100ms + 50–150ms = 150–250ms
        //       C2: 300ms + 50–150ms = 350–450ms
        //     Type-B (falling→rising, delay works in our favour):
        //       C1: ~50ms  (just 1 frame — very short)
        //       C2: ~200ms
        //
        //   Minimum interval in any window of ≥ 6 edges:
        //     C1: min ≈  50ms  → clearly < 125ms
        //     C2: min ≈ 200ms  → clearly ≥ 125ms
        //   125ms threshold gives ≥ 75ms margin from both classes.
        //
        private const val MIN_INTERVAL_NS        = 20_000_000L   // 20ms  — anti double-trigger
        private const val FAST_THRESHOLD_NS      = 125_000_000L  // 125ms — min < this → C1
        private const val EDGE_TIMEOUT_NS        = 700_000_000L  // 700ms — signal lost → IDLE
        private const val INTERVAL_WINDOW        = 8             // sliding window size
        private const val MIN_CLASSIFY_INTERVALS = 6             // need ≥ 6 to classify

        private const val VOTE_THRESH = 0.25f

        // ── Manual camera exposure (AE disabled) ───────────────────────────────
        // Disabling AE prevents the camera from adapting to torch flashes (which
        // compresses bright/dark amplitude → bitVote → 0 → detection fails).
        // Tune for ambient: lower outdoors (e.g. 8ms), higher in dark (e.g. 33ms).
        private const val MANUAL_EXPOSURE_NS = 16_000_000L  // 16ms
        private const val MANUAL_ISO         = 800
    }

    // ── RX state machine (2-state, fully async) ────────────────────────────────
    //
    // IDLE    — no recent signal; waiting for any definitive bright/dark frame
    // SYNCING — accumulating edge intervals; classifies once window is full
    //
    // Classification: minInterval < FAST_THRESHOLD_NS → C1, otherwise → C2
    // No preamble, no break, no clock sync required.  Works from any phase.

    private enum class RxState { IDLE, SYNCING }

    @Volatile private var rxState = RxState.IDLE

    // Schmitt-trigger sign (+1=bright, -1=dark, 0=uninitialised)
    private var currentSign  = 0
    private var lastEdgeNs   = 0L

    // Sliding window of edge-to-edge intervals
    private val rxIntervals  = ArrayDeque<Long>()
    private var totalEdges   = 0  // edges this SYNCING session

    // Display state — kept across resets; cleared only by RESET RX button
    private var lastReceivedMsg = ""
    private var lastAnnouncedNs = 0L   // throttle Toast to once per 3 s

    // BER strip fields kept for overlay compatibility
    private val rxBits   = StringBuilder()
    private val exBits   = StringBuilder()
    private var errorCount = 0
    private var totalBits  = 0

    // ── GridAnalyzer ──────────────────────────────────────────────────────────

    @Volatile private var analyzer: GridAnalyzer? = null
    private var currentAlpha      = 0.05f
    private var currentConfThresh = 0.15f
    private var currentGridSize   = 8
    private var currentWindowN    = 30
    private var useWindowMode     = false
    private val analyzerLock      = Any()

    // ── FPS tracking ──────────────────────────────────────────────────────────

    private var lastFrameNs = 0L
    private var smoothFps   = 20f

    // ── TX ────────────────────────────────────────────────────────────────────

    @Volatile private var isTxRunning = false
    private var txThread: HandlerThread? = null
    private var txHandler: Handler?      = null
    @Volatile private var cameraControl: CameraControl? = null

    // ── Camera executor ───────────────────────────────────────────────────────

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var previewView:    PreviewView
    private lateinit var debugOverlay:   DebugOverlayView
    private lateinit var stateText:      TextView
    private lateinit var statsText:      TextView
    private lateinit var txC1Button:     Button
    private lateinit var txC2Button:     Button
    private lateinit var stopTxButton:   Button
    private lateinit var alphaSeekBar:   SeekBar
    private lateinit var alphaValueText: TextView
    private lateinit var confSeekBar:    SeekBar
    private lateinit var confValueText:  TextView
    private lateinit var gridSeekBar:    SeekBar
    private lateinit var gridValueText:  TextView
    private lateinit var modeToggle:     ToggleButton
    private lateinit var histogramPanel: HistogramPanelView

    // ── Permission ────────────────────────────────────────────────────────────

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_test)

        previewView    = findViewById(R.id.testPreviewView)
        debugOverlay   = findViewById(R.id.testDebugOverlay)
        stateText      = findViewById(R.id.testStateText)
        statsText      = findViewById(R.id.testStatsText)
        txC1Button     = findViewById(R.id.testTxC1Button)
        txC2Button     = findViewById(R.id.testTxC2Button)
        stopTxButton   = findViewById(R.id.testStopTxButton)
        alphaSeekBar   = findViewById(R.id.alphaSeekBar)
        alphaValueText = findViewById(R.id.alphaValueText)
        confSeekBar    = findViewById(R.id.confSeekBar)
        confValueText  = findViewById(R.id.confValueText)
        gridSeekBar    = findViewById(R.id.gridSeekBar)
        gridValueText  = findViewById(R.id.gridValueText)
        modeToggle     = findViewById(R.id.modeToggle)
        histogramPanel = findViewById(R.id.histogramPanel)

        setupSliders()
        setupButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        isTxRunning = false
        txThread?.quitSafely()
        cameraExecutor.shutdown()
        cameraControl?.enableTorch(false)
    }

    // ── Sliders ───────────────────────────────────────────────────────────────

    private fun setupSliders() {
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            useWindowMode = isChecked
            if (isChecked) {
                alphaSeekBar.max      = 999
                alphaSeekBar.progress = (currentWindowN - 1).coerceIn(0, 999)
                alphaValueText.text   = "N=${currentWindowN}"
                synchronized(analyzerLock) {
                    analyzer?.useRollingWindow = true
                    analyzer?.windowSize       = currentWindowN
                }
            } else {
                alphaSeekBar.max      = 999
                alphaSeekBar.progress = (currentAlpha * 1000f - 1f).toInt().coerceIn(0, 999)
                alphaValueText.text   = "α=${String.format("%.3f", currentAlpha)}"
                synchronized(analyzerLock) { analyzer?.useRollingWindow = false }
            }
        }

        alphaSeekBar.max      = 999
        alphaSeekBar.progress = 49
        alphaValueText.text   = "α=0.050"
        alphaSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (useWindowMode) {
                    currentWindowN      = p + 1
                    alphaValueText.text = "N=$currentWindowN"
                    synchronized(analyzerLock) { analyzer?.windowSize = currentWindowN }
                } else {
                    currentAlpha        = (p + 1) / 1000f
                    alphaValueText.text = "α=${String.format("%.3f", currentAlpha)}"
                    synchronized(analyzerLock) { analyzer?.alpha = currentAlpha }
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        confSeekBar.max      = 100
        confSeekBar.progress = 15
        confValueText.text   = "0.150"
        confSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                currentConfThresh  = p / 100f
                confValueText.text = String.format("%.3f", currentConfThresh)
                synchronized(analyzerLock) { analyzer?.confidenceThreshold = currentConfThresh }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        gridSeekBar.max      = 3
        gridSeekBar.progress = 1
        gridValueText.text   = "8px"
        gridSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                currentGridSize    = 4 shl p
                gridValueText.text = "${currentGridSize}px"
                synchronized(analyzerLock) { analyzer = null }
                resetRx()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        txC1Button.setOnClickListener  { startTx(BIT_PERIOD_NS) }
        txC2Button.setOnClickListener  { startTx(C2_BIT_PERIOD_NS) }
        stopTxButton.setOnClickListener { stopTx() }
        findViewById<Button>(R.id.testResetRxButton).setOnClickListener { resetRx() }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build()
            preview.surfaceProvider = previewView.surfaceProvider

            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

            // Disable AE so the camera cannot adapt to torch flashes.
            // When AE is ON it compensates within 1-3 frames → bitVote collapses → no edges.
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 20))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, MANUAL_EXPOSURE_NS)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, MANUAL_ISO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                val plane     = imageProxy.planes[0]
                val buffer    = plane.buffer
                val rowStride = plane.rowStride
                val frameNs   = imageProxy.imageInfo.timestamp

                if (analyzer == null) {
                    val w = imageProxy.width
                    val h = imageProxy.height
                    synchronized(analyzerLock) {
                        if (analyzer == null) {
                            analyzer = GridAnalyzer(
                                imageWidth          = w,
                                imageHeight         = h,
                                gridSize            = currentGridSize,
                                alpha               = currentAlpha,
                                confidenceThreshold = currentConfThresh
                            ).also { a ->
                                if (useWindowMode) {
                                    a.useRollingWindow = true
                                    a.windowSize       = currentWindowN
                                }
                            }
                        }
                    }
                    runOnUiThread { debugOverlay.setImageDimensions(w, h) }
                }

                if (lastFrameNs > 0) {
                    val inst = 1_000_000_000f / (frameNs - lastFrameNs).toFloat()
                    smoothFps = smoothFps * 0.85f + inst * 0.15f
                }
                lastFrameNs = frameNs

                val result = synchronized(analyzerLock) {
                    analyzer!!.processFrame(buffer, rowStride)
                }

                processRxState(result, frameNs)

                runOnUiThread {
                    histogramPanel.update(result)
                    debugOverlay.update(
                        DebugOverlayView.DebugData(
                            result              = result,
                            state               = rxState.name,
                            alpha               = currentAlpha,
                            confThreshold       = currentConfThresh,
                            fps                 = smoothFps,
                            bitVote             = result.bitVote,
                            receivedBits        = rxBits.toString(),
                            expectedBits        = exBits.toString(),
                            errorCount          = errorCount,
                            totalBits           = totalBits,
                            uncertainBits       = 0,
                            lastReceivedMessage = lastReceivedMsg
                        )
                    )

                    // Status line
                    val minNs   = rxIntervals.minOrNull() ?: Long.MAX_VALUE
                    val extra = when {
                        rxState == RxState.SYNCING && rxIntervals.size >= MIN_CLASSIFY_INTERVALS -> {
                            val label = if (minNs < FAST_THRESHOLD_NS) "C1" else "C2"
                            " [$label] min=${minNs/1_000_000L}ms e=$totalEdges"
                        }
                        rxState == RxState.SYNCING ->
                            " collecting ${rxIntervals.size}/$MIN_CLASSIFY_INTERVALS"
                        else -> ""
                    }
                    stateText.text = "RX: ${rxState.name}$extra"
                    statsText.text = if (lastReceivedMsg.isNotEmpty()) lastReceivedMsg else "—"
                }

                imageProxy.close()
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                cameraControl = camera.cameraControl
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ── RX state machine ──────────────────────────────────────────────────────

    private fun processRxState(result: GridResult, frameNs: Long) {
        val isBright = result.bitVote >  VOTE_THRESH
        val isDark   = result.bitVote < -VOTE_THRESH

        when (rxState) {

            // ── IDLE: wait for any definitive signal ──────────────────────────
            RxState.IDLE -> {
                if (isBright || isDark) {
                    currentSign = if (isBright) 1 else -1
                    lastEdgeNs  = frameNs
                    rxIntervals.clear()
                    totalEdges  = 0
                    rxState     = RxState.SYNCING
                }
            }

            // ── SYNCING: measure intervals, classify by minimum ───────────────
            //
            // The minimum interval in a window of N edges reliably discriminates:
            //   C1 (100ms/bit): alternates ~50ms / ~200ms → min ≈  50ms (< 125ms)
            //   C2 (300ms/bit): alternates ~200ms / ~400ms → min ≈ 200ms (≥ 125ms)
            //
            // Works regardless of where in the TX stream the receiver starts.
            RxState.SYNCING -> {
                // Signal lost → back to IDLE
                if (frameNs - lastEdgeNs > EDGE_TIMEOUT_NS) {
                    Log.v(TAG, "SYNCING: timeout e=$totalEdges")
                    clearToIdle()
                    return
                }

                // Schmitt-trigger edge detection
                val prevSign = currentSign
                if      (isBright && currentSign != 1)  currentSign = 1
                else if (isDark   && currentSign != -1) currentSign = -1

                if (currentSign != prevSign && prevSign != 0) {
                    val interval = frameNs - lastEdgeNs
                    lastEdgeNs   = frameNs

                    if (interval < MIN_INTERVAL_NS) return  // double-trigger, skip

                    // Add to sliding window, evict oldest if full
                    rxIntervals.addLast(interval)
                    if (rxIntervals.size > INTERVAL_WINDOW) rxIntervals.removeFirst()
                    totalEdges++

                    Log.v(TAG, "SYNCING: e=$totalEdges interval=${interval/1_000_000L}ms window=${rxIntervals.size}")

                    // Classify once we have enough samples
                    if (rxIntervals.size >= MIN_CLASSIFY_INTERVALS) {
                        val minInterval = rxIntervals.minOrNull() ?: return
                        val label = if (minInterval < FAST_THRESHOLD_NS) "C1" else "C2"
                        lastReceivedMsg = "$label RECEIVED"
                        totalBits = totalEdges

                        // Toast — throttled to once every 3 seconds
                        if (frameNs - lastAnnouncedNs > 3_000_000_000L) {
                            lastAnnouncedNs = frameNs
                            val msg = lastReceivedMsg
                            runOnUiThread {
                                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Reset helpers ─────────────────────────────────────────────────────────

    private fun clearToIdle() {
        rxState     = RxState.IDLE
        currentSign = 0
        lastEdgeNs  = 0L
        rxIntervals.clear()
        totalEdges  = 0
    }

    private fun resetRx() {
        clearToIdle()
        lastReceivedMsg = ""
        lastAnnouncedNs = 0L
        rxBits.clear(); exBits.clear()
        errorCount = 0; totalBits = 0
        synchronized(analyzerLock) { analyzer?.reset() }
    }

    // ── TX ────────────────────────────────────────────────────────────────────

    private fun startTx(bitPeriodNs: Long) {
        if (isTxRunning) return
        isTxRunning = true
        txC1Button.isEnabled   = false
        txC2Button.isEnabled   = false
        stopTxButton.isEnabled = true

        txThread = HandlerThread("TestTX").also { it.start() }
        txHandler = Handler(txThread!!.looper)
        txHandler!!.post { txLoop(bitPeriodNs) }
    }

    private fun stopTx() {
        isTxRunning = false
        txC1Button.isEnabled   = true
        txC2Button.isEnabled   = true
        stopTxButton.isEnabled = false
        cameraControl?.enableTorch(false)
        txThread?.quitSafely()
    }

    /**
     * TX: continuous alternating ON/OFF at [bitPeriodNs] per bit.
     *
     * C1: bitPeriodNs = 100ms  (fast)
     * C2: bitPeriodNs = 300ms  (slow)
     *
     * No preamble, no break, no framing — just pure alternating pulses.
     * The receiver classifies by the minimum observed edge interval (async-safe).
     * Absolute wall-clock scheduling prevents jitter accumulation.
     */
    private fun txLoop(bitPeriodNs: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Warmup: burn the first slow torch-HAL initialisation call.
        torch(true);  Thread.sleep(50)
        torch(false); Thread.sleep(100)

        val start = SystemClock.elapsedRealtimeNanos()
        var i = 0L
        while (isTxRunning) {
            spinWait(start + i * bitPeriodNs)
            torch(i % 2L == 0L)   // ON for even indices, OFF for odd
            i++
        }

        torch(false)
        runOnUiThread {
            txC1Button.isEnabled   = true
            txC2Button.isEnabled   = true
            stopTxButton.isEnabled = false
        }
    }

    private fun torch(on: Boolean) { cameraControl?.enableTorch(on) }

    private fun spinWait(targetNs: Long) {
        while (isTxRunning && SystemClock.elapsedRealtimeNanos() < targetNs) { /* busy-wait */ }
    }
}
