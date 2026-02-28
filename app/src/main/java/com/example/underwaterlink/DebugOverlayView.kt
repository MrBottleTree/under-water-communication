package com.example.underwaterlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Transparent overlay drawn on top of the camera [PreviewView].
 *
 * Draws two layers:
 *  1. Heatmap  — one coloured rectangle per grid cell (blue→red scaled by confidence).
 *  2. Debug panel — compact text panel showing protocol state, FPS, AE params, and bitVote.
 *
 * The BER strip and received-message banner present in earlier versions have been removed;
 * those values are now shown in the right-panel [TextView]s of the new 3-column layout.
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Data passed in every frame ────────────────────────────────────────────

    /** Snapshot of all data needed to redraw the overlay for one camera frame. */
    data class DebugData(
        val result: GridResult,
        /** Human-readable FSM state name (e.g. "INITIAL", "SENDING"). */
        val state: String,
        /** Current EMA alpha value (shown in the debug panel). */
        val alpha: Float,
        /** Current confidence threshold (Otsu gating). */
        val confThreshold: Float,
        /** Smoothed FPS reading (20fps target). */
        val fps: Float,
        /** Signed brightness vote in [-1, +1] from [GridAnalyzer]. */
        val bitVote: Float
    )

    private var data: DebugData? = null

    // Camera preview geometry within this view (FIT_CENTER letterbox)
    private var previewLeft   = 0f
    private var previewTop    = 0f
    private var previewWidth  = 0f
    private var previewHeight = 0f
    private var imageWidth    = 640
    private var imageHeight   = 480

    // ── Paints ────────────────────────────────────────────────────────────────

    private val heatPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }

    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE; isAntiAlias = false
    }

    private val panelBgPaint = Paint().apply {
        color = Color.argb(190, 0, 0, 0); style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    private val greenText = Paint().apply {
        color = Color.GREEN; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    private val yellowText = Paint().apply {
        color = Color.YELLOW; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    private val redText = Paint().apply {
        color = Color.RED; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    private val cyanText = Paint().apply {
        color = Color.CYAN; textSize = 28f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Notify the overlay of the actual camera image dimensions for geometry calculation. */
    fun setImageDimensions(w: Int, h: Int) {
        imageWidth  = w
        imageHeight = h
        recomputePreviewGeometry()
        invalidate()
    }

    /** Push a new frame's data and request a redraw. */
    fun update(d: DebugData) {
        data = d
        invalidate()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputePreviewGeometry()
    }

    /**
     * Recompute the letterbox region that matches PreviewView's FIT_CENTER scale type.
     * One axis will be full-width/height; the other may have black bars.
     */
    private fun recomputePreviewGeometry() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f || imageWidth == 0 || imageHeight == 0) return
        val scale     = min(vw / imageWidth, vh / imageHeight)
        previewWidth  = imageWidth  * scale
        previewHeight = imageHeight * scale
        previewLeft   = (vw - previewWidth)  / 2f
        previewTop    = (vh - previewHeight) / 2f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val d = data ?: return
        val result = d.result
        val cellW = previewWidth  / result.cols
        val cellH = previewHeight / result.rows
        drawHeatmap(canvas, result, cellW, cellH)
        drawDebugPanel(canvas, d)
    }

    // 1. Heatmap — coloured grid cells ----------------------------------------

    private fun drawHeatmap(canvas: Canvas, result: GridResult, cellW: Float, cellH: Float) {
        for (row in 0 until result.rows) {
            for (col in 0 until result.cols) {
                val g    = row * result.cols + col
                val conf = result.confidence[g]
                if (conf < 0.02f) continue   // skip near-zero grids for speed

                val left = previewLeft + col * cellW
                val top  = previewTop  + row * cellH

                // Hue: 240° (blue) at conf=0 → 0° (red) at conf=1
                val hue   = (1f - conf) * 240f
                val alpha = (conf * 200).toInt().coerceIn(40, 200)
                heatPaint.color = hsvArgb(hue, 1f, 1f, alpha)
                canvas.drawRect(left, top, left + cellW, top + cellH, heatPaint)
            }
        }

        // White border on top-K voting grids
        for (g in result.topGrids) {
            val row  = g / result.cols;  val col  = g % result.cols
            val left = previewLeft + col * cellW; val top  = previewTop  + row * cellH
            canvas.drawRect(left, top, left + cellW, top + cellH, borderPaint)
        }
    }

    // 2. Debug text panel (top-left of preview) --------------------------------

    private fun drawDebugPanel(canvas: Canvas, d: DebugData) {
        val result = d.result
        val x      = previewLeft + 8f
        var y      = previewTop  + 8f
        val lineH  = 30f
        val panelW = 360f
        val lines  = 9

        canvas.drawRoundRect(
            RectF(x - 4f, y - 4f, x + panelW, y + lines * lineH + 4f),
            6f, 6f, panelBgPaint
        )

        /** Draw one label:value line and advance [y]. */
        fun txt(label: String, value: String, paint: Paint = textPaint) {
            canvas.drawText("$label $value", x + 4f, y + lineH - 6f, paint)
            y += lineH
        }

        // State — colour by semantic meaning
        val statePaint = when {
            d.state.contains("SEND") || d.state.contains("READY") -> greenText
            d.state.contains("CALIB") || d.state.contains("INITIAL") -> cyanText
            else -> yellowText
        }
        txt("State:", d.state, statePaint)
        txt("FPS:",   String.format("%.1f", d.fps))
        txt("Alpha:", String.format("%.3f", d.alpha))
        txt("Conf:",  String.format("%.2f", d.confThreshold))
        txt("Active:", "${result.activeGridCount} grids")
        txt("Cluster:", "${result.largestClusterSize} grids")
        txt("Bright:", String.format("%.3f", result.compositeBrightness))

        // BitVote — green/red/yellow Schmitt colouring
        val votePaint = when {
            d.bitVote >  0.25f -> greenText
            d.bitVote < -0.25f -> redText
            else               -> yellowText
        }
        txt("BitVote:", String.format("%+.2f", d.bitVote), votePaint)
        txt("TopK:", "${result.topGrids.size}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert HSV + alpha to a packed ARGB int. */
    private fun hsvArgb(h: Float, s: Float, v: Float, alpha: Int): Int {
        val hsv = floatArrayOf(h, s, v)
        return (Color.HSVToColor(hsv) and 0x00FFFFFF) or (alpha shl 24)
    }
}
