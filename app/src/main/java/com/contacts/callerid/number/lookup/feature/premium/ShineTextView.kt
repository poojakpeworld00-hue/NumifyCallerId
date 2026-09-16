package com.contacts.callerid.number.lookup.feature.premium

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.contacts.callerid.number.lookup.R

/**
 * The word "Premium" in the paywall hero — text filled with a moving gradient.
 *
 * The design writes it as a four-stop gradient laid out at 260% of the word's
 * width, clipped to the glyphs, whose `background-position` slides 100% → 0%
 * over 4.5s, linear, forever, starting 0.9s in:
 *
 * ```
 * background: linear-gradient(100deg,#6E97FF 18%,#A98BFF 40%,#6E97FF 62%,#A98BFF 84%);
 * background-size: 260% 100%;
 * -webkit-background-clip: text;
 * ```
 *
 * A custom view because there is no other way to get it: `android:textColor`
 * takes one colour, a gradient drawable behind the text fills the *box* rather
 * than the letters, and a `<shape>` gradient is baked at inflate time with no
 * position to animate. Putting a [LinearGradient] on the paint fills the glyphs
 * themselves, and translating its local matrix is exactly what
 * `background-position` does.
 *
 * The animation runs only while the view is attached and visible, so a paywall
 * left in the background costs nothing.
 *
 * **One approximation.** The design's gradient axis is `100deg` — ten degrees
 * past horizontal. Here the shader runs corner to corner of the text box, which
 * over a single line lands near five degrees. On a word this wide and this short
 * the two are indistinguishable; matching the angle exactly would mean sizing
 * the shader to a rotated bounding box for no visible gain.
 */
class ShineTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val matrix = Matrix()
    private var animator: ValueAnimator? = null

    /** `background-position`, 1f = 100%, 0f = 0%. */
    private var phase = 1f

    private val shineColors = intArrayOf(
        ContextCompat.getColor(context, R.color.premium_shine_blue),
        ContextCompat.getColor(context, R.color.premium_shine_blue),
        ContextCompat.getColor(context, R.color.premium_shine_violet),
        ContextCompat.getColor(context, R.color.premium_shine_blue),
        ContextCompat.getColor(context, R.color.premium_shine_violet),
        ContextCompat.getColor(context, R.color.premium_shine_violet),
    )

    /**
     * The design's four stops, with the first and last repeated at 0 and 1.
     *
     * CSS clamps a gradient outside its stops to the nearest colour; Android's
     * [LinearGradient] would interpolate from the edge instead, so the flat runs
     * are spelled out rather than left to the shader.
     */
    private val shinePositions = floatArrayOf(0f, 0.18f, 0.40f, 0.62f, 0.84f, 1f)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        paint.shader = LinearGradient(
            0f, 0f, w * GRADIENT_SPAN, h.toFloat(),
            shineColors, shinePositions, Shader.TileMode.CLAMP,
        )
        applyPhase()
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

    /** `shine 4.5s linear 0.9s infinite`. */
    fun start() {
        if (animator != null || visibility != VISIBLE) return
        animator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = CYCLE_MS
            startDelay = START_DELAY_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                phase = it.animatedValue as Float
                applyPhase()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    /**
     * `background-position: <phase>`.
     *
     * At 100% the gradient's right edge meets the text's right edge, which for a
     * background 2.6 times as wide means shifting it left by 1.6 widths; at 0%
     * the left edges meet and the shift is nothing.
     */
    private fun applyPhase() {
        val shader = paint.shader ?: return
        matrix.reset()
        matrix.setTranslate(-phase * width * (GRADIENT_SPAN - 1f), 0f)
        shader.setLocalMatrix(matrix)
        invalidate()
    }

    private companion object {
        /** `background-size: 260%`. */
        const val GRADIENT_SPAN = 2.6f
        const val CYCLE_MS = 4_500L
        const val START_DELAY_MS = 900L
    }
}
