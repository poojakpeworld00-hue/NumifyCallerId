package com.callerid.numberlookup.home.feature.splash

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * The three rings orbiting the splash mark (design: Numify Splash v2).
 *
 * The handoff draws them as three separately-animated elements:
 *
 *  - `spin-cw 14s` — a 150-unit dashed circle at 35% accent, turning slowly.
 *  - `spin-ccw 5s` — a 126-unit circle at 16% accent whose top and right quarters
 *    are solid accent, turning the other way.
 *  - `orbit 3.6s` — an 8-unit dot riding the outer circle, with a glow.
 *
 * A custom view rather than three rotating ImageViews because of the middle one:
 * a `<shape>` stroke takes one colour, so "16% all round, solid across two
 * quadrants" cannot be expressed as a drawable at all. Once one ring needs a
 * Canvas the other two may as well share it — three `View`s each running their
 * own rotation animator would be three more animators to start, stop and leak.
 *
 * One animator drives all three. Each ring's angle is that phase scaled by the
 * ratio of its own period, so they stay in the design's relative speeds without
 * three timelines to keep in sync.
 */
class SplashMarkView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** Design units, against the 170-unit box the whole mark sits in. */
        const val BOX = 170f
        const val DASHED_DIAMETER = 150f
        const val SOLID_DIAMETER = 126f
        const val RING_STROKE = 2f
        const val DOT_DIAMETER = 8f

        const val DASH_ON = 6f
        const val DASH_OFF = 7f

        /** `spin-cw 14s`, the slowest ring — the phase animator's own period. */
        const val BASE_PERIOD_MS = 14_000L

        /** `spin-ccw 5s`, turned the other way. */
        const val SOLID_PERIOD_MS = 5_000L

        /** `orbit 3.6s`. */
        const val ORBIT_PERIOD_MS = 3_600L

        /** The accent arc covers the top and right quarters: from 12 o'clock, 180°. */
        const val ARC_START_DEG = -90f
        const val ARC_SWEEP_DEG = 180f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val oval = RectF()

    private val dashedColor = color(R.color.splash_ring_dashed)
    private val faintColor = color(R.color.splash_ring_faint)
    private val accentColor = color(R.color.ds_accent)

    /** 0..1 through one full turn of the slowest ring. */
    private var phase = 0f

    private var spinner: Animator? = null

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /** Design units → pixels, scaled off whichever dimension is the limiting one. */
    private fun u(units: Float): Float = units / BOX * minOf(width, height)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    /** Starts the shared phase loop. Idempotent. */
    fun start() {
        if (spinner != null || visibility != VISIBLE) return
        spinner = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = BASE_PERIOD_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        spinner?.cancel()
        spinner = null
    }

    /**
     * The composed frame with nothing turning — what the view shows when the user
     * has system animations off, so the mark is still the design's mark rather
     * than a blank square.
     */
    fun showSettledFrame() {
        stop()
        phase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val stroke = u(RING_STROKE)
        paint.strokeWidth = stroke

        // Outer dashed ring, turning clockwise.
        val dashedR = u(DASHED_DIAMETER) / 2f
        val dashOn = u(DASH_ON)
        val dashOff = u(DASH_OFF)
        paint.color = dashedColor
        paint.pathEffect = DashPathEffect(floatArrayOf(dashOn, dashOff), 0f)
        canvas.withRotation(phase * 360f, cx, cy) {
            drawCircle(cx, cy, dashedR, paint)
        }
        // Cleared before the solid rings: a path effect left on the paint would
        // dash them too.
        paint.pathEffect = null

        // Inner ring, turning counter-clockwise: faint all the way round, with the
        // top and right quarters overdrawn in accent.
        val solidR = u(SOLID_DIAMETER) / 2f
        val solidAngle = -phase * 360f * (BASE_PERIOD_MS.toFloat() / SOLID_PERIOD_MS)
        oval.set(cx - solidR, cy - solidR, cx + solidR, cy + solidR)
        canvas.withRotation(solidAngle, cx, cy) {
            paint.color = faintColor
            drawCircle(cx, cy, solidR, paint)
            paint.color = accentColor
            drawArc(oval, ARC_START_DEG, ARC_SWEEP_DEG, false, paint)
        }

        // The dot rides the outer circle, fastest of the three.
        val orbitAngle = phase * 360f * (BASE_PERIOD_MS.toFloat() / ORBIT_PERIOD_MS)
        dotPaint.color = accentColor
        canvas.withRotation(orbitAngle, cx, cy) {
            drawCircle(cx, cy - dashedR, u(DOT_DIAMETER) / 2f, dotPaint)
        }
    }

    /** Local stand-in for the ktx extension, keeping save/restore paired. */
    private inline fun Canvas.withRotation(degrees: Float, px: Float, py: Float, block: Canvas.() -> Unit) {
        val saved = save()
        rotate(degrees, px, py)
        block()
        restoreToCount(saved)
    }
}
