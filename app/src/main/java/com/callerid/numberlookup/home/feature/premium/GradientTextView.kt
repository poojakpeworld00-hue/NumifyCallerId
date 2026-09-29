package com.callerid.numberlookup.home.feature.premium

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * Text filled left to right with the paywall's blue → violet gradient - the
 * design's `linear-gradient(90deg, #6E97FF, #A98BFF)` clipped to the glyphs.
 *
 * A static cousin of [ShineTextView]: the gradient is on the paint, so it fills
 * the letters rather than the box, and it spans the text's own width so a
 * translation that runs longer still starts blue and ends violet.
 */
class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val from = ContextCompat.getColor(context, R.color.premium_shine_blue)
    private val to = ContextCompat.getColor(context, R.color.premium_shine_violet)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val textWidth = paint.measureText(text, 0, text.length).coerceAtLeast(1f)
        val start = compoundPaddingLeft.toFloat()
        paint.shader = LinearGradient(start, 0f, start + textWidth, 0f, from, to, Shader.TileMode.CLAMP)
    }
}
