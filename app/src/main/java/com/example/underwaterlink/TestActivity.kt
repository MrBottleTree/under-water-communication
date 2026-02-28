package com.example.underwaterlink

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
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

        // ── Camera exposure ────────────────────────────────────────────────────
        // We keep AE_MODE_OFF so the camera never auto-adapts to torch flashes
        // (built-in AE compensates within 1-3 frames → bitVote collapses → detection fails).
        // Instead we run our own slow software AE loop that shifts exposure by at most
        // AE_MAX_STEP per update, once every AE_UPDATE_FRAMES frames.  This is slow
        // enough that the Otsu histogram can track the gradual change without losing
        // its bimodal structure.
        private const val INITIAL_EXPOSURE_NS  = 16_000_000L   // 16ms — starting point
        private const val MANUAL_ISO           = 800
        private const val AE_TARGET_BRIGHTNESS = 0.40f         // target mean scene brightness
        private const val AE_UPDATE_FRAMES     = 30            // update interval: ~1.5s at 20fps
        private const val AE_EMA_ALPHA         = 0.05f         // EMA smoothing (~1s time constant)
        private const val AE_MAX_STEP          = 0.15f         // max ±15% change per update
        private const val AE_MIN_EXPOSURE_NS   = 4_000_000L    // 4ms  floor
        // Ceiling capped at one frame period (50ms = 1/20fps).  Exposures longer than
        // the frame period physically prevent 20fps regardless of FPS range hints.
        private const val AE_MAX_EXPOSURE_NS   = 50_000_000L   // 50ms ceiling (= 1/20fps)
        // Explicit frame duration for AE_MODE_OFF.  CONTROL_AE_TARGET_FPS_RANGE is only
        // a hint and is often ignored by the HAL when AE is off; SENSOR_FRAME_DURATION
        // is the authoritative field that locks the frame period.
        private const val SENSOR_FRAME_DURATION_NS = 50_000_000L  // 50ms = 20fps

        // ── Auto-sync timing ───────────────────────────────────────────────────
        private const val SYNC_TX_WINDOW_MS       = 2_000L   // TX each probing phase: 2s
        private const val SYNC_RX_WINDOW_MS       = 6_000L   // RX each probing phase: 6s (3× TX)
        private const val SYNC_MAX_RETRY_DELAY_MS = 3_000L   // random backoff 0–3s before retry
        private const val SYNC_BURST_MS           = 2_000L   // sync signal (C1) burst from responder
        // Guard delay before each response TX.  Classification fires after ~600ms (6 edges
        // at 100ms/bit), but the sender's window runs for SYNC_TX_WINDOW_MS = 2s.
        // Waiting 2s from classification guarantees the sender has stopped before we start TX.
        private const val SYNC_RESPONSE_DELAY_MS  = 2_000L
    }

    // ── RX state machine (2-state, fully async) ────────────────────────────────
    //
    // IDLE    — no recent signal; waiting for any definitive bright/dark frame
    // SYNCING — accumulating edge intervals; classifies once window is full
    //
    // Classification: minInterval < FAST_THRESHOLD_NS → C1, otherwise → C2
    // No preamble, no break, no clock sync required.  Works from any phase.

    private enum class RxState { IDLE, SYNCING }

    // ── Auto-sync state machine ────────────────────────────────────────────────
    private enum class AutoSyncState {
        OFF,         // auto-sync not running
        PROBING,     // random TX C1 or RX — finding partner
        WAIT_ACK,    // initiator: sent C1, now RX waiting for C2 ACK
        SEND_ACK,    // responder: received C1, now TX C2 ACK
        WAIT_FINAL,  // responder: sent C2 ACK, now RX waiting for final C2
        SEND_FINAL,  // initiator: received C2 ACK, now TX final C2
        WAIT_SYNC,   // initiator: sent final C2, now RX waiting for sync signal (C1)
        SEND_SYNC,   // responder: received final C2, now TX sync signal (C1 burst)
        SYNCED       // both devices: TX C1 continuously (synchronized flash)
    }

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

    // ── Auto-sync state variables ──────────────────────────────────────────────
    @Volatile private var autoSyncState      = AutoSyncState.OFF
    private var handshakeDeviceState         = 0          // 0 / 1 / 2 (shown in debug)
    private var syncRole                     = "?"        // "?" | "INIT" | "RESP"
    @Volatile private var syncIsTxActive     = false      // true while TX in auto-sync (pauses histogram)
    private var rxPhaseHandled               = false      // prevent double-fire per RX window
    private val syncHandler                  = Handler(Looper.getMainLooper())
    private var syncRxTimeoutRunnable: Runnable? = null

    // ── Zoom ──────────────────────────────────────────────────────────────────

    // setZoomRatio() applies to both Preview and ImageAnalysis frames — no crop needed.
    private var currentZoom = 1

    private lateinit var zoom1xButton: Button
    private lateinit var zoom2xButton: Button
    private lateinit var zoom4xButton: Button
    private lateinit var zoomLabel:    TextView

    // ── Slow auto-exposure state (camera executor thread, except aeExposureNs) ──
    // All fields written only from the analysis callback; aeExposureNs is also
    // read from the UI thread for display, hence @Volatile.
    @Volatile private var aeExposureNs  = INITIAL_EXPOSURE_NS
    private var aeBrightnessEma         = -1f   // negative = uninitialised
    private var aeFrameCount            = 0

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
    private lateinit var autoSyncButton: Button
    private lateinit var stopSyncButton: Button
    private lateinit var syncStateText:  TextView

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
        CharCode.load(this)

        previewView    = findViewById(R.id.testPreviewView)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
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
        zoom1xButton   = findViewById(R.id.zoom1xButton)
        zoom2xButton   = findViewById(R.id.zoom2xButton)
        zoom4xButton   = findViewById(R.id.zoom4xButton)
        zoomLabel      = findViewById(R.id.zoomLabel)
        autoSyncButton = findViewById(R.id.autoSyncButton)
        stopSyncButton = findViewById(R.id.stopSyncButton)
        syncStateText  = findViewById(R.id.syncStateText)

        setupSliders()
        setupButtons()
        setupZoom()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        syncHandler.removeCallbacksAndMessages(null)
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
        autoSyncButton.setOnClickListener { startAutoSync() }
        stopSyncButton.setOnClickListener { stopAutoSync() }
        stopSyncButton.isEnabled = false
    }

    // ── Zoom ──────────────────────────────────────────────────────────────────

    private fun setupZoom() {
        updateZoomButtons()
        fun applyZoom(zoom: Int) {
            if (currentZoom == zoom) return
            currentZoom = zoom
            // Reset histogram so stale bimodal state doesn't confuse classification.
            synchronized(analyzerLock) { analyzer?.reset() }
            resetRx()
            updateZoomButtons()
            zoomLabel.text = "${currentZoom}×"
            // setZoomRatio applies to ImageAnalysis frames too — no camera restart needed.
            cameraControl?.setZoomRatio(zoom.toFloat())
        }
        zoom1xButton.setOnClickListener { applyZoom(1) }
        zoom2xButton.setOnClickListener { applyZoom(2) }
        zoom4xButton.setOnClickListener { applyZoom(4) }
    }

    private fun updateZoomButtons() {
        val active   = ColorStateList.valueOf(Color.parseColor("#1B5E20"))
        val inactive = ColorStateList.valueOf(Color.parseColor("#333333"))
        zoom1xButton.backgroundTintList = if (currentZoom == 1) active else inactive
        zoom2xButton.backgroundTintList = if (currentZoom == 2) active else inactive
        zoom4xButton.backgroundTintList = if (currentZoom == 4) active else inactive
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

            // AE_MODE_OFF: we control exposure manually so the camera never auto-compensates
            // for torch flashes.  Our slow software AE loop takes over from here.
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 20))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, aeExposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, SENSOR_FRAME_DURATION_NS)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, MANUAL_ISO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                val plane     = imageProxy.planes[0]
                val buffer    = plane.buffer
                val rowStride = plane.rowStride
                val frameNs   = imageProxy.imageInfo.timestamp

                // Lazy init — always runs so analyzer is ready when RX phase starts.
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

                if (!syncIsTxActive) {
                    val result = synchronized(analyzerLock) {
                        analyzer!!.processFrame(buffer, rowStride)
                    }

                    processRxState(result, frameNs)

                    // ── Slow software AE ───────────────────────────────────────────────
                    // Use the mean brightness across ALL grids as the AE signal.
                    // The LED covers only a few grids out of the full grid, so its
                    // flashing barely moves the whole-frame mean — no need to mask it.
                    val meanBrightness = result.brightness.average().toFloat()
                    aeBrightnessEma = if (aeBrightnessEma < 0f) meanBrightness
                                      else aeBrightnessEma * (1f - AE_EMA_ALPHA) + meanBrightness * AE_EMA_ALPHA
                    aeFrameCount++
                    if (aeFrameCount % AE_UPDATE_FRAMES == 0) {
                        val error    = AE_TARGET_BRIGHTNESS - aeBrightnessEma
                        val step     = (error * 0.5f).coerceIn(-AE_MAX_STEP, AE_MAX_STEP)
                        val newExp   = (aeExposureNs * (1f + step)).toLong()
                                          .coerceIn(AE_MIN_EXPOSURE_NS, AE_MAX_EXPOSURE_NS)
                        if (newExp != aeExposureNs) {
                            aeExposureNs = newExp
                            Log.v(TAG, "AE: brt=${String.format("%.2f", aeBrightnessEma)} " +
                                       "step=${String.format("%+.0f", step * 100)}% " +
                                       "exp=${newExp / 1_000_000L}ms")
                        }
                        // Always re-assert FPS lock — torch toggles can reset Camera2CameraControl
                        // state, and without periodic re-assertion the camera slips to ~10fps.
                        applyExposure(aeExposureNs)
                    }

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
                        if (autoSyncState != AutoSyncState.OFF) {
                            stateText.text = "SYNC:${autoSyncState.name} [RX] s=$handshakeDeviceState role=$syncRole"
                        } else {
                            stateText.text = "RX: ${rxState.name}$extra"
                        }
                        syncStateText.text = if (autoSyncState != AutoSyncState.OFF)
                            "SYNC: ${autoSyncState.name} $syncRole s=$handshakeDeviceState"
                        else "SYNC: OFF"
                        val expMs = aeExposureNs / 1_000_000L
                        statsText.text = "${if (lastReceivedMsg.isNotEmpty()) lastReceivedMsg else "—"} | exp=${expMs}ms"
                    }
                } else {
                    // TX phase: histogram frozen, camera preview still runs.
                    // Re-assert FPS lock every AE_UPDATE_FRAMES frames — the AE loop above
                    // is skipped during TX, so nothing else is calling applyExposure().
                    aeFrameCount++
                    if (aeFrameCount % AE_UPDATE_FRAMES == 0) {
                        applyExposure(aeExposureNs)
                    }
                    runOnUiThread {
                        stateText.text = "SYNC:${autoSyncState.name} [TX] s=$handshakeDeviceState role=$syncRole"
                        syncStateText.text = "SYNC: ${autoSyncState.name} $syncRole s=$handshakeDeviceState"
                        statsText.text = "— | exp=${aeExposureNs / 1_000_000L}ms"
                    }
                }

                imageProxy.close()
            }

            try {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                cameraControl = camera.cameraControl
                if (currentZoom != 1) cameraControl?.setZoomRatio(currentZoom.toFloat())
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

                        // Notify auto-sync state machine on main thread
                        if (autoSyncState != AutoSyncState.OFF) {
                            val capturedLabel = label
                            runOnUiThread { handleAutoSyncRxResult(capturedLabel) }
                        }

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
        if (isTxRunning || autoSyncState != AutoSyncState.OFF) return
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
        // Use bit-period-aligned durations so warmup intervals match the real signal.
        // Fixed 50ms/100ms would create intervals << 300ms that contaminate the C2 sliding
        // window and cause the receiver to classify the entire C2 burst as C1.
        val bitPeriodMs = bitPeriodNs / 1_000_000L
        torch(true);  Thread.sleep(bitPeriodMs)
        torch(false); Thread.sleep(bitPeriodMs)

        val start = SystemClock.elapsedRealtimeNanos()
        var i = 0L
        while (isTxRunning) {
            spinWait(start + i * bitPeriodNs)
            torch(i % 2L == 0L)   // ON for even indices, OFF for odd
            i++
        }

        torch(false)
        runOnUiThread {
            // Only re-enable manual TX buttons if auto-sync is not active.
            if (autoSyncState == AutoSyncState.OFF) {
                txC1Button.isEnabled   = true
                txC2Button.isEnabled   = true
                stopTxButton.isEnabled = false
            }
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposure(exposureNs: Long) {
        val ctrl = cameraControl ?: return
        Camera2CameraControl.from(ctrl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                // SENSOR_FRAME_DURATION is the authoritative frame-period field when AE is off.
                // Re-assert it (and the FPS range hint) every call: Camera2CameraControl options
                // override Camera2Interop and can be cleared by torch/session events.
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, SENSOR_FRAME_DURATION_NS)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 20))
                .build()
        )
    }

    private fun torch(on: Boolean) { cameraControl?.enableTorch(on) }

    private fun spinWait(targetNs: Long) {
        while (isTxRunning && SystemClock.elapsedRealtimeNanos() < targetNs) { /* busy-wait */ }
    }

    // ── Auto-sync state machine ────────────────────────────────────────────────

    /**
     * Called on main thread each time a stable C1/C2 classification is made during auto-sync.
     *
     * IMPORTANT: rxPhaseHandled and cancelSyncRxTimeout() are only committed AFTER the label
     * is confirmed to match.  If the wrong signal arrives (e.g. C2 during PROBING), this
     * function returns early without touching rxPhaseHandled or the RX timeout, so the RX
     * phase continues and the device does not get stuck.
     */
    private fun handleAutoSyncRxResult(label: String) {
        if (rxPhaseHandled) return

        // Determine the expected label for this RX state.  Wrong label → keep waiting.
        val expected = when (autoSyncState) {
            AutoSyncState.PROBING, AutoSyncState.WAIT_SYNC -> "C1"
            AutoSyncState.WAIT_ACK, AutoSyncState.WAIT_FINAL -> "C2"
            else -> return  // not an RX state
        }
        if (label != expected) return   // wrong signal — timeout will still fire normally

        // Correct label: commit to this RX window.
        rxPhaseHandled = true
        cancelSyncRxTimeout()

        when (autoSyncState) {
            AutoSyncState.PROBING -> {
                // Responder path: received initiator's C1.
                // Wait SYNC_RESPONSE_DELAY_MS so the initiator's TX window expires before
                // our C2 starts (classification fires ~600ms into a 2000ms window).
                syncRole = "RESP"
                handshakeDeviceState = 1
                autoSyncState = AutoSyncState.SEND_ACK
                syncHandler.postDelayed({
                    if (autoSyncState != AutoSyncState.SEND_ACK) return@postDelayed
                    startSyncTxPhase(C2_BIT_PERIOD_NS, SYNC_TX_WINDOW_MS) {
                        autoSyncState = AutoSyncState.WAIT_FINAL
                        startSyncRxPhase(SYNC_RX_WINDOW_MS) { beginProbing() }
                    }
                }, SYNC_RESPONSE_DELAY_MS)
            }
            AutoSyncState.WAIT_ACK -> {
                // Initiator path: received responder's C2 ACK.
                handshakeDeviceState = 2
                autoSyncState = AutoSyncState.SEND_FINAL
                syncHandler.postDelayed({
                    if (autoSyncState != AutoSyncState.SEND_FINAL) return@postDelayed
                    startSyncTxPhase(C2_BIT_PERIOD_NS, SYNC_TX_WINDOW_MS) {
                        autoSyncState = AutoSyncState.WAIT_SYNC
                        startSyncRxPhase(SYNC_RX_WINDOW_MS) { beginProbing() }
                    }
                }, SYNC_RESPONSE_DELAY_MS)
            }
            AutoSyncState.WAIT_FINAL -> {
                // Responder path: received initiator's final C2.
                handshakeDeviceState = 2
                autoSyncState = AutoSyncState.SEND_SYNC
                syncHandler.postDelayed({
                    if (autoSyncState != AutoSyncState.SEND_SYNC) return@postDelayed
                    startSyncTxPhase(BIT_PERIOD_NS, SYNC_BURST_MS) {
                        autoSyncState = AutoSyncState.SYNCED
                        startSyncTxPhaseForever(BIT_PERIOD_NS)
                    }
                }, SYNC_RESPONSE_DELAY_MS)
            }
            AutoSyncState.WAIT_SYNC -> {
                // Initiator path: detected responder's C1 sync signal → start sync flash.
                // No delay: SYNCED means both devices TX simultaneously.
                autoSyncState = AutoSyncState.SYNCED
                startSyncTxPhaseForever(BIT_PERIOD_NS)
            }
            else -> { /* unreachable — guarded above */ }
        }
    }

    private fun cancelSyncRxTimeout() {
        syncRxTimeoutRunnable?.let { syncHandler.removeCallbacks(it) }
        syncRxTimeoutRunnable = null
    }

    /** Switch to RX mode for [timeoutMs] ms; call [onTimeout] if no classification fires. */
    private fun startSyncRxPhase(timeoutMs: Long, onTimeout: () -> Unit) {
        rxPhaseHandled = false
        clearToIdle()
        synchronized(analyzerLock) { analyzer?.reset() }
        val r = Runnable {
            if (!rxPhaseHandled) {
                Log.v(TAG, "SYNC RX timeout in state $autoSyncState")
                onTimeout()
            }
        }
        syncRxTimeoutRunnable = r
        syncHandler.postDelayed(r, timeoutMs)
    }

    /** Start TX at [bitPeriodNs] for [durationMs] ms, then stop TX and invoke [onComplete]. */
    private fun startSyncTxPhase(bitPeriodNs: Long, durationMs: Long, onComplete: () -> Unit) {
        syncIsTxActive = true
        if (!isTxRunning) {
            isTxRunning = true
            txThread = HandlerThread("SyncTX").also { it.start() }
            txHandler = Handler(txThread!!.looper)
            txHandler!!.post { txLoop(bitPeriodNs) }
        }
        syncHandler.postDelayed({
            if (autoSyncState == AutoSyncState.OFF) return@postDelayed
            isTxRunning = false
            cameraControl?.enableTorch(false)
            txThread?.quitSafely()
            txThread = null
            txHandler = null
            syncIsTxActive = false
            clearToIdle()
            synchronized(analyzerLock) { analyzer?.reset() }
            onComplete()
        }, durationMs)
    }

    /** Start TX at [bitPeriodNs] and keep running until stopAutoSync() is called. */
    private fun startSyncTxPhaseForever(bitPeriodNs: Long) {
        syncIsTxActive = true
        if (!isTxRunning) {
            isTxRunning = true
            txThread = HandlerThread("SyncTX").also { it.start() }
            txHandler = Handler(txThread!!.looper)
            txHandler!!.post { txLoop(bitPeriodNs) }
        }
    }

    /** Random-backoff probe: randomly choose to TX C1 or listen for C1. */
    private fun beginProbing() {
        if (autoSyncState == AutoSyncState.OFF) return
        handshakeDeviceState = 0
        syncRole = "?"
        autoSyncState = AutoSyncState.PROBING
        val delay = (Math.random() * SYNC_MAX_RETRY_DELAY_MS).toLong()
        Log.v(TAG, "SYNC beginProbing: delay=${delay}ms")
        syncHandler.postDelayed({
            if (autoSyncState != AutoSyncState.PROBING) return@postDelayed
            val txFirst = Math.random() < 0.5
            if (txFirst) {
                syncRole = "INIT"
                startSyncTxPhase(BIT_PERIOD_NS, SYNC_TX_WINDOW_MS) {
                    autoSyncState = AutoSyncState.WAIT_ACK
                    startSyncRxPhase(SYNC_RX_WINDOW_MS) { beginProbing() }
                }
            } else {
                // Stay in RX — handleAutoSyncRxResult() fires when C1 is detected
                startSyncRxPhase(SYNC_RX_WINDOW_MS) { beginProbing() }
            }
        }, delay)
    }

    private fun startAutoSync() {
        if (autoSyncState != AutoSyncState.OFF) return
        stopTx()                          // cancel any manual TX in progress
        resetRx()
        autoSyncState = AutoSyncState.PROBING
        autoSyncButton.isEnabled = false
        stopSyncButton.isEnabled = true
        beginProbing()
    }

    private fun stopAutoSync() {
        cancelSyncRxTimeout()
        syncHandler.removeCallbacksAndMessages(null)
        isTxRunning = false
        cameraControl?.enableTorch(false)
        txThread?.quitSafely()
        txThread = null
        txHandler = null
        syncIsTxActive = false
        autoSyncState = AutoSyncState.OFF
        handshakeDeviceState = 0
        syncRole = "?"
        resetRx()
        autoSyncButton.isEnabled = true
        stopSyncButton.isEnabled = false
    }
}
