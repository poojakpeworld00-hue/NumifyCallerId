package com.numify.callerid.lookup.feature.intro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R

/**
 * The progress ring around the resolved caller on the third onboarding page.
 *
 * The design draws it as two stacked SVG circles — a full #DCE4F7 track and a
 * brand-blue arc whose `stroke-dashoffset` animates 157 → 110 — which is a 4-unit
 * stroke on a radius-25 circle in a 56 box. Circumference is 2πr ≈ 157, so the
 * authored offset leaves (157 − 110) / 157 of the ring drawn: a little under a
 * third, starting at twelve o'clock and running clockwise.
 *
 * A ProgressBar cannot express that (its indeterminate ring is the platform's own
 * animation, and the determinate one is a different shape), so the arc is drawn
 * here and [sweepFraction] is animated from the page's entrance sequence.
 */
class LookupRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** The design's 56-unit box, its r=25 circle and its 4-unit stroke. */
        const val DESIGN_BOX = 56f
        const val DESIGN_RADIUS = 25f
        const val DESIGN_STROKE = 4f

        /** `stroke-dasharray: 157` — the circumference the offsets are measured against. */
        const val DASH_ARRAY = 157f

        /** `stroke-dashoffset` at the end of the ringDraw keyframe. */
        const val DASH_OFFSET_END = 110f

        /** Twelve o'clock: the design's `transform="rotate(-90 28 28)"`. */
        const val START_ANGLE = -90f
    }

    /** The arc the finished animation lands on, as a fraction of the full circle. */
    val targetFraction: Float = (DASH_ARRAY - DASH_OFFSET_END) / DASH_ARRAY

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.ds_field_border)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ds_accent)
    }

    private val bounds = RectF()

    /** How much of the ring is drawn, 0..1. Animated by the page entrance. */
    var sweepFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0f) return

        // Everything scales off the design's 56-unit box, so the ring keeps its
        // proportions whatever size the layout gives it.
        val scale = size / DESIGN_BOX
        val stroke = DESIGN_STROKE * scale
        val radius = DESIGN_RADIUS * scale
        trackPaint.strokeWidth = stroke
        arcPaint.strokeWidth = stroke

        val cx = width / 2f
        val cy = height / 2f
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawCircle(cx, cy, radius, trackPaint)
        if (sweepFraction > 0f) {
            canvas.drawArc(bounds, START_ANGLE, 360f * sweepFraction, false, arcPaint)
        }
    }
}
