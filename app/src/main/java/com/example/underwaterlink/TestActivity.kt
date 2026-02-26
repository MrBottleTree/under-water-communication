package com.example.underwaterlink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        // C1 uses 100 ms/bit, C2 uses 300 ms/bit — 3× slower.
        // Classification is by average inter-edge interval, not edge count, so it's robust
        // even when some edges are missed at distance.
        private const val BIT_PERIOD_NS     = 100_000_000L   // 100 ms per bit (C1 data, all preamble)
        private const val C2_BIT_PERIOD_NS  = 300_000_000L   // 300 ms per bit (C2 data only)
        private const val VOTE_THRESH       = 0.25f           // min |bitVote| to count as signal

        // RX break detection
        // Alternating data never produces more than ~2 consecutive dark frames at 20 fps.
        // BREAK_DARK_FRAMES = 5 is unambiguous: only a real inter-phase gap can do this.
        private const val BREAK_DARK_FRAMES   = 5
        // How many consecutive signal frames (|bitVote| > threshold) to leave IDLE
        private const val PREAMBLE_MIN_FRAMES = 4

        // TX: protocol uses ONLY alternating bits — no sustained ON/OFF.
        //   [preamble alt bits] ──500ms break──> [data alt bits] ──500ms break──> repeat
        private const val TX_PREAMBLE_BITS = 12     // 1.2 s  – builds bimodal histogram (at 100ms/bit)
        private const val TX_BREAK_MS      = 500L   // 0.5 s  – long enough for 400ms edge timeout to fire
        private const val TX_ALT_BITS_C1   = 10     // 10 edges × 100 ms = 1.0 s
        private const val TX_ALT_BITS_C2   = 10     // 10 edges × 300 ms = 3.0 s

        // Classification threshold: C1 avg interval ≈ 100ms, C2 ≈ 300ms, threshold at midpoint.
        private const val C1_C2_THRESHOLD_NS = 200_000_000L  // 200 ms

        // Edge-timeout: no edge for this long → data burst ended.
        // Must be > C2_BIT_PERIOD_NS (300ms) so C2 edges don't time out early,
        // and < TX_BREAK_MS (500ms) so it fires before the next cycle.
        private const val EDGE_TIMEOUT_NS  = 400_000_000L
    }

    // ── RX state machine ──────────────────────────────────────────────────────
    //
    // Protocol:  [preamble alt-bits] ──300ms break──> [data alt-bits] ──300ms break──> repeat
    //
    // States:
    //  IDLE       – waiting for any signal to appear
    //  PREAMBLE   – signal detected, waiting for the preamble→data break
    //  DATA_WAIT  – in the break, waiting for the first data edge
    //  RECEIVING  – counting data edges; edge timeout ends the burst

    private enum class RxState { IDLE, PREAMBLE, DATA_WAIT, RECEIVING }

    @Volatile private var rxState     = RxState.IDLE
    private var consecutiveBright     = 0   // signal frames in IDLE (|bitVote| > thresh)
    private var consecutiveDark       = 0   // consecutive dark frames (break detection)

    // Edge-based decoding state
    private var currentSign           = 0   // last committed sign: +1=bright, -1=dark, 0=none
    private var lastEdgeNs            = 0L  // timestamp of the most recent detected edge
    private var dataEdges             = 0   // edges decoded in the current data burst
    private var edgeIntervalTotal     = 0L  // sum of inter-edge intervals (ns)
    private var edgeIntervalCount     = 0   // number of intervals measured (= dataEdges - 1)

    // BER / display
    private val rxBits                = StringBuilder()
    private val exBits                = StringBuilder()
    private var errorCount            = 0
    private var totalBits             = 0
    private var lastReceivedMsg       = ""

    // ── GridAnalyzer (lazy-initialised on first camera frame) ─────────────────

    @Volatile private var analyzer: GridAnalyzer? = null
    private var currentAlpha      = 0.05f
    private var currentConfThresh = 0.15f
    private var currentGridSize   = 8       // must match default in GridAnalyzer
    private var currentWindowN    = 30      // rolling-window frame count
    private var useWindowMode     = false   // mirrors modeToggle.isChecked; safe to read on any thread
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
        // ── Mode toggle: α-decay  ↔  rolling-window N frames ─────────────────
        //
        // The same SeekBar is reused for both modes:
        //   α-decay  mode: progress 0–999  →  alpha  = (p+1)/1000  (0.001 – 1.000)
        //   N-window mode: progress 0–999  →  N      = p+1         (1 – 1000 frames)
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            useWindowMode = isChecked
            if (isChecked) {
                // Switch to N-window: re-range slider to 1–1000
                alphaSeekBar.max      = 999
                alphaSeekBar.progress = (currentWindowN - 1).coerceIn(0, 999)
                alphaValueText.text   = "N=${currentWindowN}"
                synchronized(analyzerLock) {
                    analyzer?.useRollingWindow = true
                    analyzer?.windowSize       = currentWindowN
                }
            } else {
                // Switch back to α-decay
                alphaSeekBar.max      = 999
                alphaSeekBar.progress = (currentAlpha * 1000f - 1f).toInt().coerceIn(0, 999)
                alphaValueText.text   = "α=${String.format("%.3f", currentAlpha)}"
                synchronized(analyzerLock) {
                    analyzer?.useRollingWindow = false
                }
            }
        }

        // Dual-purpose SeekBar (starts in α-decay mode)
        alphaSeekBar.max      = 999
        alphaSeekBar.progress = 49           // default α = 0.050
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

        // Conf threshold: progress 0–100  →  conf = p / 100  →  0.00 to 1.00
        confSeekBar.max      = 100
        confSeekBar.progress = 15            // default 0.150
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

        // Grid size: progress 0–3  →  gridSize = 4 << p  →  4, 8, 16, 32 px
        gridSeekBar.max      = 3
        gridSeekBar.progress = 1             // default 8 px
        gridValueText.text   = "8px"
        gridSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                currentGridSize    = 4 shl p
                gridValueText.text = "${currentGridSize}px"
                synchronized(analyzerLock) { analyzer = null }   // re-init on next frame
                resetRx()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        txC1Button.setOnClickListener  { startTx(TX_ALT_BITS_C1, BIT_PERIOD_NS) }
        txC2Button.setOnClickListener  { startTx(TX_ALT_BITS_C2, C2_BIT_PERIOD_NS) }
        stopTxButton.setOnClickListener { stopTx() }
        findViewById<Button>(R.id.testResetRxButton).setOnClickListener { resetRx() }
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build()
            preview.surfaceProvider = previewView.surfaceProvider

            val analysis = ImageAnalysis.Builder()
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
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                val plane     = imageProxy.planes[0]
                val buffer    = plane.buffer
                val rowStride = plane.rowStride
                val frameNs   = imageProxy.imageInfo.timestamp

                // Lazy-initialise (or re-init after grid-size change) with actual resolution
                if (analyzer == null) {
                    val w = imageProxy.width
                    val h = imageProxy.height
                    synchronized(analyzerLock) {
                        if (analyzer == null) {
                            analyzer = GridAnalyzer(
                                imageWidth = w,
                                imageHeight = h,
                                gridSize = currentGridSize,
                                alpha = currentAlpha,
                                confidenceThreshold = currentConfThresh
                            ).also { a ->
                                // Restore window mode if it was active before re-init
                                if (useWindowMode) {
                                    a.useRollingWindow = true
                                    a.windowSize       = currentWindowN
                                }
                            }
                        }
                    }
                    runOnUiThread { debugOverlay.setImageDimensions(w, h) }
                }

                // Smooth FPS
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
                    val avgMs = if (edgeIntervalCount > 0) edgeIntervalTotal / edgeIntervalCount / 1_000_000L else 0L
                    stateText.text = "RX: ${rxState.name}  e:$dataEdges avg:${avgMs}ms"
                    statsText.text = "ERR $errorCount/$totalBits"
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
        val isBright = result.bitVote > VOTE_THRESH
        val isDark   = result.bitVote < -VOTE_THRESH

        when (rxState) {

            // ── IDLE: wait for any alternating signal to appear ────────────────────
            RxState.IDLE -> {
                if (isBright || isDark) consecutiveBright++ else consecutiveBright = 0
                if (consecutiveBright >= PREAMBLE_MIN_FRAMES) {
                    rxState = RxState.PREAMBLE
                    consecutiveBright = 0; consecutiveDark = 0
                    currentSign = if (isBright) 1 else -1
                }
            }

            // ── PREAMBLE: preamble running, wait for the break ─────────────────────
            // A break = BREAK_DARK_FRAMES consecutive dark frames.
            // Alternating data never produces more than 2 consecutive dark frames at 20 fps.
            RxState.PREAMBLE -> {
                if (isDark) consecutiveDark++ else consecutiveDark = 0
                if (consecutiveDark >= BREAK_DARK_FRAMES) {
                    rxState = RxState.DATA_WAIT
                    consecutiveDark = 0
                    currentSign = -1   // we know we are dark after the break
                }
            }

            // ── DATA_WAIT: inside the break, wait for the first data edge ──────────
            RxState.DATA_WAIT -> {
                if (isBright) {
                    // Rising edge → bit 0 = 1 (data always starts bright)
                    currentSign = 1
                    lastEdgeNs  = frameNs
                    dataEdges   = 1
                    totalBits++
                    // bit 0 should be 1 (rising) → no error
                    rxBits.append(1); exBits.append(1)
                    if (rxBits.length > 80) { rxBits.deleteCharAt(0); exBits.deleteCharAt(0) }
                    rxState = RxState.RECEIVING
                }
            }

            // ── RECEIVING: edge-detect every transition, timeout = end-of-data ─────
            //
            // Classification is by average inter-edge interval, not edge count:
            //   C1 sends at 100 ms/bit → avg interval ≈ 100 ms
            //   C2 sends at 300 ms/bit → avg interval ≈ 300 ms
            //   Threshold at 200 ms gives a 2× safety margin on each side.
            // This is robust even if some edges are missed at distance.
            RxState.RECEIVING -> {
                // End-of-data detection: no edge for > 400 ms
                if (lastEdgeNs > 0 && frameNs - lastEdgeNs > EDGE_TIMEOUT_NS) {
                    announceReceivedMessage()
                    rxState = RxState.IDLE
                    consecutiveBright = 0; consecutiveDark = 0
                    // Clear per-burst state so next reception starts fresh
                    currentSign = 0; lastEdgeNs = 0L; dataEdges = 0
                    edgeIntervalTotal = 0L; edgeIntervalCount = 0
                    return
                }

                // Schmitt-trigger edge detection (hysteresis via VOTE_THRESH)
                val prevSign = currentSign
                if      (isBright && currentSign != 1)  currentSign = 1
                else if (isDark   && currentSign != -1) currentSign = -1
                // If uncertain, hold last sign (no spurious edges)

                if (currentSign != prevSign && prevSign != 0) {
                    // Accumulate interval from the previous edge
                    if (lastEdgeNs > 0) {
                        edgeIntervalTotal += frameNs - lastEdgeNs
                        edgeIntervalCount++
                    }
                    lastEdgeNs = frameNs
                    val decodedBit  = if (currentSign > 0) 1 else 0
                    // alternating pattern: even-indexed edges are rising (1), odd are falling (0)
                    val expectedBit = if (dataEdges % 2 == 0) 1 else 0
                    dataEdges++
                    totalBits++
                    if (decodedBit != expectedBit) errorCount++
                    rxBits.append(decodedBit); exBits.append(expectedBit)
                    if (rxBits.length > 80) { rxBits.deleteCharAt(0); exBits.deleteCharAt(0) }
                }
            }
        }
    }

    /**
     * Called when the edge-timeout ends a burst — classify as C1 or C2.
     *
     * Uses average inter-edge interval rather than edge count.
     * C1 (100ms/bit) → avg ≈ 100ms; C2 (300ms/bit) → avg ≈ 300ms.
     * Threshold = 200ms (midpoint). Robust even when edges are missed at distance,
     * because the timing ratio is preserved: a missed C1 edge gives ~200ms interval,
     * still well below C2's ~300ms.
     */
    private fun announceReceivedMessage() {
        if (dataEdges == 0) return
        val avgIntervalMs = if (edgeIntervalCount > 0) edgeIntervalTotal / edgeIntervalCount / 1_000_000L else 0L
        lastReceivedMsg = if (avgIntervalMs < 200L) "C1 RECEIVED" else "C2 RECEIVED"
        Log.d(TAG, "$lastReceivedMsg (edges=$dataEdges, avgInterval=${avgIntervalMs}ms)")
    }

    private fun resetRx() {
        rxState = RxState.IDLE
        consecutiveBright = 0; consecutiveDark = 0
        currentSign = 0; lastEdgeNs = 0L; dataEdges = 0
        edgeIntervalTotal = 0L; edgeIntervalCount = 0
        rxBits.clear(); exBits.clear()
        errorCount = 0; totalBits = 0
        lastReceivedMsg = ""
        synchronized(analyzerLock) { analyzer?.reset() }
    }

    // ── TX (HandlerThread, timing-critical) ───────────────────────────────────

    private fun startTx(altBits: Int, dataBitPeriodNs: Long) {
        if (isTxRunning) return
        isTxRunning = true
        txC1Button.isEnabled  = false
        txC2Button.isEnabled  = false
        stopTxButton.isEnabled = true

        txThread = HandlerThread("TestTX").also { it.start() }
        txHandler = Handler(txThread!!.looper)
        txHandler!!.post { txLoop(altBits, dataBitPeriodNs) }
    }

    private fun stopTx() {
        isTxRunning = false
        txC1Button.isEnabled  = true
        txC2Button.isEnabled  = true
        stopTxButton.isEnabled = false
        cameraControl?.enableTorch(false)
        txThread?.quitSafely()
    }

    /**
     * TX loop (all alternating bits — no sustained ON/OFF):
     *   [TX_PREAMBLE_BITS alt bits @ 100ms] ──500ms break──> [altBits alt bits @ dataBitPeriodNs] ──500ms break──> repeat
     *
     * C1: dataBitPeriodNs = 100ms → edges every 100ms → RX avg interval ≈ 100ms → "C1 RECEIVED"
     * C2: dataBitPeriodNs = 300ms → edges every 300ms → RX avg interval ≈ 300ms → "C2 RECEIVED"
     * Threshold at 200ms gives a 2× safety margin, robust even if edges are missed at distance.
     *
     * Preamble always runs at 100ms/bit regardless of C1/C2, so the histogram builds up
     * at the same rate in both cases. Only the data phase uses the per-message period.
     */
    private fun txLoop(altBits: Int, dataBitPeriodNs: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Warmup: pre-init the torch HAL (first setTorchMode is always slow)
        torch(true);  Thread.sleep(50)
        torch(false); Thread.sleep(100)

        while (isTxRunning) {
            val cycleStart = SystemClock.elapsedRealtimeNanos()

            // ── Preamble: TX_PREAMBLE_BITS alternating bits at 100 ms/bit ─────────
            // Allows the receiver's bimodal histogram to build up before data arrives.
            for (i in 0..TX_PREAMBLE_BITS) {
                if (!isTxRunning) break
                spinWait(cycleStart + i * BIT_PERIOD_NS)
                torch(i % 2 == 0 && i < TX_PREAMBLE_BITS)  // OFF at i = TX_PREAMBLE_BITS
            }

            // ── Break: TX_BREAK_MS silence ────────────────────────────────────────
            // Torch is already OFF from the last preamble event.
            // RX detects ≥5 consecutive dark frames → transitions to DATA_WAIT.
            val dataStart = cycleStart + TX_PREAMBLE_BITS * BIT_PERIOD_NS + TX_BREAK_MS * 1_000_000L
            spinWait(dataStart)

            // ── Data: altBits alternating bits at dataBitPeriodNs ─────────────────
            // C1 → 100ms/bit, C2 → 300ms/bit. RX classifies by average inter-edge interval.
            for (i in 0..altBits) {
                if (!isTxRunning) break
                spinWait(dataStart + i * dataBitPeriodNs)
                torch(i % 2 == 0 && i < altBits)           // OFF at i = altBits
            }

            // ── Break at end ──────────────────────────────────────────────────────
            // Torch is already OFF. RX edge-timeout (400 ms) fires inside this 500ms gap.
            val cycleEnd = dataStart + altBits * dataBitPeriodNs + TX_BREAK_MS * 1_000_000L
            spinWait(cycleEnd)
        }

        torch(false)
        runOnUiThread {
            txC1Button.isEnabled  = true
            txC2Button.isEnabled  = true
            stopTxButton.isEnabled = false
        }
    }

    private fun torch(on: Boolean) {
        cameraControl?.enableTorch(on)
    }

    private fun spinWait(targetNs: Long) {
        while (isTxRunning && SystemClock.elapsedRealtimeNanos() < targetNs) { /* busy-wait */ }
    }
}
