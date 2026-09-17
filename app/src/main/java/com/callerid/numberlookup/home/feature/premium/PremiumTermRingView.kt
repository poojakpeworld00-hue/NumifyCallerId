package com.callerid.numberlookup.home.feature.premium

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * How much of a Premium term is left, as a ring with the crown inside it.
 *
 * It sits on the gold status card, so both strokes are white: the track at a
 * low alpha for the part already spent, the arc solid for what remains. On gold
 * that reads better than a second colour would — a third hue on a two-hue card
 * is one too many.
 *
 * The arc sweeps out once when the value is set and then holds. Deliberately
 * not a loop: this is a quantity, and a quantity that keeps re-animating starts
 * to look like a spinner, which means "working" rather than "eighteen days".
 *
 * A lifetime unlock, or an entitlement restored with no local date to count
 * from, passes 1f and gets a closed ring - true in both cases, since neither
 * has a remaining fraction to show.
 */
class PremiumTermRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RING_STROKE_DP)
        color = ContextCompat.getColor(context, R.color.premium_ring_track)
    }

    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(RING_STROKE_DP)
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.ds_on_accent)
    }

    private val crown = ContextCompat.getDrawable(context, R.drawable.ic_premium_crown)?.apply {
        setTint(ContextCompat.getColor(context, R.color.ds_on_accent))
    }

    private val bounds = RectF()

    /** What the arc is drawn to, 0f..1f. */
    private var swept = 0f

    /** What it is sweeping towards, kept so a re-bind does not restart at zero. */
    private var target = 0f

    private var animator: ValueAnimator? = null

    /**
     * Sets the remaining fraction and sweeps to it.
     *
     * Ignores a repeat of the value it already holds, because Settings re-binds
     * on every onResume and a ring that replays each time the user comes back
     * from another screen is noise.
     */
    fun setRemaining(fraction: Float) {
        val next = fraction.coerceIn(0f, 1f)
        if (next == target && animator != null) return
        target = next

        animator?.cancel()
        if (!isAttachedToWindow) {
            swept = next
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(swept, next).apply {
            duration = SWEEP_MS
            interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.premium_fade_up)
            addUpdateListener {
                swept = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        animator = null
        // Held at the value it reached, so coming back shows the term rather
        // than an empty ring that fills in again.
        swept = target
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = track.strokeWidth / 2f + dp(1f)
        bounds.set(inset, inset, width - inset, height - inset)

        canvas.drawOval(bounds, track)
        if (swept > 0f) {
            // From twelve o'clock, clockwise: the direction people read a dial.
            canvas.drawArc(bounds, -90f, 360f * swept, false, arc)
        }

        crown?.let {
            val size = (width * CROWN_RATIO).toInt()
            val left = (width - size) / 2
            val top = (height - size) / 2
            it.setBounds(left, top, left + size, top + size)
            it.draw(canvas)
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private companion object {
        const val RING_STROKE_DP = 3f
        const val CROWN_RATIO = 0.5f
        const val SWEEP_MS = 1_100L
    }
}
