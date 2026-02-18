package com.example.underwaterlink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay that draws a bounding box around the detected flash region.
 *
 * Visual states:
 *   isLocked = true  → solid bright-green box  ("LOCKED")   — start marker confirmed
 *   isLocked = false → dashed yellow box        ("SEEKING…") — candidate being evaluated
 *   rect = null      → nothing drawn
 *
 * Coordinate mapping (FIT_CENTER letterbox):
 *   Camera image pixels arrive in raw image orientation (often landscape for back camera).
 *   imageInfo.rotationDegrees says how many degrees CW to rotate the image for correct display.
 *   PreviewView is set to FIT_CENTER, so the displayed image is letterboxed inside the view.
 *   We rotate the normalized [0,1] corners and scale into view pixels.
 */
class RoiOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // Locked: solid green
    private val lockedPaint = Paint().apply {
        color       = Color.GREEN
        style       = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    // Seeking: dashed yellow
    private val seekingPaint = Paint().apply {
        color       = Color.YELLOW
        style       = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect  = DashPathEffect(floatArrayOf(20f, 10f), 0f)
        isAntiAlias = true
    }

    private val lockedLabelPaint = Paint().apply {
        color       = Color.GREEN
        textSize    = 40f
        typeface    = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private val seekingLabelPaint = Paint().apply {
        color       = Color.YELLOW
        textSize    = 36f
        typeface    = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private var normRect: RectF? = null
    private var imgW    = 1
    private var imgH    = 1
    private var rotDeg  = 0
    private var locked  = false

    /** Call from the UI thread after every analyzed frame. */
    fun setRoi(
        rect:            RectF?,
        imageWidth:      Int,
        imageHeight:     Int,
        rotationDegrees: Int,
        isLocked:        Boolean
    ) {
        normRect = rect
        imgW     = imageWidth
        imgH     = imageHeight
        rotDeg   = rotationDegrees
        locked   = isLocked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nr = normRect ?: return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // After applying rotationDegrees, effective display dimensions may swap
        val dispW = if (rotDeg == 90 || rotDeg == 270) imgH.toFloat() else imgW.toFloat()
        val dispH = if (rotDeg == 90 || rotDeg == 270) imgW.toFloat() else imgH.toFloat()

        // FIT_CENTER: scale to fit, keeping aspect ratio (letterbox)
        val scale   = minOf(viewW / dispW, viewH / dispH)
        val offsetX = (viewW - dispW * scale) / 2f
        val offsetY = (viewH - dispH * scale) / 2f

        val drawRect   = mapToView(nr, scale, offsetX, offsetY, dispW, dispH)
        val boxPaint   = if (locked) lockedPaint   else seekingPaint
        val labelPaint = if (locked) lockedLabelPaint else seekingLabelPaint
        val label      = if (locked) "LOCKED"       else "SEEKING…"

        canvas.drawRect(drawRect, boxPaint)
        val labelY = (drawRect.top - 8f).coerceAtLeast(labelPaint.textSize)
        canvas.drawText(label, drawRect.left, labelY, labelPaint)
    }

    /**
     * Rotates normalized image-space corners to display space, then scales to view pixels.
     *
     * 90° CW:  (x, y) → (1−y,  x )
     * 180°:    (x, y) → (1−x, 1−y)
     * 270° CW: (x, y) → ( y,  1−x)
     */
    private fun mapToView(
        nr:      RectF,
        scale:   Float,
        offsetX: Float,
        offsetY: Float,
        dispW:   Float,
        dispH:   Float
    ): RectF {
        val corners = listOf(
            PointF(nr.left,  nr.top),
            PointF(nr.right, nr.top),
            PointF(nr.left,  nr.bottom),
            PointF(nr.right, nr.bottom)
        )

        val rotated = corners.map { p ->
            when (rotDeg) {
                90  -> PointF(1f - p.y, p.x)
                180 -> PointF(1f - p.x, 1f - p.y)
                270 -> PointF(p.y, 1f - p.x)
                else -> PointF(p.x, p.y)
            }
        }

        val displayedW = dispW * scale
        val displayedH = dispH * scale

        return RectF(
            rotated.minOf { it.x } * displayedW + offsetX,
            rotated.minOf { it.y } * displayedH + offsetY,
            rotated.maxOf { it.x } * displayedW + offsetX,
            rotated.maxOf { it.y } * displayedH + offsetY
        )
    }
}
