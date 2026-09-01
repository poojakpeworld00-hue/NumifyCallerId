package com.numify.callerid.numberlookup.screen.shared

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.numify.callerid.numberlookup.R

/**
 * Reusable empty-state icon used by the Blocklist and Tools screens.
 *
 * Renders (back-to-front) an expanding ring, a soft disc and a centered glyph.
 * On attach it runs two looping animations that mirror the Claude Design:
 *  • the disc + glyph "breathe" — scale 1 → 1.05 → 1 over 3s (ease-in-out, reverse),
 *  • a ring expands 0.7 → 1.5 while fading 0.55 → 0 over 3s (ease-out, restart).
 *
 * Everything is theme-token driven via drawables, so it flips light/dark for free.
 */
class PulseIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val ring = ImageView(context)
    private val pulse = FrameLayout(context)
    private val icon = ImageView(context)

    private var breathAnimator: ValueAnimator? = null
    private var ringAnimator: ValueAnimator? = null

    init {
        // The ring expands to 1.5× — let it draw past our bounds instead of being
        // clipped into a hard square ("cut" animation).
        clipChildren = false
        clipToPadding = false

        val a = context.obtainStyledAttributes(attrs, R.styleable.PulseIconView)
        val iconRes = a.getResourceId(R.styleable.PulseIconView_pulse_icon, 0)
        val discRes = a.getResourceId(
            R.styleable.PulseIconView_pulse_disc, R.drawable.shape_empty_circle_tools
        )
        val ringRes = a.getResourceId(
            R.styleable.PulseIconView_pulse_ring, R.drawable.shape_ring_tools
        )
        val iconSize = a.getDimensionPixelSize(R.styleable.PulseIconView_pulse_iconSize, 0)
        val hasTint = a.hasValue(R.styleable.PulseIconView_pulse_iconTint)
        val tint = a.getColor(R.styleable.PulseIconView_pulse_iconTint, 0)
        a.recycle()

        // Ring — fills the whole view, sits behind everything.
        ring.setImageDrawable(ContextCompat.getDrawable(context, ringRes))
        ring.scaleType = ImageView.ScaleType.FIT_XY
        addView(ring, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Pulsing disc + centered glyph.
        pulse.background = ContextCompat.getDrawable(context, discRes)
        addView(pulse, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        if (iconRes != 0) icon.setImageResource(iconRes)
        if (hasTint) icon.setColorFilter(tint)
        val iconLp = LayoutParams(
            if (iconSize > 0) iconSize else LayoutParams.WRAP_CONTENT,
            if (iconSize > 0) iconSize else LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        pulse.addView(icon, iconLp)
    }

    fun setIcon(resId: Int) = icon.setImageResource(resId)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startBreathing()
        startRing()
    }

    override fun onDetachedFromWindow() {
        breathAnimator?.cancel(); breathAnimator = null
        ringAnimator?.cancel(); ringAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startBreathing() {
        if (breathAnimator != null) return
        breathAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val s = it.animatedValue as Float
                pulse.scaleX = s
                pulse.scaleY = s
            }
            start()
        }
    }

    private fun startRing() {
        if (ringAnimator != null) return
        ringAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val scale = 0.7f + (1.5f - 0.7f) * f
                ring.scaleX = scale
                ring.scaleY = scale
                ring.alpha = 0.55f * (1f - f)
            }
            start()
        }
    }
}
