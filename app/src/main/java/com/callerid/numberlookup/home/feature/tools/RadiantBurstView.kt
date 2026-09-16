package com.callerid.numberlookup.home.feature.tools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Light-meter sunburst: a breathing halo with twelve rays around a bright core.
 *
 * The rays pulse on a stagger so the burst reads as light spilling outwards
 * instead of twelve bars blinking in unison. The design achieves that by giving
 * each ray its own animation-delay; here it is a per-index phase offset taken
 * from one animator.
 *
 * [setIntensity] scales both the halo and the ray length with the measured
 * reading, so a dark room genuinely looks dimmer than a bright one rather than
 * being the same drawing with a different number underneath it.
 */
class RadiantBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cid_amber_100)
    }

    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.cid_amber)
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    /** 0f..1f of the whole 3.6s breath. */
    private var phase = 0f

    /** 0f..1f, how bright it is out there. */
    private var intensity = 0.5f

    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3600L
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setIntensity(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == intensity) return
        intensity = clamped
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulse.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulse.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f

        // Halo breathes between 0.94 and 1.0 of its size, scaled by intensity.
        val breath = 0.94f + 0.06f * kotlin.math.sin(phase * 2f * Math.PI).toFloat()
        val haloRadius = outer * (0.62f + 0.20f * intensity) * breath
        canvas.drawCircle(cx, cy, haloRadius, haloPaint)

        val coreRadius = outer * 0.40f
        val rayInner = coreRadius + (7f * density)
        val rayLen = (10f + 12f * intensity) * density

        for (i in 0 until RAYS) {
            // Each ray leads the next by a twelfth of the cycle.
            val rayPhase = (phase + i / RAYS.toFloat()) % 1f
            val swell = 0.7f + 0.3f * kotlin.math.sin(rayPhase * 2f * Math.PI).toFloat()
            rayPaint.alpha = (140 + 115 * swell).toInt().coerceIn(0, 255)

            val angle = Math.toRadians((i * (360f / RAYS)).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + rayInner * cosA, cy + rayInner * sinA,
                cx + (rayInner + rayLen * swell) * cosA, cy + (rayInner + rayLen * swell) * sinA,
                rayPaint,
            )
        }

        canvas.drawCircle(cx, cy, coreRadius, corePaint)
    }

    private companion object {
        const val RAYS = 12
    }
}
