package com.contacts.callerid.number.lookup.feature.tools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import com.contacts.callerid.number.lookup.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Half-dial for the sound meter: a 180-degree track, the measured arc drawn over
 * it, a needle, and labelled decibel stops.
 *
 * Being a half-circle, the view measures its height as half its width. A square
 * box would leave the bottom half of the canvas empty and push everything beneath
 * it down by that same amount again.
 */
class NoiseGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val stroke = 12f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = ContextCompat.getColor(context, R.color.primary_container)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.on_surface_variant)
    }

    private val oval = RectF()

    /** 0f..1f across the dial's dB range. */
    private var level = 0f
    private var animator: ValueAnimator? = null

    fun setDb(db: Float) {
        val target = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(level, target).apply {
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                level = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Half-circle plus room for the labels that sit inside the arc.
        setMeasuredDimension(w, (w / 2f + 10f * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = width / 2f
        val radius = cx - stroke / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(oval, 180f, 180f, false, trackPaint)
        if (level > 0f) canvas.drawArc(oval, 180f, 180f * level, false, arcPaint)

        // Labelled stops, drawn inside the arc.
        val labelRadius = radius - stroke - (10f * density)
        STOPS.forEachIndexed { i, db ->
            val t = i / (STOPS.size - 1f)
            val angle = Math.toRadians((180f + 180f * t).toDouble())
            val x = cx + labelRadius * cos(angle).toFloat()
            val y = cy + labelRadius * sin(angle).toFloat() -
                    (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(db.toString(), x, y, labelPaint)
        }

        val angle = Math.toRadians((180f + 180f * level).toDouble())
        val needleLen = radius - stroke
        canvas.drawLine(
            cx, cy,
            cx + needleLen * cos(angle).toFloat(),
            cy + needleLen * sin(angle).toFloat(),
            needlePaint,
        )
        canvas.drawCircle(cx, cy, 8f * density, hubPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private companion object {
        const val MIN_DB = 20f
        const val MAX_DB = 120f
        val STOPS = intArrayOf(30, 50, 70, 90, 110)
    }
}
