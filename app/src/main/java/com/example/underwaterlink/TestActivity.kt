package com.example.underwaterlink

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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

/**
 * TestActivity — primary activity for UnderwaterLink.
 *
 * Wires together:
 *  - [GridAnalyzer] (camera frame → bitVote)
 *  - [RxBitDecoder] (bitVote → decoded bits)
 *  - [ProtocolFsm]  (decoded bits → protocol state machine)
 *  - TX engine      ([executeTorchSequence] running a [SignalProtocol.TorchStep] list)
 *
 * Architecture: no MVVM, no DI — all logic lives here per project constraints.
 */
class TestActivity : AppCompatActivity() {

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "UnderwaterTest"

        // --- Camera / AE ---
        /** Starting exposure before AE loop converges. */
        private const val INITIAL_EXPOSURE_NS      = 16_000_000L   // 16ms
        /** Fixed ISO (no auto-ISO — AE is off). */
        private const val MANUAL_ISO               = 800
        /** AE target: mean luma across all grids should converge here. */
        private const val AE_TARGET_BRIGHTNESS     = 0.40f
        /** AE recalculates every this many frames (~1.5s at 20fps). */
        private const val AE_UPDATE_FRAMES         = 30
        /** EMA smoothing alpha for brightness tracking (~1s time constant). */
        private const val AE_EMA_ALPHA             = 0.05f
        /** Maximum fractional exposure change per AE update step. */
        private const val AE_MAX_STEP              = 0.15f
        /** Minimum exposure floor — below this the sensor is unreliable. */
        private const val AE_MIN_EXPOSURE_NS       = 4_000_000L    // 4ms
        /**
         * Maximum exposure ceiling = one frame period.
         * Exposures longer than 50ms physically prevent 20fps regardless of FPS hints.
         */
        private const val AE_MAX_EXPOSURE_NS       = 50_000_000L   // 50ms = 1/20fps
        /**
         * Authoritative frame duration for AE_MODE_OFF.
         * CONTROL_AE_TARGET_FPS_RANGE is only a hint when AE is off; this field locks
         * the frame period. Must be set in both Camera2Interop AND every applyExposure() call.
         */
        private const val SENSOR_FRAME_DURATION_NS = 50_000_000L   // 50ms = 20fps

        // --- RX ---
        /** Schmitt-trigger threshold; |bitVote| must exceed this to count as definitive. */
        private const val VOTE_THRESH = 0.25f

        // --- UI ---
        private val ZOOM_OPTIONS   = listOf("1x", "2x", "4x")
        private val SIGNAL_OPTIONS = listOf("C1", "C2", "C3", "R", "S", "E", "Q:no", "Packet")

        // --- Log ---
        /** Maximum number of event log lines retained in memory. */
        private const val MAX_LOG_LINES = 50
    }

    // ── Protocol objects ──────────────────────────────────────────────────────

    /** Application-level FSM; null when protocol is not running. */
    private var protocolFsm: ProtocolFsm? = null

    /** ON-duration bit decoder; fed every frame that is not a TX phase. */
    private var rxBitDecoder: RxBitDecoder? = null

    /**
     * True while a TX sequence is executing.
     * Freezes the histogram (skips [GridAnalyzer.processFrame]) during TX so that
     * our own torch flashes do not corrupt the Otsu bimodal distribution.
     * Written from main thread; read from camera executor thread.
     */
    @Volatile private var syncIsTxActive = false

    /** Message text taken from [syncMessageInput] when the protocol starts. */
    private var protocolMessage: String = ""

    // ── TX engine ─────────────────────────────────────────────────────────────

    /** True while a TX thread is spinning. Written from main thread and TX thread. */
    @Volatile private var isTxRunning = false

    private var txThread: HandlerThread? = null
    private var txHandler: Handler?      = null

    // ── Camera ────────────────────────────────────────────────────────────────

    private var boundCamera: Camera?    = null
    @Volatile private var cameraControl: CameraControl? = null

    /** Single-thread executor for CameraX image analysis callbacks. */
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ── GridAnalyzer ──────────────────────────────────────────────────────────

    @Volatile private var analyzer: GridAnalyzer? = null
    private var currentAlpha      = 0.05f
    private var currentConfThresh = 0.15f
    private var currentGridSize   = 8
    private var currentWindowN    = 30
    private var useWindowMode     = false

    /** Guards concurrent read/write of [analyzer] between UI thread and camera executor. */
    private val analyzerLock = Any()

    // ── Slow software AE ──────────────────────────────────────────────────────

    /** Current exposure in nanoseconds. Written from camera executor; read for display. */
    @Volatile private var aeExposureNs  = INITIAL_EXPOSURE_NS
    /** EMA of mean per-frame brightness; negative until the first frame. */
    private var aeBrightnessEma         = -1f
    /** Frame counter; AE update fires when this reaches a multiple of [AE_UPDATE_FRAMES]. */
    private var aeFrameCount            = 0

    // ── FPS tracking ──────────────────────────────────────────────────────────

    private var lastFrameNs = 0L
    private var smoothFps   = 20f

    // ── Zoom ──────────────────────────────────────────────────────────────────

    /**
     * Current zoom ratio applied via [CameraControl.setZoomRatio].
     * Applies to both Preview and ImageAnalysis — no crop needed.
     */
    private var currentZoomRatio = 1f

    // ── Logs ──────────────────────────────────────────────────────────────────

    private val signalLog = ArrayDeque<String>()
    private val eventLog  = ArrayDeque<String>()

    // ── Views ─────────────────────────────────────────────────────────────────

    private lateinit var previewView:         PreviewView
    private lateinit var debugOverlay:        DebugOverlayView
    private lateinit var testStateText:       TextView
    private lateinit var syncStateText:       TextView
    private lateinit var testStatsText:       TextView
    private lateinit var decodedWindowText:   TextView
    private lateinit var signalLogText:       TextView
    private lateinit var eventLogText:        TextView
    private lateinit var rightScrollView:     android.widget.ScrollView
    private lateinit var eventLogScrollView:  android.widget.ScrollView
    private lateinit var zoomSpinner:         Spinner
    private lateinit var signalSpinner:       Spinner
    private lateinit var qPacketNoInput:      EditText
    private lateinit var packetMessageInput:  EditText
    private lateinit var transmitSignalButton: Button
    private lateinit var stopTxButton:        Button
    private lateinit var syncMessageInput:    EditText
    private lateinit var modeToggle:          ToggleButton
    private lateinit var alphaParamLabel:     TextView
    private lateinit var alphaParamInput:     EditText
    private lateinit var confParamInput:      EditText
    private lateinit var gridParamInput:      EditText
    private lateinit var autoSyncButton:      Button
    private lateinit var stopSyncButton:      Button

    // ── Permission launcher ───────────────────────────────────────────────────

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

        // Legacy char-code table — kept for backward compatibility
        CharCode.load(this)

        bindViews()
        createRxBitDecoder()
        setupSignalSelector()
        setupZoomSelector()
        setupParameterInputs()
        setupButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        protocolFsm?.stop()
        protocolFsm = null
        stopCurrentTx()
        cameraExecutor.shutdown()
        cameraControl?.enableTorch(false)
        boundCamera = null
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        previewView          = findViewById(R.id.testPreviewView)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType           = PreviewView.ScaleType.FIT_CENTER
        debugOverlay         = findViewById(R.id.testDebugOverlay)
        testStateText        = findViewById(R.id.testStateText)
        syncStateText        = findViewById(R.id.syncStateText)
        testStatsText        = findViewById(R.id.testStatsText)
        decodedWindowText    = findViewById(R.id.testDecodedWindowText)
        signalLogText        = findViewById(R.id.signalLogText)
        eventLogText         = findViewById(R.id.eventLogText)
        rightScrollView      = findViewById(R.id.rightScrollView)
        eventLogScrollView   = findViewById(R.id.eventLogScrollView)
        zoomSpinner          = findViewById(R.id.zoomSpinner)
        signalSpinner        = findViewById(R.id.signalSpinner)
        qPacketNoInput       = findViewById(R.id.qPacketNoInput)
        packetMessageInput   = findViewById(R.id.packetMessageInput)
        transmitSignalButton = findViewById(R.id.testTransmitSignalButton)
        stopTxButton         = findViewById(R.id.testStopTxButton)
        syncMessageInput     = findViewById(R.id.syncMessageInput)
        modeToggle           = findViewById(R.id.modeToggle)
        alphaParamLabel      = findViewById(R.id.alphaParamLabel)
        alphaParamInput      = findViewById(R.id.alphaParamInput)
        confParamInput       = findViewById(R.id.confParamInput)
        gridParamInput       = findViewById(R.id.gridParamInput)
        autoSyncButton       = findViewById(R.id.autoSyncButton)
        stopSyncButton       = findViewById(R.id.stopSyncButton)
    }

    // ── RxBitDecoder setup ────────────────────────────────────────────────────

    /**
     * Create the RX bit decoder.
     *
     * Callbacks from [RxBitDecoder] arrive on the camera executor thread; any UI
     * updates are dispatched to the main thread by the FSM or by the decoder itself.
     */
    private fun createRxBitDecoder() {
        rxBitDecoder = RxBitDecoder(
            voteThreshold = VOTE_THRESH,
            onBitDecoded  = { bit ->
                // Forward to FSM; FSM dispatches state mutations to main thread internally.
                protocolFsm?.onBitReceived(bit)
                // Update the last-signal display for the simple signal type case.
                val label = when (bit.type) {
                    SignalProtocol.BitType.T1 -> "T1 bit ${bit.value}"
                    SignalProtocol.BitType.T2 -> "T2 bit ${bit.value}"
                    else                      -> null
                }
                if (label != null) runOnUiThread { testStatsText.text = label }
            },
            onSignalLost  = {
                // RxBitDecoder already called reset() on itself before firing this.
                runOnUiThread { appendLog("RX: signal lost") }
            },
            onDebugEvent  = { msg ->
                runOnUiThread { appendLog(msg) }
            }
        )
    }

    // ── ProtocolFsm factory ───────────────────────────────────────────────────

    /**
     * Build a fresh [ProtocolFsm] wired to the TX engine and UI callbacks.
     *
     * All state-change callbacks must run on the main thread; FSM guarantees this
     * for its own [ProtocolFsm.onBitReceived] path, but [onStartTx] / [onStartRx] /
     * [onStopRx] are called from the main thread already (scheduled via mainHandler).
     */
    private fun createProtocolFsm(): ProtocolFsm {
        return ProtocolFsm(
            onStartTx = { steps, onComplete ->
                executeTorchSequence(steps, onComplete)
            },
            onStartRx = {
                // Unfreeze the histogram so the camera executor starts feeding RxBitDecoder.
                syncIsTxActive = false
                rxBitDecoder?.reset()
            },
            onStopRx = {
                // Freeze the histogram while the partner listens (our TX is coming).
                syncIsTxActive = true
            },
            onStateChanged = { state, role, info ->
                runOnUiThread {
                    testStateText.text = state.name
                    syncStateText.text = role.name
                    appendLog("STATE: $state | $role | $info")
                }
            },
            onPacketReceived = { packetNo, chars, crcOk ->
                runOnUiThread {
                    val status = if (crcOk) "OK" else "CRC_FAIL"
                    appendLog("PKT[$packetNo]: '$chars' $status")
                    if (crcOk) {
                        val current = decodedWindowText.text.toString()
                        val updated = if (current == "(none)") chars else current + chars
                        decodedWindowText.text = updated.takeLast(80)
                    }
                }
            },
            onMessageComplete = { fullMessage ->
                runOnUiThread {
                    appendLog("MSG COMPLETE: '$fullMessage'")
                    testStatsText.text = fullMessage.take(40)
                    decodedWindowText.text = fullMessage
                }
            },
            onLogEvent = { msg ->
                runOnUiThread { appendLog(msg) }
            },
            onSignalDecoded = { signal ->
                runOnUiThread { appendSignalLog(signal) }
            }
        )
    }

    // ── Signal spinner ────────────────────────────────────────────────────────

    /** Wire the signal spinner and toggle visibility of the auxiliary input fields. */
    private fun setupSignalSelector() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, SIGNAL_OPTIONS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        signalSpinner.adapter = adapter
        signalSpinner.setSelection(0, false)

        signalSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val selected = SIGNAL_OPTIONS[position]
                qPacketNoInput.visibility     = if (selected == "Q:no")   android.view.View.VISIBLE else android.view.View.GONE
                packetMessageInput.visibility = if (selected == "Packet") android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ── Zoom selector ─────────────────────────────────────────────────────────

    /** Wire the zoom spinner to [applyZoomRatio]. */
    private fun setupZoomSelector() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ZOOM_OPTIONS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        zoomSpinner.adapter = adapter
        zoomSpinner.setSelection(0, false)

        zoomSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                val requestedZoom = when (ZOOM_OPTIONS[position]) {
                    "2x"  -> 2f
                    "4x"  -> 4f
                    else  -> 1f
                }
                applyZoomRatio(requestedZoom)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /**
     * Apply [requestedRatio] to the camera, clamping to the hardware-reported zoom range.
     * Also resets the analyzer so the new field-of-view is not mixed with previous histogram data.
     */
    private fun applyZoomRatio(requestedRatio: Float) {
        currentZoomRatio = requestedRatio
        val camera    = boundCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value
        val minZoom   = zoomState?.minZoomRatio
        val maxZoom   = zoomState?.maxZoomRatio
        val clamped = when {
            minZoom != null && maxZoom != null -> requestedRatio.coerceIn(minZoom, maxZoom)
            minZoom != null                    -> requestedRatio.coerceAtLeast(minZoom)
            maxZoom != null                    -> requestedRatio.coerceAtMost(maxZoom)
            else                               -> requestedRatio
        }
        currentZoomRatio = clamped
        camera.cameraControl.setZoomRatio(clamped)
        synchronized(analyzerLock) { analyzer?.reset() }
        resetRx()
    }

    // ── Parameter inputs ──────────────────────────────────────────────────────

    /** Wire the alpha/N toggle and the three numeric EditTexts. */
    private fun setupParameterInputs() {
        syncMessageInput.filters = arrayOf(InputFilter.LengthFilter(80))

        // Mode toggle: alpha-decay ↔ rolling-window
        modeToggle.setOnCheckedChangeListener { _, isChecked ->
            useWindowMode = isChecked
            synchronized(analyzerLock) { analyzer?.useRollingWindow = isChecked }
            if (isChecked) {
                alphaParamLabel.text = "Window N"
                alphaParamInput.setText(currentWindowN.toString())
                synchronized(analyzerLock) { analyzer?.windowSize = currentWindowN }
            } else {
                alphaParamLabel.text = "Alpha"
                alphaParamInput.setText(String.format("%.3f", currentAlpha))
                synchronized(analyzerLock) { analyzer?.alpha = currentAlpha }
            }
        }

        // Populate initial values
        alphaParamLabel.text = "Alpha"
        alphaParamInput.setText(String.format("%.3f", currentAlpha))
        confParamInput.setText(String.format("%.3f", currentConfThresh))
        gridParamInput.setText(currentGridSize.toString())

        // Alpha / Window-N input
        alphaParamInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isEmpty()) return
                if (useWindowMode) {
                    val n = value.toIntOrNull() ?: return
                    if (n <= 0) return
                    currentWindowN = n
                    synchronized(analyzerLock) { analyzer?.windowSize = currentWindowN }
                } else {
                    val alpha = value.toFloatOrNull() ?: return
                    if (alpha <= 0f) return
                    currentAlpha = alpha
                    synchronized(analyzerLock) { analyzer?.alpha = currentAlpha }
                }
            }
        })

        // Confidence threshold input
        confParamInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isEmpty()) return
                val conf = value.toFloatOrNull() ?: return
                currentConfThresh = conf.coerceIn(0f, 1f)
                synchronized(analyzerLock) { analyzer?.confidenceThreshold = currentConfThresh }
            }
        })

        // Grid size input — requires analyzer recreation
        gridParamInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isEmpty()) return
                val grid = value.toIntOrNull() ?: return
                if (grid <= 0 || grid == currentGridSize) return
                currentGridSize = grid
                // Null the analyzer — lazy init in the camera loop will recreate it.
                synchronized(analyzerLock) { analyzer = null }
                resetRx()
            }
        })
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private fun setupButtons() {
        transmitSignalButton.setOnClickListener { transmitSelectedSignal() }
        stopTxButton.setOnClickListener         { stopCurrentTx(); stopTxButton.isEnabled = false; transmitSignalButton.isEnabled = true }
        findViewById<Button>(R.id.testResetRxButton).setOnClickListener { resetRx() }
        autoSyncButton.setOnClickListener       { startProtocol() }
        stopSyncButton.setOnClickListener       { stopProtocol() }
        stopSyncButton.isEnabled = false
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    /**
     * Bind CameraX preview + image analysis with Camera2Interop for manual AE.
     *
     * CRITICAL configuration rules:
     * - [CaptureRequest.CONTROL_AE_MODE] must be OFF so the HAL never auto-compensates
     *   for our torch flashes (AE reacts in 1–3 frames → destroys bitVote).
     * - [CaptureRequest.SENSOR_FRAME_DURATION] is the authoritative 20fps lock when AE is off;
     *   it must be in both this Camera2Interop block AND every [applyExposure] call.
     * - Use [CameraControl.enableTorch], NOT CameraManager.setTorchMode (silently fails
     *   when CameraX owns the session).
     */
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
            // for torch flashes. Our slow software AE loop takes over from here.
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 20))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, aeExposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, SENSOR_FRAME_DURATION_NS)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, MANUAL_ISO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

            val analysis = analysisBuilder.build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy: ImageProxy ->
                processImageFrame(imageProxy)
            }

            try {
                provider.unbindAll()
                boundCamera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                cameraControl = boundCamera?.cameraControl
                applyZoomRatio(currentZoomRatio)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
                boundCamera = null
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Process a single camera frame from the CameraX analysis executor.
     *
     * During TX ([syncIsTxActive] == true): skip the histogram and RX decoder,
     * but still run the AE loop to keep the FPS lock asserted.
     *
     * During RX ([syncIsTxActive] == false): run GridAnalyzer, feed RxBitDecoder,
     * run the slow AE loop, and push a UI update.
     */
    private fun processImageFrame(imageProxy: ImageProxy) {
        val plane     = imageProxy.planes[0]
        val buffer    = plane.buffer
        val rowStride = plane.rowStride
        val frameNs   = imageProxy.imageInfo.timestamp

        // Lazy init — always runs so analyzer is ready the first frame RX starts.
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

        // FPS tracking (exponential moving average)
        if (lastFrameNs > 0) {
            val inst = 1_000_000_000f / (frameNs - lastFrameNs).toFloat()
            smoothFps = smoothFps * 0.85f + inst * 0.15f
        }
        lastFrameNs = frameNs

        if (!syncIsTxActive) {
            // ── RX phase ────────────────────────────────────────────────────
            val result = synchronized(analyzerLock) {
                analyzer!!.processFrame(buffer, rowStride)
            }

            // Feed bit decoder on this same executor thread (required by RxBitDecoder contract).
            rxBitDecoder?.processFrame(result.bitVote, frameNs)

            // ── Slow software AE ────────────────────────────────────────────
            // Use the mean brightness across ALL grids — the LED covers only a few grids
            // so its flashing barely moves the whole-frame mean (no masking needed).
            val meanBrightness = result.brightness.average().toFloat()
            aeBrightnessEma = if (aeBrightnessEma < 0f) meanBrightness
                              else aeBrightnessEma * (1f - AE_EMA_ALPHA) + meanBrightness * AE_EMA_ALPHA
            aeFrameCount++
            if (aeFrameCount % AE_UPDATE_FRAMES == 0) {
                val error  = AE_TARGET_BRIGHTNESS - aeBrightnessEma
                val step   = (error * 0.5f).coerceIn(-AE_MAX_STEP, AE_MAX_STEP)
                val newExp = (aeExposureNs * (1f + step)).toLong()
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

            // ── UI update ───────────────────────────────────────────────────
            runOnUiThread {
                debugOverlay.update(
                    DebugOverlayView.DebugData(
                        result        = result,
                        state         = protocolFsm?.state?.name ?: "OFF",
                        alpha         = currentAlpha,
                        confThreshold = currentConfThresh,
                        fps           = smoothFps,
                        bitVote       = result.bitVote
                    )
                )
            }

        } else {
            // ── TX phase: histogram frozen, camera preview still runs ────────
            // Re-assert FPS lock every AE_UPDATE_FRAMES frames — the AE loop above
            // is skipped during TX, so nothing else is calling applyExposure().
            aeFrameCount++
            if (aeFrameCount % AE_UPDATE_FRAMES == 0) {
                applyExposure(aeExposureNs)
            }
            runOnUiThread {
                val fsmState = protocolFsm?.state?.name ?: "OFF"
                testStateText.text = fsmState
            }
        }

        imageProxy.close()
    }

    // ── TX engine ─────────────────────────────────────────────────────────────

    /**
     * Execute a [SignalProtocol.TorchStep] list on a dedicated HandlerThread.
     *
     * Sets [syncIsTxActive] before starting so the camera executor skips the histogram.
     * Calls [onComplete] on the main thread after the last step and the torch is off.
     * If TX is already running, the request is dropped and logged.
     */
    private fun executeTorchSequence(
        steps: List<SignalProtocol.TorchStep>,
        onComplete: () -> Unit
    ) {
        if (isTxRunning) {
            appendLog("TX: already running, ignoring new sequence")
            return
        }
        isTxRunning    = true
        syncIsTxActive = true

        txThread  = HandlerThread("TorchTX").also { it.start() }
        txHandler = Handler(txThread!!.looper)
        txHandler!!.post {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val start    = SystemClock.elapsedRealtimeNanos()
            var deadline = start
            for (step in steps) {
                if (!isTxRunning) break
                torch(step.isOn)
                deadline += step.durationNs
                spinWait(deadline)
            }
            torch(false)
            isTxRunning = false
            txThread?.quitSafely()
            txThread  = null
            txHandler = null
            runOnUiThread {
                syncIsTxActive = false
                onComplete()
            }
        }
    }

    /**
     * Immediately abort any in-progress TX sequence.
     *
     * Sets [isTxRunning] = false so the spin-wait exits on the next iteration,
     * turns off the torch, and clears the thread references.
     */
    private fun stopCurrentTx() {
        isTxRunning    = false
        syncIsTxActive = false
        cameraControl?.enableTorch(false)
        txThread?.quitSafely()
        txThread  = null
        txHandler = null
    }

    // ── Manual TX ─────────────────────────────────────────────────────────────

    /** Build and execute the TX sequence for the currently selected signal. */
    private fun transmitSelectedSignal() {
        if (isTxRunning) return
        val signal = signalSpinner.selectedItem?.toString() ?: return

        val steps: List<SignalProtocol.TorchStep> = when (signal) {
            "C1"     -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C1)
            "C2"     -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C2)
            "C3"     -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.C3)
            "R"      -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.R)
            "S"      -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.S)
            "E"      -> SignalProtocol.encodeConstCode(SignalProtocol.ConstCode.E)
            "Q:no"   -> {
                val no = qPacketNoInput.text.toString().toIntOrNull()?.coerceIn(0, 15) ?: 0
                SignalProtocol.encodeQNo(no)
            }
            "Packet" -> {
                val msg = packetMessageInput.text.toString().take(5)
                SignalProtocol.encodePacket(msg)
            }
            else -> return
        }

        appendLog("MANUAL TX: $signal")
        transmitSignalButton.isEnabled = false
        stopTxButton.isEnabled         = true

        executeTorchSequence(steps) {
            appendLog("MANUAL TX done: $signal")
            transmitSignalButton.isEnabled = true
            stopTxButton.isEnabled         = false
        }
    }

    // ── Protocol lifecycle ────────────────────────────────────────────────────

    /**
     * Start the [ProtocolFsm] from INITIAL state.
     *
     * Cancels any in-progress manual TX and resets the RX decoder before handing
     * control to the FSM.
     */
    private fun startProtocol() {
        if (protocolFsm != null) return
        stopCurrentTx()
        resetRx()
        protocolMessage = syncMessageInput.text.toString().take(80)
        appendLog("PROTOCOL start, message='$protocolMessage'")
        protocolFsm = createProtocolFsm()
        protocolFsm?.start(protocolMessage)
        autoSyncButton.isEnabled       = false
        stopSyncButton.isEnabled       = true
        transmitSignalButton.isEnabled = false
    }

    /**
     * Stop the [ProtocolFsm] and return to idle.
     *
     * The FSM's own [ProtocolFsm.stop] cancels all pending timeouts; we then
     * also stop the TX engine and reset the RX decoder for a clean slate.
     */
    private fun stopProtocol() {
        protocolFsm?.stop()
        protocolFsm = null
        stopCurrentTx()
        resetRx()
        syncIsTxActive = false
        appendLog("PROTOCOL stopped")
        autoSyncButton.isEnabled       = true
        stopSyncButton.isEnabled       = false
        transmitSignalButton.isEnabled = true
        testStateText.text = "OFF"
        syncStateText.text = "—"
    }

    // ── RX reset ──────────────────────────────────────────────────────────────

    /**
     * Reset the RX decoder and the GridAnalyzer histogram.
     *
     * Called on zoom change, grid size change, and manual Reset RX button.
     */
    private fun resetRx() {
        rxBitDecoder?.reset()
        synchronized(analyzerLock) { analyzer?.reset() }
        runOnUiThread {
            testStatsText.text     = "(none)"
            decodedWindowText.text = "(none)"
            appendLog("RX reset")
        }
    }

    // ── Camera helpers ────────────────────────────────────────────────────────

    /**
     * Push updated capture request options to the camera.
     *
     * CRITICAL: both [CaptureRequest.SENSOR_FRAME_DURATION] and
     * [CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE] must be re-asserted here.
     * Camera2CameraControl options override Camera2Interop options and can be
     * cleared by torch/session events, causing the camera to slip from 20fps to ~10fps.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposure(exposureNs: Long) {
        val ctrl = cameraControl ?: return
        Camera2CameraControl.from(ctrl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME,   exposureNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION,  SENSOR_FRAME_DURATION_NS)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(20, 20))
                .build()
        )
    }

    /** Toggle the torch. Use this instead of CameraManager.setTorchMode (silently fails). */
    private fun torch(on: Boolean) { cameraControl?.enableTorch(on) }

    /**
     * Busy-wait spin loop for precise TX timing.
     *
     * Exits early if [isTxRunning] is cleared (e.g. by [stopCurrentTx]).
     * The TX thread runs at [Process.THREAD_PRIORITY_URGENT_AUDIO] to minimise jitter.
     */
    private fun spinWait(targetNs: Long) {
        while (isTxRunning && SystemClock.elapsedRealtimeNanos() < targetNs) { /* busy-wait */ }
    }

    // ── Logs ──────────────────────────────────────────────────────────────────

    /**
     * Append a decoded signal name to the signal log (e.g. "C1", "Q:3", "S", "E").
     * Must be called from the main thread.
     */
    private fun appendSignalLog(signal: String) {
        val ts   = System.currentTimeMillis() % 100_000
        val line = "[${ts}ms] $signal"
        signalLog.addLast(line)
        while (signalLog.size > MAX_LOG_LINES) signalLog.removeFirst()
        signalLogText.text = signalLog.joinToString("\n")
        rightScrollView.post { rightScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    /**
     * Append a timestamped line to the event log.
     * Must be called from the main thread. Capped at [MAX_LOG_LINES] entries.
     */
    private fun appendLog(msg: String) {
        val ts   = System.currentTimeMillis() % 100_000
        val line = "[${ts}ms] $msg"
        eventLog.addLast(line)
        while (eventLog.size > MAX_LOG_LINES) eventLog.removeFirst()
        eventLogText.text = eventLog.joinToString("\n")
        eventLogScrollView.post { eventLogScrollView.fullScroll(android.view.View.FOCUS_DOWN) }
    }
}
