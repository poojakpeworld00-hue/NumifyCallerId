package com.numify.callerid.lookup.feature.tools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R

/**
 * The battery graphic: a rounded cell with a cap, filling from the bottom up.
 *
 * The fill is drawn rather than laid out because it has to be clipped to the
 * cell's rounded corners; a child View with a percentage height would square off
 * against them along the bottom.
 *
 * While charging, a soft band rides the top of the fill. That band is the only
 * thing on screen saying "still going up", so it is tied to [charging] instead of
 * running unconditionally.
 */
class BatteryGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val strokeW = 5f * density
    private val corner = 22f * density
    private val capW = 40f * density
    private val capH = 9f * density

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeW
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    /** The unfilled portion of the cell. Deliberately not `surface`: the page behind
     *  it is almost the same near-white, so a surface-filled cell would read as a
     *  hole in the page rather than as an empty battery. */
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface_2)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.primary)
        alpha = 90
    }

    private val body = RectF()
    private val cap = RectF()
    private val fill = RectF()

    private var level = 0f
    private var charging = false

    /** 0f..1f up the charge band, only meaningful while charging. */
    private var bandPhase = 0f
    private var bandAnimator: ValueAnimator? = null
    private var levelAnimator: ValueAnimator? = null

    /**
     * True once the fill has passed the cell's vertical centre, meaning the
     * readout printed across the middle now sits on the fill rather than on the
     * empty cell. Callers use it to choose a legible text colour.
     */
    fun fillCoversCentre(percent: Int): Boolean = percent >= 50

    fun setLevel(percent: Int, isCharging: Boolean, animate: Boolean = true) {
        val target = (percent / 100f).coerceIn(0f, 1f)
        charging = isCharging
        syncBand()

        levelAnimator?.cancel()
        if (!animate) {
            level = target
            invalidate()
            return
        }
        levelAnimator = ValueAnimator.ofFloat(level, target).apply {
            duration = 700L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                level = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun syncBand() {
        if (charging && bandAnimator == null) {
            bandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2400L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    bandPhase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else if (!charging) {
            bandAnimator?.cancel()
            bandAnimator = null
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val bodyTop = capH + (2f * density)
        val half = strokeW / 2f

        body.set(half, bodyTop + half, width - half, height - half)
        cap.set(cx - capW / 2f, 0f, cx + capW / 2f, capH + corner)

        // Cap first, clipped by the body drawn over it, so only its top shows.
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), bodyTop)
        canvas.drawRoundRect(cap, 5f * density, 5f * density, capPaint)
        canvas.restore()

        canvas.drawRoundRect(body, corner, corner, cellPaint)

        // Fill, clipped to the cell so it takes the rounded bottom corners.
        if (level > 0f) {
            canvas.save()
            val clip = android.graphics.Path().apply {
                addRoundRect(body, corner, corner, android.graphics.Path.Direction.CW)
            }
            canvas.clipPath(clip)

            val fillTop = body.bottom - body.height() * level
            fill.set(body.left, fillTop, body.right, body.bottom)
            fillPaint.shader = LinearGradient(
                0f, fillTop, 0f, body.bottom,
                ContextCompat.getColor(context, R.color.primary),
                ContextCompat.getColor(context, R.color.primary_dark),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(fill, fillPaint)

            if (charging) {
                // Rides from the fill line upward, fading as it goes.
                val bandH = 10f * density
                val travel = body.height() * 0.16f
                val y = fillTop - travel * bandPhase
                bandPaint.alpha = (90 * (1f - bandPhase)).toInt().coerceAtLeast(0)
                canvas.drawRect(body.left, y, body.right, y + bandH, bandPaint)
            }
            canvas.restore()
        }

        canvas.drawRoundRect(body, corner, corner, shellPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        bandAnimator?.cancel()
        bandAnimator = null
        levelAnimator?.cancel()
    }
}
