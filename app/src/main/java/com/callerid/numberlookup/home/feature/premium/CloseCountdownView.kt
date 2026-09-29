package com.callerid.numberlookup.home.feature.premium

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.res.ResourcesCompat
import com.callerid.numberlookup.home.R
import kotlin.math.ceil

/**
 * The seconds before the paywall's close button appears, drawn where it will be.
 *
 * The design: a 36dp ring - `conic-gradient(white .85 closeDeg, white .14 0)`
 * masked down to the band outside a 15dp radius - filling clockwise from the top
 * as time passes, with the whole seconds left in Space Grotesk 14 at its centre.
 * When it runs out it calls [onFinished] and the Activity swaps in the button.
 */
class CloseCountdownView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onFinished: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val ringWidth = 3f * density
    private val arc = RectF()

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.argb((0.14f * 255).toInt(), 255, 255, 255)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = Color.argb((0.85f * 255).toInt(), 255, 255, 255)
    }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
    }

    private var totalMs = 0L
    private var leftMs = 0L
    private var animator: ValueAnimator? = null

    /** Runs the countdown for [durationMs]; a zero duration finishes at once. */
    fun start(durationMs: Long) {
        animator?.cancel()
        totalMs = durationMs
        leftMs = durationMs
        if (durationMs <= 0) {
            onFinished?.invoke()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                leftMs = (totalMs * (1f - it.animatedFraction)).toLong()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) {
                        leftMs = 0
                        onFinished?.invoke()
                    }
                }
            })
            start()
        }
    }

    /** Whole seconds left, for the label and for TalkBack. */
    val secondsLeft: Int get() = ceil(leftMs / 1000.0).toInt()

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val inset = ringWidth / 2f
        arc.set(inset, inset, size - inset, size - inset)
        val sweep = if (totalMs > 0) 360f * (1f - leftMs.toFloat() / totalMs) else 360f
        // conic-gradient starts at the top and runs clockwise.
        canvas.drawArc(arc, -90f + sweep, 360f - sweep, false, track)
        if (sweep > 0f) canvas.drawArc(arc, -90f, sweep, false, fill)

        val baseline = size / 2f - (digits.descent() + digits.ascent()) / 2f
        canvas.drawText(secondsLeft.toString(), size / 2f, baseline, digits)
    }
}
