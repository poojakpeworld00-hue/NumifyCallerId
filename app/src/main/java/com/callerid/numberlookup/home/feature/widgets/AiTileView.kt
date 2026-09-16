package com.callerid.numberlookup.home.feature.widgets

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * The "Ask AI" tile on Home, with the three loops the design gives it.
 *
 * The handoff animates it in CSS as:
 *
 *  - `aiShimmer` — a four-stop gradient laid out at 300% of the tile's width, whose
 *    `background-position` slides 0% → 200% over 3.5s, linear, forever. That is a
 *    gradient wider than the view being scrolled behind it, which is why this is a
 *    custom view: a `<shape>` gradient is baked at inflate time and has no position
 *    to animate.
 *  - `aiGlow` — the drop shadow breathing between a tight blue and a wider violet
 *    on a 2s ease-in-out. Reproduced as an elevation pulse: Android renders shadows
 *    from the outline, so growing the elevation is what actually spreads the shadow.
 *  - `twinkle` — the sparkle scaling to 1.2, rotating 12 degrees and dropping to
 *    0.75 alpha on a 1.5s ease-in-out. Applied to the single child, so the tile
 *    animates whatever glyph is put inside it.
 *
 * All three start on attach and are cancelled on detach, so a tile scrolled off
 * screen or a destroyed fragment leaves nothing running.
 */
class AiTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private companion object {
        const val CORNER_RADIUS_DP = 16f

        /** `background-size: 300% 100%`. */
        const val GRADIENT_SPAN = 3f

        /** `aiShimmer 3.5s linear infinite`. */
        const val SHIMMER_MS = 3_500L

        /** `aiGlow 2s ease-in-out infinite`, as an elevation pulse. */
        const val GLOW_MS = 2_000L
        const val GLOW_MIN_DP = 8f
        const val GLOW_MAX_DP = 14f

        /** `twinkle 1.5s ease-in-out infinite`. */
        const val TWINKLE_MS = 1_500L
        const val TWINKLE_SCALE = 1.2f
        const val TWINKLE_ROTATION = 12f
        const val TWINKLE_ALPHA = 0.75f
    }

    private val density = resources.displayMetrics.density
    private val cornerRadius = CORNER_RADIUS_DP * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val shaderMatrix = Matrix()

    /** The design's stops, with the first repeated so the loop has no seam. */
    private val stops = intArrayOf(
        color(R.color.ds_ai_1),
        color(R.color.ds_ai_2),
        color(R.color.ds_ai_3),
        color(R.color.ds_ai_1),
    )

    private var gradient: LinearGradient? = null

    /** 0..1 through one shimmer cycle. */
    private var phase = 0f

    private val animators = mutableListOf<Animator>()

    init {
        setWillNotDraw(false)
        elevation = GLOW_MIN_DP * density
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Three tile-widths of gradient, so there is something to slide.
        gradient = LinearGradient(
            0f, 0f, w * GRADIENT_SPAN, 0f,
            stops, null, Shader.TileMode.REPEAT,
        )
        paint.shader = gradient
        // The outline is what Android casts the shadow from, so it has to match the
        // rounded rect drawn below or the glow would be a rectangle.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // `background-position: 0% → 200%` across a 300%-wide gradient.
        shaderMatrix.reset()
        shaderMatrix.setTranslate(-phase * w * (GRADIENT_SPAN - 1f), 0f)
        gradient?.setLocalMatrix(shaderMatrix)

        bounds.set(0f, 0f, w, h)
        canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, paint)
        super.onDraw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoops()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoops()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // The tile is GONE until the assistant is enabled; there is no reason to
        // run three loops behind a hidden view.
        if (visibility == VISIBLE && isAttachedToWindow) startLoops() else stopLoops()
    }

    private fun startLoops() {
        if (animators.isNotEmpty() || visibility != VISIBLE) return

        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHIMMER_MS
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        animators += ValueAnimator.ofFloat(GLOW_MIN_DP * density, GLOW_MAX_DP * density).apply {
            duration = GLOW_MS
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { elevation = it.animatedValue as Float }
            start()
        }

        glyph()?.let { sparkle ->
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                duration = TWINKLE_MS
                interpolator = AccelerateDecelerateInterpolator()
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    val t = it.animatedValue as Float
                    val scale = 1f + (TWINKLE_SCALE - 1f) * t
                    sparkle.scaleX = scale
                    sparkle.scaleY = scale
                    sparkle.rotation = TWINKLE_ROTATION * t
                    sparkle.alpha = 1f - (1f - TWINKLE_ALPHA) * t
                }
                start()
            }
        }
    }

    private fun stopLoops() {
        animators.forEach { it.cancel() }
        animators.clear()
        glyph()?.apply {
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
            alpha = 1f
        }
    }

    /**
     * The thing the twinkle is applied to: the first ImageView inside the tile.
     *
     * It used to be `getChildAt(0)`, which was the sparkle back when the tile
     * held nothing else. Now that it can carry an "Ask AI" label beside the
     * glyph, animating the first child would scale and — worse — *rotate* the
     * words with it.
     */
    private fun glyph(): View? = firstImage(this)

    private fun firstImage(view: View): View? = when (view) {
        is ImageView -> view
        is ViewGroup -> (0 until view.childCount)
            .firstNotNullOfOrNull { firstImage(view.getChildAt(it)) }
        else -> null
    }
}
