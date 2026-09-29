package com.callerid.numberlookup.home.feature.intro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.ColorUtils
import com.callerid.numberlookup.home.R

/**
 * The intro page's hero panel.
 *
 * The design paints it as `radial-gradient(120% 90% at 30% 20%, wash 0%, mid 55%,
 * page 100%)` under a 1dp border, with two decorative rings behind the mini screen:
 * a solid 300dp circle at (-60, -40) and a dashed 200dp one at (-10, 10). A
 * GradientDrawable only draws circular radial gradients, and this one is an
 * ellipse - 120% of the width across, 90% of the height down - so the wash is a
 * unit-radius RadialGradient scaled into that ellipse by the shader's matrix.
 *
 * Children are clipped to the 28dp outline, which is the design's
 * `overflow:hidden`: the mini screen bleeds off the bottom edge and the floating
 * card's shadow stops at the panel, exactly as they do there.
 */
class IntroHeroLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val radius = 28f * density

    private var wash = ContextCompat.getColor(context, R.color.intro_wash_lookup)
    private var washMid = ContextCompat.getColor(context, R.color.intro_wash_lookup_mid)
    private val page = ContextCompat.getColor(context, R.color.ds_page)
    private var border = ContextCompat.getColor(context, R.color.ds_hero_blue_border)
    private var accent = ContextCompat.getColor(context, R.color.ds_accent)
    private var ringAlpha = 0.10f
    private var dashAlpha = 0.18f

    private val bounds = RectF()
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val washMatrix = Matrix()
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        // A 1px CSS dashed border draws 3px dashes with 3px gaps.
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }

    init {
        context.withStyledAttributes(attrs, R.styleable.IntroHeroLayout) {
            wash = getColor(R.styleable.IntroHeroLayout_introWash, wash)
            washMid = getColor(R.styleable.IntroHeroLayout_introWashMid, washMid)
            border = getColor(R.styleable.IntroHeroLayout_introBorder, border)
            accent = getColor(R.styleable.IntroHeroLayout_introAccent, accent)
            ringAlpha = getFloat(R.styleable.IntroHeroLayout_introRingAlpha, ringAlpha)
            dashAlpha = getFloat(R.styleable.IntroHeroLayout_introDashAlpha, dashAlpha)
        }
        setWillNotDraw(false)
        borderPaint.color = border
        ringPaint.color = withAlpha(accent, ringAlpha)
        dashPaint.color = withAlpha(accent, dashAlpha)
        washPaint.shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(wash, washMid, page),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
        // The outline is the only clip. Per-child clipping would cut the floating
        // card's top off as it rises out of the frame that slides it in.
        clipChildren = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        // "120% 90% at 30% 20%": the ellipse's radii and centre, as fractions of the box.
        washMatrix.reset()
        washMatrix.setScale(1.2f * w, 0.9f * h)
        washMatrix.postTranslate(0.3f * w, 0.2f * h)
        washPaint.shader.setLocalMatrix(washMatrix)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(bounds, radius, radius, washPaint)

        // CSS draws a border inside the box, so the stroke is centred half a
        // pixel in from the edge rather than straddling it.
        val inset = borderPaint.strokeWidth / 2f
        canvas.drawRoundRect(
            bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset,
            radius - inset, radius - inset, borderPaint,
        )

        // Both rings share a centre: (-60 + 150, -40 + 150) and (-10 + 100, 10 + 100).
        val cx = 90f * density
        val cy = 110f * density
        canvas.drawCircle(cx, cy, 150f * density - inset, ringPaint)
        canvas.drawCircle(cx, cy, 100f * density - inset, dashPaint)
        super.onDraw(canvas)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        ColorUtils.setAlphaComponent(color, (Color.alpha(color) * alpha).toInt())
}
