package com.numify.callerid.numberlookup.screen.utility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.numify.callerid.numberlookup.R
import java.util.Random

/**
 * Lightweight audio-level visualizer: a row of bars whose heights react to the
 * current sound level. Not a true FFT — there's no PCM/frequency data from the
 * amplitude meter — so it's a stylized spectrum.
 */
class AudioWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barCount = 30
    private val values = FloatArray(barCount)
    private val random = Random()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.primary)
        style = Paint.Style.FILL
    }
    private var level = 0f

    /** Push a new 0..1 level; bars ease toward fresh random heights scaled by it. */
    fun setLevel(value: Float) {
        level = value.coerceIn(0f, 1f)
        for (i in 0 until barCount) {
            val target = level * (0.2f + random.nextFloat() * 0.8f)
            values[i] += (target - values[i]) * 0.5f
        }
        invalidate()
    }

    fun reset() {
        values.fill(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val slot = w / barCount
        val bar = slot * 0.6f
        for (i in 0 until barCount) {
            val bh = (values[i].coerceIn(0.03f, 1f)) * h
            val left = i * slot + (slot - bar) / 2f
            val top = h - bh
            canvas.drawRoundRect(left, top, left + bar, h, bar / 2f, bar / 2f, paint)
        }
    }
}
