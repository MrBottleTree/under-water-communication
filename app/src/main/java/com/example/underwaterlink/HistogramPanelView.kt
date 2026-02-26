package com.example.underwaterlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Compact 1-D thermal heatmap for the 4 top-grid histograms.
 *
 * Layout (horizontal):  [strip1] | [strip2] | [strip3] | [strip4]
 *
 * Each strip:
 *   ┌─ title (grid index / coords / confidence) ──────────────────┐
 *   │  1-D color band  — 256 columns, one per brightness bin       │
 *   │  "hot" colormap: black → red → orange → yellow → white       │
 *   │  white tick at the Otsu threshold position                   │
 *   └──────────────────────────────────────────────────────────────┘
 *
 * Strips are separated by 2-px white vertical lines.
 * Total panel height is ~50 dp (see activity_test.xml).
 */
class HistogramPanelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var histograms:    List<FloatArray> = emptyList()
    private var topGrids:      List<Int>        = emptyList()
    private var otsuThreshold: IntArray         = IntArray(0)
    private var confidence:    FloatArray       = FloatArray(0)
    private var cols:          Int              = 1

    // ── Paints ────────────────────────────────────────────────────────────────

    private val bgPaint  = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val sepPaint = Paint().apply { color = Color.WHITE; strokeWidth = 2f }
    private val barPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false }
    private val tickPaint = Paint().apply { color = Color.WHITE; strokeWidth = 2f }
    private val titleOn  = Paint().apply {
        color = Color.YELLOW; textSize = 18f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }
    private val titleOff = Paint().apply {
        color = Color.DKGRAY; textSize = 18f; isAntiAlias = true; typeface = Typeface.MONOSPACE
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun update(result: GridResult) {
        histograms    = result.topGridHistograms
        topGrids      = result.topGrids
        otsuThreshold = result.otsuThreshold
        confidence    = result.confidence
        cols          = result.cols
        invalidate()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val count   = histograms.size.coerceAtMost(4)
        if (count == 0) return

        val SEP     = 2f          // separator width
        val PAD     = 4f          // horizontal padding per strip
        val TITLE_H = 20f         // height reserved for title text
        val stripW  = (w - SEP * (count - 1)) / count
        val heatH   = h - TITLE_H - 2f   // heatmap band height

        for (i in 0 until count) {
            val left   = i * (stripW + SEP)
            val hasGrid = i < topGrids.size
            val g      = if (hasGrid) topGrids[i] else -1
            val hist   = histograms[i]
            val thresh = if (hasGrid && g < otsuThreshold.size) otsuThreshold[g] else 128
            val conf   = if (hasGrid && g < confidence.size)    confidence[g]    else 0f

            // ── Title ──────────────────────────────────────────────────────
            val title = if (hasGrid) {
                "#${i + 1}(${g % cols},${g / cols}) c=${String.format("%.2f", conf)}"
            } else "#${i + 1} --"
            canvas.drawText(title, left + PAD, TITLE_H - 3f, if (hasGrid) titleOn else titleOff)

            // ── 1-D thermal heatmap ────────────────────────────────────────
            val top    = TITLE_H
            val maxVal = hist.max().coerceAtLeast(1e-6f)
            val binW   = stripW / 256f

            for (b in 0..255) {
                val t    = hist[b] / maxVal           // normalised 0–1
                barPaint.color = hotColor(t)
                canvas.drawRect(left + b * binW, top, left + (b + 1) * binW, top + heatH, barPaint)
            }

            // ── Otsu threshold tick ────────────────────────────────────────
            val tx = left + thresh * binW
            canvas.drawLine(tx, top, tx, top + heatH, tickPaint)

            // ── Vertical separator (not after last strip) ──────────────────
            if (i < count - 1) {
                val sx = left + stripW + SEP / 2f
                canvas.drawLine(sx, 0f, sx, h, sepPaint)
            }
        }
    }

    // ── "Hot" colormap: black → red → orange → yellow → white ────────────────
    //
    //   t  0.00 → 0.33 : R ramps from 0 to 255, G=0,   B=0   (black → red)
    //   t  0.33 → 0.66 : R=255,          G ramps 0→255, B=0   (red → yellow)
    //   t  0.66 → 1.00 : R=255, G=255,   B ramps 0→255        (yellow → white)

    private fun hotColor(t: Float): Int {
        if (t <= 0f) return Color.BLACK
        val r = ((t * 3f)       .coerceIn(0f, 1f) * 255f).toInt()
        val g = ((t * 3f - 1f)  .coerceIn(0f, 1f) * 255f).toInt()
        val b = ((t * 3f - 2f)  .coerceIn(0f, 1f) * 255f).toInt()
        return Color.rgb(r, g, b)
    }
}
