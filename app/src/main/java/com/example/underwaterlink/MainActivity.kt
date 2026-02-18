package com.example.underwaterlink

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import kotlin.math.sqrt
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "UnderwaterLinkTX"
        private const val SYMBOL_PERIOD_MS = 100L     
        private const val END_MARKER_MS = 200L        
        private const val START_ON_MS = 200L          
        private const val START_OFF_MS = 100L         
        
        
        private val SYMBOL_ON_MS = longArrayOf(25, 40, 55, 70)
        private val SYMBOL_LABELS = arrayOf("00", "01", "10", "11")
    }

    
    private lateinit var messageInput: EditText
    private lateinit var transmitButton: Button
    private lateinit var stopButton: Button
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var currentBitText: TextView
    private lateinit var binaryOutput: TextView
    private lateinit var binaryScroll: ScrollView

    
    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null

    
    private var txThread: HandlerThread? = null
    private var txHandler: Handler? = null
    @Volatile private var isTransmitting = false

    
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                Log.d(TAG, "Camera permission granted")
            } else {
                Toast.makeText(this, "Camera permission is required for flashlight", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        
        messageInput = findViewById(R.id.messageInput)
        transmitButton = findViewById(R.id.transmitButton)
        stopButton = findViewById(R.id.stopButton)
        statusText = findViewById(R.id.statusText)
        rateText = findViewById(R.id.rateText)
        currentBitText = findViewById(R.id.currentBitText)
        binaryOutput = findViewById(R.id.binaryOutput)
        binaryScroll = findViewById(R.id.binaryScroll)

        
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraId = cameraManager.cameraIdList.firstOrNull()

        requestCameraPermission()

        transmitButton.setOnClickListener { startTransmission() }
        stopButton.setOnClickListener { stopTransmission() }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    
    
    private fun textToSymbols(text: String): List<Int> {
        val symbols = mutableListOf<Int>()
        for (ch in text) {
            val byte = ch.code and 0xFF
            
            symbols.add((byte shr 6) and 0x03)
            symbols.add((byte shr 4) and 0x03)
            symbols.add((byte shr 2) and 0x03)
            symbols.add(byte and 0x03)
        }
        return symbols
    }

    
    private fun checksumSymbols(text: String): List<Int> {
        var xor = 0
        for (ch in text) {
            xor = xor xor (ch.code and 0xFF)
        }
        return listOf(
            (xor shr 6) and 0x03,
            (xor shr 4) and 0x03,
            (xor shr 2) and 0x03,
            xor and 0x03
        )
    }

    
    private fun formatSymbolsForDisplay(text: String, dataSymbols: List<Int>, chkSymbols: List<Int>): String {
        val sb = StringBuilder()
        sb.appendLine("4-ary PWM @ 20 bps (${SYMBOL_PERIOD_MS}ms/sym, 2 bits/sym)")
        sb.appendLine("Min OFF gap: ${SYMBOL_PERIOD_MS - SYMBOL_ON_MS[3]}ms")
        for (i in SYMBOL_ON_MS.indices) {
            sb.appendLine("  ${SYMBOL_LABELS[i]} -> ON ${SYMBOL_ON_MS[i]}ms  OFF ${SYMBOL_PERIOD_MS - SYMBOL_ON_MS[i]}ms")
        }
        sb.appendLine()
        sb.appendLine("Protocol: START(${START_ON_MS}ms ON) -> DATA -> CHECKSUM -> END(${END_MARKER_MS}ms OFF)")
        sb.appendLine()
        sb.appendLine("ASCII Symbols")
        var idx = 0
        for (ch in text) {
            val syms = dataSymbols.subList(idx, idx + 4)
            val bits = syms.joinToString("") { SYMBOL_LABELS[it] }
            sb.appendLine("'$ch' (${ch.code}) -> $bits")
            idx += 4
        }
        val chkBits = chkSymbols.joinToString("") { SYMBOL_LABELS[it] }
        sb.appendLine("XOR checksum -> $chkBits")
        sb.appendLine()
        sb.appendLine("TX Log")
        return sb.toString()
    }

    
    private fun startTransmission() {
        val message = messageInput.text.toString()
        if (message.isEmpty()) {
            Toast.makeText(this, "Enter a message first", Toast.LENGTH_SHORT).show()
            return
        }
        if (cameraId == null) {
            Toast.makeText(this, "No camera/flash found", Toast.LENGTH_SHORT).show()
            return
        }

        val dataSymbols = textToSymbols(message)
        val chkSymbols = checksumSymbols(message)
        val allSymbols = dataSymbols + chkSymbols
        val totalDataBits = message.length * 8

        
        binaryOutput.text = formatSymbolsForDisplay(message, dataSymbols, chkSymbols)

        
        isTransmitting = true
        transmitButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        messageInput.isEnabled = false
        statusText.text = "Status: 4-ary TX — ${dataSymbols.size} data + ${chkSymbols.size} chk symbols"
        rateText.text = "TX Rate: target 20.0 bps"
        currentBitText.text = "Current symbol: -"

        
        txThread = HandlerThread("FlashTX").apply {
            start()
        }
        txHandler = Handler(txThread!!.looper)
        txHandler?.post { transmitSymbols4PWM(allSymbols, totalDataBits, dataSymbols.size) }
    }

    
    private fun warmUpTorch(camId: String) {
        Log.d(TAG, "Warming up torch...")
        try {
            cameraManager.setTorchMode(camId, true)
            Thread.sleep(50)
            cameraManager.setTorchMode(camId, false)
            Thread.sleep(50)
            cameraManager.setTorchMode(camId, true)
            Thread.sleep(50)
            cameraManager.setTorchMode(camId, false)
            Thread.sleep(100) 
        } catch (e: Exception) {
            Log.e(TAG, "Warmup error: ${e.message}")
        }
        Log.d(TAG, "Torch warmed up")
    }

    
    private fun stats(values: List<Double>): Triple<Double, Double, Double> {
        if (values.isEmpty()) return Triple(0.0, 0.0, 0.0)
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return Triple(mean, sqrt(variance), variance)
    }

    
    
    
    
    
    
    private fun transmitSymbols4PWM(symbols: List<Int>, totalDataBits: Int, dataSymCount: Int) {
        val camId = cameraId ?: return

        
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        
        warmUpTorch(camId)

        val totalSymbols = symbols.size
        val startMarkerMs = START_ON_MS + START_OFF_MS  

        
        val onDurations = Array(4) { mutableListOf<Double>() }

        val txStartNs = SystemClock.elapsedRealtimeNanos()
        val appendBuffer = StringBuilder()

        
        appendBuffer.appendLine("START MARKER")
        val startOnEndNs = txStartNs + START_ON_MS * 1_000_000L
        val startOffEndNs = txStartNs + startMarkerMs * 1_000_000L

        val startOnCallNs = SystemClock.elapsedRealtimeNanos()
        try {
            cameraManager.setTorchMode(camId, true)
        } catch (e: Exception) {
            Log.e(TAG, "Start marker ON error: ${e.message}")
            runOnUiThread { resetUiAfterTx("Error: torch failed") }
            txThread?.quitSafely()
            return
        }

        while (SystemClock.elapsedRealtimeNanos() < startOnEndNs) { /* spin */ }

        val startOffCallNs = SystemClock.elapsedRealtimeNanos()
        try {
            cameraManager.setTorchMode(camId, false)
        } catch (e: Exception) {
            Log.e(TAG, "Start marker OFF error: ${e.message}")
            runOnUiThread { resetUiAfterTx("Error: torch failed") }
            txThread?.quitSafely()
            return
        }

        val actualStartOnMs = (startOffCallNs - startOnCallNs) / 1_000_000.0
        appendBuffer.appendLine("  ON=${String.format("%.1f", actualStartOnMs)}ms (expected ${START_ON_MS}ms)")

        
        while (SystemClock.elapsedRealtimeNanos() < startOffEndNs) { /* spin */ }

        appendBuffer.appendLine("DATA (${dataSymCount} sym) + CHK (${totalSymbols - dataSymCount} sym)")

        
        val startBuf = appendBuffer.toString()
        appendBuffer.clear()
        runOnUiThread {
            binaryOutput.append(startBuf)
            binaryScroll.post { binaryScroll.fullScroll(View.FOCUS_DOWN) }
        }

        
        
        val dataStartNs = txStartNs + startMarkerMs * 1_000_000L

        for ((index, sym) in symbols.withIndex()) {
            if (!isTransmitting) break

            val expectedOnMs = SYMBOL_ON_MS[sym]

            
            val symStartNs = dataStartNs + index * SYMBOL_PERIOD_MS * 1_000_000L
            val onEndNs = symStartNs + expectedOnMs * 1_000_000L

            
            while (SystemClock.elapsedRealtimeNanos() < symStartNs) { /* spin */ }

            
            val onCallStartNs = SystemClock.elapsedRealtimeNanos()
            try {
                cameraManager.setTorchMode(camId, true)
            } catch (e: Exception) {
                Log.e(TAG, "Torch ON error: ${e.message}")
                break
            }

            
            while (SystemClock.elapsedRealtimeNanos() < onEndNs) { /* spin */ }

            
            val offCallStartNs = SystemClock.elapsedRealtimeNanos()
            try {
                cameraManager.setTorchMode(camId, false)
            } catch (e: Exception) {
                Log.e(TAG, "Torch OFF error: ${e.message}")
                break
            }

            
            val actualOnMs = (offCallStartNs - onCallStartNs) / 1_000_000.0
            val devMs = actualOnMs - expectedOnMs.toDouble()
            val driftMs = (onCallStartNs - symStartNs) / 1_000_000.0

            onDurations[sym].add(actualOnMs)

            val tag = if (index < dataSymCount) "D" else "C"
            appendBuffer.appendLine(
                "#${(index + 1).toString().padStart(4)} $tag  ${SYMBOL_LABELS[sym]}  ON=${String.format("%5.1f", actualOnMs)}ms (${String.format("%+.1f", devMs)})  exp=${expectedOnMs}ms  drift=${String.format("%+.1f", driftMs)}ms"
            )

            
            if (index % 4 == 3 || index == totalSymbols - 1) {
                val elapsedMs = (SystemClock.elapsedRealtimeNanos() - txStartNs) / 1_000_000.0
                val bitsTransmitted = (index + 1).coerceAtMost(dataSymCount) * 2
                val actualBps = if (elapsedMs > 0) bitsTransmitted * 1000.0 / elapsedMs else 0.0
                val uiBuf = appendBuffer.toString()
                appendBuffer.clear()

                runOnUiThread {
                    rateText.text = "TX Rate: ${String.format("%.1f", actualBps)} bps (actual)"
                    currentBitText.text = if (index < dataSymCount)
                        "Data ${index + 1} / $dataSymCount"
                    else
                        "Checksum ${index - dataSymCount + 1} / ${totalSymbols - dataSymCount}"
                    binaryOutput.append(uiBuf)
                    binaryScroll.post { binaryScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }

        val endMarkerEndNs = dataStartNs + totalSymbols * SYMBOL_PERIOD_MS * 1_000_000L + END_MARKER_MS * 1_000_000L
        while (SystemClock.elapsedRealtimeNanos() < endMarkerEndNs) { /* spin */ }
        try { cameraManager.setTorchMode(camId, false) } catch (_: Exception) {}

        val totalElapsedMs = (SystemClock.elapsedRealtimeNanos() - txStartNs) / 1_000_000.0
        val finalBps = if (totalElapsedMs > 0) totalDataBits * 1000.0 / totalElapsedMs else 0.0

        val statsReport = StringBuilder()
        statsReport.appendLine("\nTIMING STATS")
        statsReport.appendLine(String.format("Total: %d data bits (%d+%d symbols) in %.0fms = %.1f bps effective",
            totalDataBits, dataSymCount, totalSymbols - dataSymCount, totalElapsedMs, finalBps))
        statsReport.appendLine(String.format("Start marker: %.1fms ON", actualStartOnMs))
        statsReport.appendLine()

        for (level in 0..3) {
            val values = onDurations[level]
            if (values.isEmpty()) continue
            val (mean, stddev, variance) = stats(values)
            val min = values.min()
            val max = values.max()
            val expected = SYMBOL_ON_MS[level]
            statsReport.appendLine("Symbol ${SYMBOL_LABELS[level]} ON (expected ${expected}ms, n=${values.size}):")
            statsReport.appendLine(String.format("  mean=%.1fms  stddev=%.2fms  var=%.2fms\u00B2", mean, stddev, variance))
            statsReport.appendLine(String.format("  min=%.1fms  max=%.1fms  range=%.1fms", min, max, max - min))
            statsReport.appendLine(String.format("  mean dev from expected: %+.1fms", mean - expected))
            statsReport.appendLine()
        }

        
        statsReport.appendLine("SEPARATION (adjacent levels)")
        for (level in 0..2) {
            val lower = onDurations[level]
            val upper = onDurations[level + 1]
            if (lower.isEmpty() || upper.isEmpty()) {
                statsReport.appendLine("${SYMBOL_LABELS[level]} vs ${SYMBOL_LABELS[level + 1]}: N/A (no samples)")
                continue
            }
            val maxLower = lower.max()
            val minUpper = upper.min()
            val margin = minUpper - maxLower
            statsReport.appendLine(String.format(
                "${SYMBOL_LABELS[level]} vs ${SYMBOL_LABELS[level + 1]}: max=${String.format("%.1f", maxLower)}ms  min=${String.format("%.1f", minUpper)}ms  margin=%.1fms %s",
                margin, if (margin > 0) "(CLEAN)" else "(OVERLAP!)"
            ))
        }

        Log.d(TAG, statsReport.toString())

        runOnUiThread {
            resetUiAfterTx("Done! $totalDataBits bits in ${String.format("%.0f", totalElapsedMs)}ms")
            rateText.text = "TX Rate: ${String.format("%.1f", finalBps)} bps (final)"
            binaryOutput.append(statsReport)
            binaryScroll.post { binaryScroll.fullScroll(View.FOCUS_DOWN) }
        }

        txThread?.quitSafely()
    }

    private fun resetUiAfterTx(statusMsg: String) {
        isTransmitting = false
        transmitButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        messageInput.isEnabled = true
        statusText.text = "Status: $statusMsg"
        currentBitText.text = "Finished"
    }

    private fun stopTransmission() {
        isTransmitting = false
        statusText.text = "Status: Stopping..."
    }

    override fun onDestroy() {
        super.onDestroy()
        isTransmitting = false
        txThread?.quitSafely()
        try { cameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
    }
}
