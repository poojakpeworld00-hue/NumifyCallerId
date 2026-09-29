package com.callerid.numberlookup.home.feature.premium

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.withStyledAttributes
import androidx.core.view.animation.PathInterpolatorCompat
import com.callerid.numberlookup.home.R
import kotlin.math.tan

/**
 * The paywall's `pwShine`: a slanted band of light that crosses its parent, then
 * rests off the far side until the next pass.
 *
 * ```
 * @keyframes pwShine { 0% {translateX(-120%) skewX(-20deg)}
 *                      60%,100% {translateX(320%) skewX(-20deg)} }
 * ```
 *
 * The percentages are of the *band's* width, as CSS translate percentages are,
 * so the band travels from 1.2 widths before its start to 3.2 widths after it:
 * on the crown tile that is the whole tile, on the CTA it is the leading part of
 * the button - both as the design has them. The move is eased over the first 60%
 * of the cycle and the last 40% is the rest.
 *
 * Attributes: `shineWidth` (the band), `shinePeak` (alpha at its centre),
 * `shineDuration` and `shineDelay` (ms, the delay applying to the first pass
 * only, as `animation-delay` does). It draws nothing else, ignores touches, and
 * runs only while attached and visible.
 */
class ShineBandView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var bandWidth = 18f * resources.displayMetrics.density
    private var peak = 0.5f
    private var durationMs = 3_200L
    private var delayMs = 1_000L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skew = tan(Math.toRadians(-20.0)).toFloat()
    private var progress = 0f
    private var animator: ValueAnimator? = null

    /** CSS `ease-in-out`, applied to the moving 60% of the cycle. */
    private val ease = PathInterpolatorCompat.create(0.42f, 0f, 0.58f, 1f)

    init {
        context.withStyledAttributes(attrs, R.styleable.ShineBandView) {
            bandWidth = getDimension(R.styleable.ShineBandView_shineWidth, bandWidth)
            peak = getFloat(R.styleable.ShineBandView_shinePeak, peak)
            durationMs = getInt(R.styleable.ShineBandView_shineDuration, durationMs.toInt()).toLong()
            delayMs = getInt(R.styleable.ShineBandView_shineDelay, delayMs.toInt()).toLong()
        }
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb((peak * 255).toInt(), 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val moved = if (progress < 0.6f) ease.getInterpolation(progress / 0.6f) else 1f
        val x = bandWidth * (-1.2f + 4.4f * moved)
        val h = height.toFloat()
        canvas.save()
        // skewX about the band's centre, the CSS default transform-origin.
        canvas.translate(x + bandWidth / 2f, h / 2f)
        canvas.skew(skew, 0f)
        canvas.translate(-bandWidth / 2f, -h / 2f)
        canvas.drawRect(0f, 0f, bandWidth, h, paint)
        canvas.restore()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            startDelay = delayMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        progress = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow) start() else stop()
    }
}
