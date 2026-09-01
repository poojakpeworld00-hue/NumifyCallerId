package com.callora.callerid.numberlookup.screen.utility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.callora.callerid.numberlookup.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Analog stopwatch face: a disc, sixty ticks, and a sweep hand over a centre hub.
 *
 * The design lays the ticks out as sixty absolutely-positioned elements. Drawing
 * them is not just shorter — a tick's length and weight depend on whether it is a
 * five-second mark, which as markup means sixty hand-written spans that cannot be
 * re-proportioned when the dial is a different size on a different phone.
 *
 * The hand is driven by [setSeconds] rather than an internal animator: the
 * stopwatch already ticks, and a second clock here would drift against the digits.
 */
class StopwatchFaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.surface)
    }

    /** Every fifth tick: longer, heavier, brand-coloured. */
    private val majorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.primary)
    }

    private val minorTickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.outline)
    }

    /**
     * The sweep hand keeps the design's clay accent instead of the brand hue: it
     * has to read against a dial whose ticks are already brand-coloured.
     */
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3f * density
        color = ContextCompat.getColor(context, R.color.cid_clay)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.on_surface)
    }

    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = ContextCompat.getColor(context, R.color.surface)
    }

    /** Seconds within the current minute, fractional so the hand sweeps smoothly. */
    private var seconds: Float = 0f

    fun setSeconds(value: Float) {
        val wrapped = value % 60f
        if (wrapped == seconds) return
        seconds = wrapped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f

        canvas.drawCircle(cx, cy, outer, discPaint)

        // Ticks hang inward from just inside the rim.
        val tickOuter = outer - (10f * density)
        val majorLen = 12f * density * SCALE
        val minorLen = 7f * density * SCALE
        for (i in 0 until 60) {
            val isMajor = i % 5 == 0
            val len = if (isMajor) majorLen else minorLen
            val angle = Math.toRadians((i * 6f - 90f).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + tickOuter * cosA,
                cy + tickOuter * sinA,
                cx + (tickOuter - len) * cosA,
                cy + (tickOuter - len) * sinA,
                if (isMajor) majorTickPaint else minorTickPaint,
            )
        }

        // Sweep hand: one full turn per minute.
        val handAngle = Math.toRadians((seconds * 6f - 90f).toDouble())
        val handLen = tickOuter - (4f * density)
        canvas.drawLine(
            cx, cy,
            cx + handLen * cos(handAngle).toFloat(),
            cy + handLen * sin(handAngle).toFloat(),
            handPaint,
        )

        val hubRadius = 7f * density
        canvas.drawCircle(cx, cy, hubRadius, hubPaint)
        canvas.drawCircle(cx, cy, hubRadius + (2f * density), hubRingPaint)
    }

    private companion object {
        /**
         * The design's dial is 262dp; ours is smaller on most phones, so tick
         * lengths are scaled to keep the same proportion of the radius rather
         * than reaching too far toward the centre.
         */
        const val SCALE = 0.86f
    }
}
