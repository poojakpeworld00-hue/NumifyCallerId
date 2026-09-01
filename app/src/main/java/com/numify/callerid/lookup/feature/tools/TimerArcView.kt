package com.numify.callerid.lookup.feature.tools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R

/**
 * The countdown ring: a full-circle track, the elapsed arc painted over it, and a
 * knob riding the arc's leading edge.
 *
 * The design realises this with two stacked CSS circles and a rotated dot. Here
 * it is a single [onDraw], because a CSS border-arc cannot express a partial
 * sweep - the prototype only fakes one by colouring two borders and rotating the
 * whole element.
 *
 * [setProgress] animates, so the ring glides between the 100ms ticks the timer
 * posts instead of stepping. That is what keeps it reading as continuous at 60fps
 * from a 10fps data source.
 */
class TimerArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    /** Design: 14px stroke on a 262px canvas, and a knob 18px across. */
    private val strokeWidth = 14f * density * DESIGN_SCALE
    private val knobRadius = 9f * density * DESIGN_SCALE

    /** Design: the knob sits in a 4px cut-out of the page background. */
    private val knobGap = 4f * density * DESIGN_SCALE

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@TimerArcView.strokeWidth
        color = ContextCompat.getColor(context, R.color.primary_container)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@TimerArcView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary_dark)
    }

    /** Punches the gap between knob and ring, so it reads as sitting on top. */
    private val knobGapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = knobGap
        color = ContextCompat.getColor(context, R.color.background)
    }

    private val oval = RectF()
    private var progressAnimator: ValueAnimator? = null

    /** 0f = nothing elapsed, 1f = a full turn. */
    var progress: Float = 0f
        private set

    /**
     * Moves the ring to [target].
     *
     * A reset (or any jump backwards) is applied immediately: animating the arc
     * all the way back round would read as the timer counting up.
     */
    fun setProgress(target: Float, animate: Boolean = true) {
        val clamped = target.coerceIn(0f, 1f)
        progressAnimator?.cancel()
        if (!animate || clamped < progress) {
            progress = clamped
            invalidate()
            return
        }
        progressAnimator = ValueAnimator.ofFloat(progress, clamped).apply {
            duration = TICK_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        // Inset by half the stroke so the ring's outer edge lands on the bounds.
        val radius = (minOf(width, height) / 2f) - (strokeWidth / 2f)
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawCircle(cx, cy, radius, trackPaint)

        val sweep = progress * 360f
        if (sweep > 0f) canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint)

        // Knob rides the arc's leading end.
        val angle = Math.toRadians((START_ANGLE + sweep).toDouble())
        val knobX = cx + radius * kotlin.math.cos(angle).toFloat()
        val knobY = cy + radius * kotlin.math.sin(angle).toFloat()
        canvas.drawCircle(knobX, knobY, knobRadius + knobGap / 2f, knobGapPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
    }

    private companion object {
        /** 12 o'clock. Canvas angles start at 3 o'clock. */
        const val START_ANGLE = -90f

        /** How often the host posts a new value; the glide matches it exactly. */
        const val TICK_MS = 100L

        /**
         * The design's ring is 262dp wide but the layout gives it less on small
         * screens, so its 14px stroke is scaled by how much of the design width
         * we actually got. Without this the stroke swallows the dial on compact
         * devices.
         */
        const val DESIGN_SCALE = 0.86f
    }
}
