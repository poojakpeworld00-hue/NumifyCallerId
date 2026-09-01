package com.numify.callerid.lookup.feature.tools

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Speed-test dial: a 240° arc of ticks with a needle over a centre hub.
 *
 * The scale is deliberately NOT linear. Its labelled stops are 0, 25, 50, 75,
 * 100, 150, 200, 300, 500 Mbps spread evenly around the arc, so the low end —
 * where most real connections land — gets as much of the dial as the top end.
 * A linear 0–500 scale would bunch every ordinary reading into the first
 * quarter. [angleFor] interpolates within whichever pair of stops the value
 * falls between.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density * SCALE
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density * SCALE
        color = ContextCompat.getColor(context, R.color.primary)
        alpha = 128
    }

    /** The top of the scale is clay, so "very fast" reads differently at a glance. */
    private val hotTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density * SCALE
        color = ContextCompat.getColor(context, R.color.cid_clay)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 10f * density * SCALE
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.on_surface_variant)
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density * SCALE
        color = ContextCompat.getColor(context, R.color.cid_clay)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density * SCALE
        color = ContextCompat.getColor(context, R.color.surface)
    }

    private var angle = SWEEP_START
    private var animator: ValueAnimator? = null

    /** Moves the needle to [mbps], easing like a real gauge settling. */
    fun setSpeed(mbps: Float, animate: Boolean = true) {
        val target = angleFor(mbps)
        animator?.cancel()
        if (!animate) {
            angle = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(angle, target).apply {
            duration = SETTLE_MS
            interpolator = SETTLE
            addUpdateListener {
                angle = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Maps Mbps onto the dial by interpolating between the labelled stops. */
    private fun angleFor(mbps: Float): Float {
        val v = mbps.coerceIn(0f, STOPS.last())
        val step = SWEEP_TOTAL / (STOPS.size - 1)
        for (i in 0 until STOPS.size - 1) {
            val lo = STOPS[i]
            val hi = STOPS[i + 1]
            if (v <= hi) {
                val within = if (hi == lo) 0f else (v - lo) / (hi - lo)
                return SWEEP_START + step * (i + within)
            }
        }
        return SWEEP_START + SWEEP_TOTAL
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f

        canvas.drawCircle(cx, cy, outer, discPaint)

        val tickOuter = outer - (10f * density)
        val majorLen = 15f * density * SCALE
        val minorLen = 9f * density * SCALE
        val labelRadius = tickOuter - (majorLen + 10f * density)

        // A tick every 15°: majors on the labelled stops, minors between them.
        var i = 0
        var deg = SWEEP_START
        while (deg <= SWEEP_START + SWEEP_TOTAL + 0.01f) {
            val isMajor = i % 2 == 0
            val isHot = deg >= HOT_FROM
            val len = if (isMajor) majorLen else minorLen
            val paint = when {
                isHot -> hotTickPaint.also { it.alpha = if (isMajor) 255 else 128 }
                isMajor -> majorTickPaint
                else -> minorTickPaint
            }
            val rad = Math.toRadians(deg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            canvas.drawLine(
                cx + tickOuter * cosA, cy + tickOuter * sinA,
                cx + (tickOuter - len) * cosA, cy + (tickOuter - len) * sinA,
                paint,
            )
            if (isMajor) {
                val label = STOPS.getOrNull(i / 2)?.toInt()?.toString()
                if (label != null) {
                    // Baseline offset so the number sits centred on its tick.
                    val ty = cy + labelRadius * sinA - (labelPaint.ascent() + labelPaint.descent()) / 2f
                    canvas.drawText(label, cx + labelRadius * cosA, ty, labelPaint)
                }
            }
            i++
            deg += 15f
        }

        // Needle.
        val rad = Math.toRadians(angle.toDouble())
        val needleLen = tickOuter - (14f * density)
        canvas.drawLine(
            cx, cy,
            cx + needleLen * cos(rad).toFloat(),
            cy + needleLen * sin(rad).toFloat(),
            needlePaint,
        )

        val hubRadius = 9f * density * SCALE
        canvas.drawCircle(cx, cy, hubRadius, hubPaint)
        canvas.drawCircle(cx, cy, hubRadius + (2f * density), hubRingPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    private companion object {
        /**
         * Canvas angles run from 3 o'clock, and the design's arc spans -120°..120°
         * measured from 12 o'clock — which is -210°..30° here.
         */
        const val SWEEP_START = -210f
        const val SWEEP_TOTAL = 240f

        /** Where the scale turns clay: the design's last three ticks. */
        const val HOT_FROM = SWEEP_START + 210f

        /** The labelled stops, evenly spaced around the arc. */
        val STOPS = floatArrayOf(0f, 25f, 50f, 75f, 100f, 150f, 200f, 300f, 500f)

        const val SETTLE_MS = 900L
        val SETTLE = PathInterpolator(0.4f, 0f, 0.3f, 1f)

        /** The design's dial is 274dp; ours is smaller, so marks scale with it. */
        const val SCALE = 0.86f
    }
}
