package com.callerid.numberlookup.home.feature.premium

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The paywall's dark hero: full bleed, rounded only at the bottom.
 *
 * The design paints it as
 * `linear-gradient(160deg, #2A2154 0%, #12172B 78%)` with two soft glows behind
 * the content - a 280px blue one hanging off the top-right corner and a 260px
 * violet one off the bottom-left, each `radial-gradient(circle, c 0%, transparent
 * 70%)`. A `<shape>` cannot place a gradient stop at 78% or aim it at 160deg, so
 * this draws all three on its own canvas, then lays its children out over them.
 *
 * The 32dp bottom corners are the outline, which also clips the glows the way
 * the design's `overflow:hidden` does.
 */
class PaywallHeroLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private val radius = 32f * density

    private val start = ContextCompat.getColor(context, R.color.premium_hero_start)
    private val end = ContextCompat.getColor(context, R.color.premium_hero_end)
    private val blue = ContextCompat.getColor(context, R.color.paywall_glow_blue)
    private val violet = ContextCompat.getColor(context, R.color.paywall_glow_violet)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowTop = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowBottom = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Pushed up by the radius so only the bottom two corners round.
                outline.setRoundRect(0, -radius.toInt(), view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return

        // CSS's 160deg: a line through the centre, long enough that its ends touch
        // the box's far corners.
        val angle = Math.toRadians(160.0)
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        val half = (abs(w * dx) + abs(h * dy)) / 2f
        val cx = w / 2f
        val cy = h / 2f
        fill.shader = LinearGradient(
            cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
            intArrayOf(start, end), floatArrayOf(0f, 0.78f), Shader.TileMode.CLAMP,
        )

        // right:-80 top:-60, 280px → centre (w - 60, 80), radius 140.
        glowTop.shader = RadialGradient(
            w - 60f * density, 80f * density, 140f * density,
            intArrayOf(blue, blue and 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
        )
        // left:-90 bottom:-110, 260px → centre (40, h - 20), radius 130.
        glowBottom.shader = RadialGradient(
            40f * density, h - 20f * density, 130f * density,
            intArrayOf(violet, violet and 0x00FFFFFF), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fill)
        canvas.drawRect(0f, 0f, w, h, glowTop)
        canvas.drawRect(0f, 0f, w, h, glowBottom)
        super.onDraw(canvas)
    }
}
