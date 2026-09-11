package com.numify.callerid.lookup.feature.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compass dial: a face of rings and tick marks, N/E/S/W lettering that turns with
 * the heading, and a fixed two-tone needle.
 *
 * The rose rotates while the needle stays put, which is what a real compass does
 * and means north is simply wherever the red half points - no reading a number to
 * work out which way you are facing.
 *
 * [setHeading] smooths across the 359-to-0 wrap. Feeding raw sensor values in
 * unsmoothed sends the rose spinning the long way round every time the user
 * crosses north.
 */
class CompassRoseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ds_surface)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.ds_nav_chip_idle)
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.ds_rule)
    }

    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.ds_ink_muted)
    }

    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        isFakeBoldText = true
        color = ContextCompat.getColor(context, R.color.ds_danger)
    }

    private val needleNorthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ds_danger)
    }

    private val needleSouthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ds_accent)
    }

    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.ds_ink)
    }

    private val hubRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = ContextCompat.getColor(context, R.color.ds_surface)
    }

    private val needle = Path()

    /** Where the rose is drawn, in degrees. Lags the sensor, deliberately. */
    private var roseAngle = 0f

    /**
     * Eases the rose towards [heading], taking the short way round the circle.
     *
     * The sensor is noisy enough that drawing it raw jitters a degree or two even
     * on a still table, so this low-pass is what keeps the dial calm.
     */
    fun setHeading(heading: Float) {
        var delta = (-heading) - roseAngle
        // Take the shorter arc, so crossing north does not unwind 359°.
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        if (abs(delta) < 0.15f) return
        roseAngle += delta * SMOOTHING
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f

        canvas.drawCircle(cx, cy, outer, facePaint)
        canvas.drawCircle(cx, cy, outer - (10f * density), ringPaint)
        canvas.drawCircle(cx, cy, outer - (34f * density), ringPaint)

        // Rose: ticks and letters turn together.
        canvas.save()
        canvas.rotate(roseAngle, cx, cy)

        val tickOuter = outer - (14f * density)
        for (i in 0 until 36) {
            val isCardinal = i % 9 == 0
            val len = if (isCardinal) 10f * density else 5f * density
            val angle = Math.toRadians((i * 10f - 90f).toDouble())
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + tickOuter * cosA, cy + tickOuter * sinA,
                cx + (tickOuter - len) * cosA, cy + (tickOuter - len) * sinA,
                tickPaint,
            )
        }

        val letterRadius = outer - (34f * density)
        CARDINALS.forEachIndexed { i, label ->
            val angle = Math.toRadians((i * 90f - 90f).toDouble())
            val x = cx + letterRadius * cos(angle).toFloat()
            val y = cy + letterRadius * sin(angle).toFloat() -
                    (cardinalPaint.ascent() + cardinalPaint.descent()) / 2f
            // Counter-rotate each letter so it stays upright as the rose turns.
            canvas.save()
            canvas.rotate(-roseAngle, x, y)
            canvas.drawText(label, x, y, if (i == 0) northPaint else cardinalPaint)
            canvas.restore()
        }
        canvas.restore()

        // Needle stays put: it is the phone's own heading line.
        val len = outer * 0.58f
        val halfW = 8f * density
        needle.reset()
        needle.moveTo(cx, cy - len)
        needle.lineTo(cx - halfW, cy)
        needle.lineTo(cx + halfW, cy)
        needle.close()
        canvas.drawPath(needle, needleNorthPaint)

        needle.reset()
        needle.moveTo(cx, cy + len)
        needle.lineTo(cx - halfW, cy)
        needle.lineTo(cx + halfW, cy)
        needle.close()
        canvas.drawPath(needle, needleSouthPaint)

        val hubR = 8f * density
        canvas.drawCircle(cx, cy, hubR, hubPaint)
        canvas.drawCircle(cx, cy, hubR + (2f * density), hubRingPaint)
    }

    private companion object {
        val CARDINALS = arrayOf("N", "E", "S", "W")

        /** Fraction of the remaining error applied per sensor sample. */
        const val SMOOTHING = 0.18f
    }
}
