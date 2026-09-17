package com.callerid.numberlookup.home.feature.premium

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * A band of light crossing whatever it is laid over.
 *
 * Same idea as [ShineTextView] and the same reason for existing: a gradient
 * `<shape>` is baked at inflate time with nothing to animate, so the gradient
 * goes on a [Paint] and its local matrix is what moves. Here it fills the view
 * rather than clipping to glyphs.
 *
 * Sits on top of the Premium status card as a sibling, not as its background,
 * so the card keeps its own gold and this only adds light. It draws nothing but
 * the band, ignores touches, and stops the moment it is detached or hidden - a
 * settings page scrolled away costs nothing.
 *
 * The band rests off-screen for most of the cycle. A shine that crosses
 * continuously reads as a loading shimmer; one that passes every few seconds
 * reads as a surface catching the light, which is what this is for.
 */
class ShineSweepView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private var animator: ValueAnimator? = null

    /** 0f = band off the leading edge, 1f = off the trailing edge. */
    private var phase = 0f

    private val edge = ContextCompat.getColor(context, R.color.premium_sweep_edge)
    private val peak = ContextCompat.getColor(context, R.color.premium_sweep_peak)

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Slanted rather than vertical: light crossing a card at an angle looks
        // like a reflection, straight down looks like a scanner.
        paint.shader = LinearGradient(
            0f, h.toFloat(), w * BAND_WIDTH, 0f,
            intArrayOf(edge, peak, edge),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

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

    private fun start() {
        if (animator != null || visibility != VISIBLE) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val shader = paint.shader ?: return

        // The band only crosses during the first stretch of the cycle; the rest
        // is the pause, spent parked past the trailing edge.
        val travel = (phase / SWEEP_FRACTION).coerceAtMost(1f)
        val span = width * (1f + BAND_WIDTH)
        matrix.setTranslate(-width * BAND_WIDTH + span * travel, 0f)
        shader.setLocalMatrix(matrix)

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private companion object {
        /** Band width as a fraction of the view's. */
        const val BAND_WIDTH = 0.42f

        /** How much of the cycle the band is actually moving for. */
        const val SWEEP_FRACTION = 0.55f

        const val CYCLE_MS = 4_200L
    }
}
