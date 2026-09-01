package com.numify.callerid.lookup.feature.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R
import kotlin.math.hypot

/**
 * Bubble-level dial: concentric rings, a crosshair, edge ticks, and the bubble.
 *
 * The bubble is drawn here rather than being a child View the activity moves,
 * because it has to be clamped to the dial's circle — a translated child would
 * happily slide out past the rim on a steep tilt.
 *
 * [setTilt] takes degrees, not a fraction, so the caller passes what the sensor
 * actually reports and the mapping to pixels lives in one place.
 */
class LevelIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.primary_container)
    }

    /** The centre ring is the "you are level" target, so it is brand-weighted. */
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.primary_container)
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
        alpha = 90
    }

    private var rollDeg = 0f
    private var pitchDeg = 0f

    fun setTilt(roll: Float, pitch: Float) {
        rollDeg = roll
        pitchDeg = pitch
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f

        canvas.drawCircle(cx, cy, outer, facePaint)

        val ringR = outer - (20f * density)
        canvas.drawCircle(cx, cy, ringR, ringPaint)

        val bubbleR = outer * 0.115f
        val targetR = bubbleR + (6f * density)
        canvas.drawCircle(cx, cy, targetR, targetPaint)

        // Crosshair, stopping short of the rim.
        val arm = ringR
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)

        // Edge ticks at the four cardinal points.
        val tick = 12f * density
        canvas.drawLine(cx - outer + (6f * density), cy, cx - outer + (6f * density) + tick, cy, tickPaint)
        canvas.drawLine(cx + outer - (6f * density) - tick, cy, cx + outer - (6f * density), cy, tickPaint)
        canvas.drawLine(cx, cy - outer + (6f * density), cx, cy - outer + (6f * density) + tick, tickPaint)
        canvas.drawLine(cx, cy + outer - (6f * density) - tick, cx, cy + outer - (6f * density), tickPaint)

        // Bubble. Travel is clamped to the ring so it can never leave the dial.
        val travel = ringR - bubbleR
        var dx = (rollDeg / MAX_TILT_DEG) * travel
        var dy = (pitchDeg / MAX_TILT_DEG) * travel
        val dist = hypot(dx, dy)
        if (dist > travel) {
            dx = dx / dist * travel
            dy = dy / dist * travel
        }
        canvas.drawCircle(cx + dx, cy + dy, bubbleR, bubblePaint)
        canvas.drawOval(
            cx + dx - bubbleR * 0.45f, cy + dy - bubbleR * 0.55f,
            cx + dx + bubbleR * 0.05f, cy + dy - bubbleR * 0.15f,
            glintPaint,
        )
    }

    private companion object {
        /** Tilt at which the bubble reaches the rim. */
        const val MAX_TILT_DEG = 25f
    }
}
