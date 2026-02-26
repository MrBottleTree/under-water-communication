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
 * Transparent overlay view drawn on top of the camera PreviewView.
 *
 * Draws:
 *  1. Heatmap  – one coloured rectangle per 16×16 grid (blue→red with confidence)
 *  2. Histograms – bar charts for the top-3 most-confident grids, with Otsu threshold line
 *  3. Debug text panel – state, alpha, fps, cluster info, bit vote
 *  4. Bit comparison strip – received vs expected bits with error rate
 */
class DebugOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Data passed in every frame ────────────────────────────────────────────

    data class DebugData(
        val result: GridResult,
        val state: String,
        val alpha: Float,
        val confThreshold: Float,
        val fps: Float,
        val bitVote: Float,
        val receivedBits: String,
        val expectedBits: String,
        val errorCount: Int,
        val totalBits: Int,
        val uncertainBits: Int,
        val lastReceivedMessage: String   // e.g. "C1 RECEIVED", "C2 RECEIVED", or ""
    )

    private var data: DebugData? = null

    // Camera preview geometry within this view (FIT_CENTER letterbox)
    private var previewLeft = 0f
    private var previewTop = 0f
    private var previewWidth = 0f
    private var previewHeight = 0f
    private var imageWidth = 640
    private var imageHeight = 480

    // ── Paints ────────────────────────────────────────────────────────────────

    private val heatPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.WHITE; isAntiAlias = false
    }
    private val threshPaint = Paint().apply {
        color = Color.YELLOW; strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val panelBgPaint = Paint().apply {
        color = Color.argb(190, 0, 0, 0); style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 30f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val smallText = Paint().apply {
        color = Color.WHITE; textSize = 24f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val greenText = Paint().apply {
        color = Color.GREEN; textSize = 30f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val yellowText = Paint().apply {
        color = Color.YELLOW; textSize = 30f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val redText = Paint().apply {
        color = Color.RED; textSize = 30f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val bannerPaint = Paint().apply {
        color = Color.GREEN; textSize = 72f; isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val bannerBgPaint = Paint().apply {
        color = Color.argb(200, 0, 0, 0); style = Paint.Style.FILL
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun setImageDimensions(w: Int, h: Int) {
        imageWidth = w
        imageHeight = h
        recomputePreviewGeometry()
        invalidate()
    }

    fun update(d: DebugData) {
        data = d
        invalidate()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputePreviewGeometry()
    }

    private fun recomputePreviewGeometry() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw == 0f || vh == 0f || imageWidth == 0 || imageHeight == 0) return
        val scale = min(vw / imageWidth, vh / imageHeight)
        previewWidth = imageWidth * scale
        previewHeight = imageHeight * scale
        previewLeft = (vw - previewWidth) / 2f
        previewTop = (vh - previewHeight) / 2f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val d = data ?: return
        val result = d.result

        val cellW = previewWidth / result.cols
        val cellH = previewHeight / result.rows

        drawHeatmap(canvas, result, cellW, cellH)
        // Histograms are now drawn by HistogramPanelView (separate, non-overlapping panel)
        drawDebugPanel(canvas, d)
        drawBitStrip(canvas, d)
        if (d.lastReceivedMessage.isNotEmpty()) drawReceivedBanner(canvas, d.lastReceivedMessage)
    }

    // 1. Heatmap ---------------------------------------------------------------

    private fun drawHeatmap(canvas: Canvas, result: GridResult, cellW: Float, cellH: Float) {
        for (row in 0 until result.rows) {
            for (col in 0 until result.cols) {
                val g = row * result.cols + col
                val conf = result.confidence[g]
                if (conf < 0.02f) continue   // skip nearly-zero grids for speed

                val left = previewLeft + col * cellW
                val top = previewTop + row * cellH

                // Hue: 240 (blue) at conf=0 → 0 (red) at conf=1
                val hue = (1f - conf) * 240f
                val alpha = (conf * 200).toInt().coerceIn(40, 200)
                heatPaint.color = hsvArgb(hue, 1f, 1f, alpha)
                canvas.drawRect(left, top, left + cellW, top + cellH, heatPaint)
            }
        }
        // White border on top-K grids
        for (g in result.topGrids) {
            val row = g / result.cols; val col = g % result.cols
            val left = previewLeft + col * cellW; val top = previewTop + row * cellH
            canvas.drawRect(left, top, left + cellW, top + cellH, borderPaint)
        }
    }

    // 2. Debug text panel (top-left) ------------------------------------------

    private fun drawDebugPanel(canvas: Canvas, d: DebugData) {
        val result = d.result
        val x = previewLeft + 8f
        var y = previewTop + 8f
        val lineH = 32f
        val panelW = 370f
        val lines = 9

        canvas.drawRoundRect(
            RectF(x - 4f, y - 4f, x + panelW, y + lines * lineH + 4f),
            6f, 6f, panelBgPaint
        )

        fun txt(label: String, value: String, paint: Paint = textPaint) {
            canvas.drawText("$label $value", x + 4f, y + lineH - 6f, paint)
            y += lineH
        }

        val statePaint = when {
            d.state.startsWith("RECEIV") -> greenText
            d.state.startsWith("PREAMBLE") || d.state.startsWith("DATA") -> yellowText
            else -> textPaint
        }
        txt("State:", d.state, statePaint)
        txt("FPS:", String.format("%.1f", d.fps))
        txt("Alpha (α):", String.format("%.3f", d.alpha))
        txt("Conf thr:", String.format("%.2f", d.confThreshold))
        txt("Active:", "${result.activeGridCount} grids")
        txt("Cluster:", "${result.largestClusterSize} grids")
        txt("Composite:", String.format("%.3f", result.compositeBrightness))

        val votePaint = when {
            d.bitVote > 0.25f -> greenText
            d.bitVote < -0.25f -> redText
            else -> yellowText
        }
        txt("BitVote:", String.format("%+.2f", d.bitVote), votePaint)

        txt("TopK:", "${result.topGrids.size}")
    }

    // 4. Bit comparison strip (bottom of preview) -----------------------------

    private fun drawBitStrip(canvas: Canvas, d: DebugData) {
        if (d.totalBits == 0 && d.uncertainBits == 0) return
        val stripH = 90f
        val x = previewLeft + 8f
        val y = previewTop + previewHeight - stripH - 4f

        canvas.drawRoundRect(
            RectF(x - 4f, y, x + previewWidth - 12f, y + stripH),
            6f, 6f, panelBgPaint
        )

        val maxChars = ((previewWidth - 20f) / 16f).toInt().coerceAtMost(48)
        val rx = d.receivedBits.takeLast(maxChars)
        val ex = d.expectedBits.takeLast(maxChars)

        smallText.color = Color.CYAN
        canvas.drawText("RX: $rx", x + 4f, y + 24f, smallText)
        smallText.color = Color.WHITE
        canvas.drawText("EX: $ex", x + 4f, y + 50f, smallText)

        val ber = if (d.totalBits > 0) d.errorCount * 100f / d.totalBits else 0f
        val errPaint = if (ber > 10f) redText else greenText
        canvas.drawText(
            "ERR ${d.errorCount}/${d.totalBits}  BER ${String.format("%.1f", ber)}%  ?:${d.uncertainBits}",
            x + 4f, y + 78f, errPaint
        )
    }

    // 5. Received message banner (centre of preview) ---------------------------

    private fun drawReceivedBanner(canvas: Canvas, msg: String) {
        val cx = previewLeft + previewWidth / 2f
        val cy = previewTop + previewHeight / 2f
        val fm = bannerPaint.fontMetrics
        val textH = fm.descent - fm.ascent
        val pad = 20f
        canvas.drawRoundRect(
            RectF(cx - previewWidth * 0.38f, cy - textH / 2f - pad,
                  cx + previewWidth * 0.38f, cy + textH / 2f + pad),
            12f, 12f, bannerBgPaint
        )
        bannerPaint.color = if (msg.startsWith("C1")) Color.GREEN else Color.CYAN
        canvas.drawText(msg, cx, cy - fm.ascent - pad / 2f, bannerPaint)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hsvArgb(h: Float, s: Float, v: Float, alpha: Int): Int {
        val hsv = floatArrayOf(h, s, v)
        return (Color.HSVToColor(hsv) and 0x00FFFFFF) or (alpha shl 24)
    }
}
