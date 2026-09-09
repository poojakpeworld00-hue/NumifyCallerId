package com.numify.callerid.lookup.feature.splash

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.common.DesignEasing
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos

/**
 * The splash's centrepiece: three frosted call cards that fly in from off-screen,
 * land in a fanned stack, and then flip the top one over to a "Verified" face.
 *
 * All three cards live in one View rather than being three layouts the Activity
 * animates. The design draws them as siblings in a single absolutely-positioned
 * stack whose paths cross and overlap, and the flip is a `rotateY` with a content
 * swap at the halfway point — expressing that as nested ViewGroups would mean
 * three shadow layers, six faces and a z-order fight, where one `onDraw` is a
 * couple of dozen primitives per frame and stays comfortably inside a frame budget.
 *
 * ### Coordinates
 * Everything is authored in the design's own units: a 396×812 content box with the
 * stack anchored at (198, 300), and a 140×90 card measured from its own centre. The
 * canvas is scaled by `width / 396` before any card is drawn, so the composition
 * keeps the design's proportions on every screen instead of growing chunkier as the
 * device gets narrower.
 *
 * ### The flip
 * The design writes `transform: rotateY(…)` with no `perspective` anywhere in the
 * tree. CSS renders that orthographically — no foreshortening, the card simply
 * squashes to `cos(angle)` of its width and comes back mirrored — which is why the
 * back face carries its own `rotateY(180deg)` to read the right way round. That is
 * reproduced literally: a `scale(cos, 1)` on the card and a counter-mirror on the
 * back face, no `Camera` and no perspective divide.
 */
class CallCardStackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        // ── The design's content box and the stack's anchor inside it ────────
        const val DESIGN_W = 396f
        const val DESIGN_H = 812f
        const val ANCHOR_Y = 300f

        // ── Card box ────────────────────────────────────────────────────────
        const val CARD_W = 140f
        const val CARD_H = 90f
        const val CARD_HALF_W = CARD_W / 2f
        const val CARD_HALF_H = CARD_H / 2f
        const val CARD_RADIUS = 18f
        const val CARD_BORDER = 1f

        /** `box-shadow: 0 12px 26px` — CSS blur radius is 2σ, hence σ = 13. */
        const val SHADOW_DY = 12f
        const val SHADOW_SIGMA = 13f

        // ── Card contents, derived from the flex row ─────────────────────────
        // Content box = 140 − 2·border − 2·16 padding = 106 wide, laid out as
        // avatar 30 · gap 10 · text column (flex:1) · gap 10 · badge 24.
        const val CONTENT_LEFT = -53f
        const val AVATAR_R = 15f
        const val AVATAR_CX = CONTENT_LEFT + AVATAR_R
        const val COLUMN_LEFT = CONTENT_LEFT + 30f + 10f
        const val COLUMN_W = 32f
        const val LINE_H = 7f
        const val LINE_GAP = 6f
        const val LINE_RADIUS = LINE_H / 2f
        const val LINE_TOP_W = COLUMN_W * 0.70f
        const val LINE_BOTTOM_W = COLUMN_W * 0.45f
        const val BADGE_R = 12f
        const val BADGE_CX = 41f
        const val BADGE_ICON_SIZE = 13f

        // ── Verified face ────────────────────────────────────────────────────
        const val VERIFIED_ICON_SIZE = 20f
        const val VERIFIED_GAP = 8f
        const val VERIFIED_TEXT_SIZE = 15f

        /** Icons are authored in a 24×24 viewBox with a 2.2 stroke. */
        const val ICON_VIEWBOX = 24f
        const val ICON_STROKE = 2.2f

        /** Past this the card is edge-on and there is nothing left to draw. */
        const val FLIP_EPSILON = 0.001f
    }

    /**
     * One card's authored motion: where it flies in from, where it settles, and
     * when. Straight from the design's `CARDS` array.
     */
    private class CardSpec(
        val icon: Path,
        val iconColor: Int,
        val fromX: Float,
        val fromY: Float,
        val fromRot: Float,
        val toX: Float,
        val toY: Float,
        val toRot: Float,
        val delay: Long,
        val flips: Boolean,
    ) {
        /** 0 → 1 along the fly-in path (easeOutBack, so it overshoots past 1). */
        var travel = 0f

        /** 0 → 1 opacity, on its own shorter ramp. */
        var fade = 0f

        /** Degrees of Y rotation; only the top card ever leaves 0. */
        var flipDeg = 0f
    }

    // ── Icon paths, in the design's 24×24 viewBox ────────────────────────────

    /** `M12 3a9 9 0 100 18 9 9 0 000-18zM6.5 6.5l11 11` — circle with a slash. */
    private val iconBlock = Path().apply {
        addCircle(12f, 12f, 9f, Path.Direction.CW)
        moveTo(6.5f, 6.5f)
        lineTo(17.5f, 17.5f)
    }

    /** `M12 4l9 15H3l9-15zM12 10v4M12 17h.01` — warning triangle with a bang. */
    private val iconWarn = Path().apply {
        moveTo(12f, 4f)
        lineTo(21f, 19f)
        lineTo(3f, 19f)
        close()
        moveTo(12f, 10f)
        lineTo(12f, 14f)
        // A hair of a line under a round cap is how the design draws the dot.
        moveTo(12f, 17f)
        lineTo(12.01f, 17f)
    }

    /** `M5 12.5l4.5 4.5L19 7` — the check. */
    private val iconCheck = Path().apply {
        moveTo(5f, 12.5f)
        lineTo(9.5f, 17f)
        lineTo(19f, 7f)
    }

    // ── Palette ─────────────────────────────────────────────────────────────

    private val colorGlass = color(R.color.splash_cs_card_glass)
    private val colorGlassBorder = color(R.color.splash_cs_card_border)
    private val colorVerified = color(R.color.splash_cs_card_verified)
    private val colorVerifiedBorder = color(R.color.splash_cs_card_verified_border)
    private val colorAvatar = color(R.color.splash_cs_avatar)
    private val colorLineStrong = color(R.color.splash_cs_line_strong)
    private val colorLineSoft = color(R.color.splash_cs_line_soft)
    private val colorBadgeWell = color(R.color.splash_cs_badge_well)
    private val colorShadow = color(R.color.splash_cs_card_shadow)
    private val colorAccent = color(R.color.splash_cs_accent)
    private val colorInk = color(R.color.splash_cs_ink)

    // ── Paints ──────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = CARD_BORDER
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ICON_STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = VERIFIED_TEXT_SIZE
        typeface = ResourcesCompat.getFont(context, R.font.space_grotesk_bold)
            ?: Typeface.DEFAULT_BOLD
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val verifiedLabel: String = context.getString(R.string.splash_card_verified)

    private val cards = listOf(
        CardSpec(
            icon = iconBlock,
            iconColor = color(R.color.splash_cs_icon_info),
            fromX = -240f, fromY = -60f, fromRot = -32f,
            toX = -16f, toY = -14f, toRot = -9f,
            delay = SplashTimeline.CARD_DELAYS[0], flips = false,
        ),
        CardSpec(
            icon = iconWarn,
            iconColor = colorAccent,
            fromX = 260f, fromY = -30f, fromRot = 26f,
            toX = 14f, toY = -6f, toRot = 7f,
            delay = SplashTimeline.CARD_DELAYS[1], flips = false,
        ),
        CardSpec(
            icon = iconCheck,
            iconColor = colorAccent,
            fromX = 0f, fromY = -220f, fromRot = 0f,
            toX = 0f, toY = 0f, toRot = 0f,
            delay = SplashTimeline.CARD_DELAYS[2], flips = true,
        ),
    )

    /** Rebuilt on resize: the blurred drop shadow, shared by all three cards. */
    private var shadowBitmap: Bitmap? = null
    private var shadowScale = 0f

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildShadow(if (w > 0) w / DESIGN_W else 0f)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        shadowBitmap?.recycle()
        shadowBitmap = null
        shadowScale = 0f
    }

    /**
     * Renders the drop shadow once into a bitmap so the per-frame cost is a single
     * `drawBitmap`. A `BlurMaskFilter` cannot run on a hardware canvas at all, and
     * `setShadowLayer` would force this View into a software layer that
     * re-rasterises on every frame of the fly-in.
     */
    private fun buildShadow(scale: Float) {
        if (scale <= 0f) return
        if (shadowBitmap != null && abs(scale - shadowScale) < 0.001f) return

        shadowBitmap?.recycle()
        shadowScale = scale

        val sigma = SHADOW_SIGMA * scale
        val pad = ceil(sigma * 3f).toInt().coerceAtLeast(1)
        val boxW = CARD_W * scale
        val boxH = CARD_H * scale
        val bitmap = Bitmap.createBitmap(
            (boxW + pad * 2).toInt().coerceAtLeast(1),
            (boxH + pad * 2).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colorShadow
            maskFilter = BlurMaskFilter(sigma, BlurMaskFilter.Blur.NORMAL)
        }
        Canvas(bitmap).drawRoundRect(
            pad.toFloat(), pad.toFloat(), pad + boxW, pad + boxH,
            CARD_RADIUS * scale, CARD_RADIUS * scale, paint,
        )
        shadowBitmap = bitmap
    }

    // ── Timeline ────────────────────────────────────────────────────────────

    /**
     * Builds the stack's whole intro — three staggered fly-ins plus the top card's
     * flip — as one [AnimatorSet]. The caller starts it and owns cancelling it, so
     * the Activity keeps the single list of animators it tears down in `onDestroy`.
     */
    fun buildIntroAnimator(): Animator {
        val animators = mutableListOf<Animator>()
        cards.forEach { card ->
            card.travel = 0f
            card.fade = 0f
            card.flipDeg = 0f

            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = card.delay
                duration = SplashTimeline.CARD_TRAVEL_DURATION
                interpolator = DesignEasing.easeOutBack
                addUpdateListener {
                    card.travel = it.animatedValue as Float
                    invalidate()
                }
            }
            animators += ValueAnimator.ofFloat(0f, 1f).apply {
                startDelay = card.delay
                duration = SplashTimeline.CARD_FADE_DURATION
                interpolator = DesignEasing.easeInOutCubic
                addUpdateListener {
                    card.fade = it.animatedValue as Float
                    invalidate()
                }
            }
            if (card.flips) {
                animators += ValueAnimator.ofFloat(0f, SplashTimeline.FLIP_DEGREES).apply {
                    startDelay = SplashTimeline.FLIP_START
                    duration = SplashTimeline.FLIP_DURATION
                    interpolator = DesignEasing.easeInOutCubic
                    addUpdateListener {
                        card.flipDeg = it.animatedValue as Float
                        invalidate()
                    }
                }
            }
        }
        return AnimatorSet().apply { playTogether(animators) }
    }

    /**
     * The stack as it looks once every entrance has run — landed, opaque, top card
     * already turned. Used when the user has system animations switched off, so
     * they still get the composed screen rather than an empty one.
     */
    fun showSettledFrame() {
        cards.forEach { card ->
            card.travel = 1f
            card.fade = 1f
            card.flipDeg = if (card.flips) SplashTimeline.FLIP_DEGREES else 0f
        }
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val scale = w / DESIGN_W
        val anchorX = w / 2f
        val anchorY = h * (ANCHOR_Y / DESIGN_H)

        cards.forEach { card ->
            if (card.fade <= 0f) return@forEach

            val travel = card.travel
            val cx = anchorX + lerp(card.fromX, card.toX, travel) * scale
            val cy = anchorY + lerp(card.fromY, card.toY, travel) * scale
            val rotation = lerp(card.fromRot, card.toRot, travel)

            // No perspective in the design, so the flip is a plain horizontal
            // squash that goes negative — and mirrors — once past 90 degrees.
            val flipScale = cos(Math.toRadians(card.flipDeg.toDouble())).toFloat()
            if (abs(flipScale) < FLIP_EPSILON) return@forEach

            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotation)
            canvas.scale(flipScale, 1f)

            shadowBitmap?.let { bitmap ->
                shadowPaint.alpha = (255 * card.fade).toInt().coerceIn(0, 255)
                canvas.drawBitmap(
                    bitmap,
                    -bitmap.width / 2f,
                    -bitmap.height / 2f + SHADOW_DY * scale,
                    shadowPaint,
                )
            }

            // From here on the canvas speaks the design's own units.
            canvas.scale(scale, scale)
            drawCard(canvas, card, showBack = card.flipDeg > 90f)
            canvas.restore()
        }
    }

    private fun drawCard(canvas: Canvas, card: CardSpec, showBack: Boolean) {
        val fade = card.fade

        val fill = if (showBack) colorVerified else colorGlass
        val border = if (showBack) colorVerifiedBorder else colorGlassBorder

        applyColor(fillPaint, fill, fade)
        canvas.drawRoundRect(
            -CARD_HALF_W, -CARD_HALF_H, CARD_HALF_W, CARD_HALF_H,
            CARD_RADIUS, CARD_RADIUS, fillPaint,
        )

        applyColor(strokePaint, border, fade)
        val inset = CARD_BORDER / 2f
        canvas.drawRoundRect(
            -CARD_HALF_W + inset, -CARD_HALF_H + inset,
            CARD_HALF_W - inset, CARD_HALF_H - inset,
            CARD_RADIUS - inset, CARD_RADIUS - inset, strokePaint,
        )

        if (showBack) drawVerifiedFace(canvas, fade) else drawCallerRow(canvas, card, fade)
    }

    /** Avatar dot, two redacted text lines and the badge — the card's resting face. */
    private fun drawCallerRow(canvas: Canvas, card: CardSpec, fade: Float) {
        applyColor(fillPaint, colorAvatar, fade)
        canvas.drawCircle(AVATAR_CX, 0f, AVATAR_R, fillPaint)

        val lineBlockTop = -(LINE_H * 2f + LINE_GAP) / 2f
        applyColor(fillPaint, colorLineStrong, fade)
        canvas.drawRoundRect(
            COLUMN_LEFT, lineBlockTop, COLUMN_LEFT + LINE_TOP_W, lineBlockTop + LINE_H,
            LINE_RADIUS, LINE_RADIUS, fillPaint,
        )
        val secondTop = lineBlockTop + LINE_H + LINE_GAP
        applyColor(fillPaint, colorLineSoft, fade)
        canvas.drawRoundRect(
            COLUMN_LEFT, secondTop, COLUMN_LEFT + LINE_BOTTOM_W, secondTop + LINE_H,
            LINE_RADIUS, LINE_RADIUS, fillPaint,
        )

        applyColor(fillPaint, colorBadgeWell, fade)
        canvas.drawCircle(BADGE_CX, 0f, BADGE_R, fillPaint)
        drawIcon(canvas, card.icon, card.iconColor, fade, BADGE_CX, 0f, BADGE_ICON_SIZE)
    }

    /** The turned face: a check and the word "Verified", un-mirrored. */
    private fun drawVerifiedFace(canvas: Canvas, fade: Float) {
        applyColor(textPaint, colorInk, fade)
        val textWidth = textPaint.measureText(verifiedLabel)
        val rowWidth = VERIFIED_ICON_SIZE + VERIFIED_GAP + textWidth
        val rowLeft = -rowWidth / 2f

        canvas.save()
        // The card is mirrored whenever this face is up (cos < 0 past 90 degrees).
        // The design's back face carries its own rotateY(180deg) to cancel that,
        // so the same counter-mirror goes here: the two negatives leave a positive
        // x axis, which is why everything below is written in plain reading order —
        // icon on the left, label to its right — and the glyphs come out upright.
        canvas.scale(-1f, 1f)

        drawIcon(
            canvas, iconCheck, colorAccent, fade,
            cx = rowLeft + VERIFIED_ICON_SIZE / 2f, cy = 0f, size = VERIFIED_ICON_SIZE,
        )

        val metrics = textPaint.fontMetrics
        val baseline = -(metrics.ascent + metrics.descent) / 2f
        canvas.drawText(
            verifiedLabel,
            rowLeft + VERIFIED_ICON_SIZE + VERIFIED_GAP,
            baseline,
            textPaint,
        )
        canvas.restore()
    }

    /**
     * Stamps a 24×24 viewBox [path] centred on ([cx], [cy]) at [size] units across.
     * Scaling the canvas rather than the path means the 2.2 stroke thins with the
     * glyph exactly as the SVG's does.
     */
    private fun drawIcon(
        canvas: Canvas,
        path: Path,
        tint: Int,
        fade: Float,
        cx: Float,
        cy: Float,
        size: Float,
    ) {
        applyColor(iconPaint, tint, fade)
        val iconScale = size / ICON_VIEWBOX
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(iconScale, iconScale)
        canvas.translate(-ICON_VIEWBOX / 2f, -ICON_VIEWBOX / 2f)
        canvas.drawPath(path, iconPaint)
        canvas.restore()
    }

    /** Sets [paint] to [argb], with the colour's own alpha scaled by [fade]. */
    private fun applyColor(paint: Paint, argb: Int, fade: Float) {
        paint.color = argb
        paint.alpha = (Color.alpha(argb) * fade).toInt().coerceIn(0, 255)
    }

    private fun lerp(from: Float, to: Float, t: Float) = from + (to - from) * t
}
