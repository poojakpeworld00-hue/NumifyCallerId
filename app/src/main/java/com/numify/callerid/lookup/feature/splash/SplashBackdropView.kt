package com.numify.callerid.lookup.feature.splash

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The splash's backdrop: the design's three-stop `linear-gradient(155deg, …)` plus
 * the two soft white discs bleeding off the top-left and bottom-right corners.
 *
 * A `<shape>` drawable cannot express this. Android's XML gradients only accept
 * angles in 45-degree steps, and 155deg is not one of them — rounding to 135 or 180
 * visibly tilts the whole screen's light. So the gradient line is built here the way
 * CSS builds it: through the centre of the box, at the authored angle, with a length
 * of |W·sinθ| + |H·cosθ| so both end stops land exactly on the corners.
 *
 * The gradient paints edge to edge (it sits behind the transparent system bars),
 * while the discs are placed against the *content* box supplied by
 * [setContentInsets] — that box is the analogue of the design's 396×812 frame, so
 * the discs keep the offsets the design gave them instead of drifting up under the
 * status bar on tall devices.
 */
class SplashBackdropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** The design frame's content box, in design px — everything scales off this. */
        const val DESIGN_W = 396f

        /** `linear-gradient(155deg, …)`, in CSS's clockwise-from-north degrees. */
        const val GRADIENT_ANGLE_DEG = 155.0

        // Disc geometry, in design px, straight from the two absolutely
        // positioned divs: {left:-80, top:-60, 260²} and {right:-100, bottom:120, 220²}.
        const val DISC_TOP_LEFT_CX = 50f
        const val DISC_TOP_LEFT_CY = 70f
        const val DISC_TOP_LEFT_R = 130f
        const val DISC_BOTTOM_RIGHT_INSET_X = 10f
        const val DISC_BOTTOM_RIGHT_INSET_Y = 230f
        const val DISC_BOTTOM_RIGHT_R = 110f
    }

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val discTopLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.splash_cs_decor_lg)
    }

    private val discBottomRightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.splash_cs_decor_sm)
    }

    private val gradientStops = intArrayOf(
        ContextCompat.getColor(context, R.color.splash_cs_grad_top),
        ContextCompat.getColor(context, R.color.splash_cs_grad_mid),
        ContextCompat.getColor(context, R.color.splash_cs_grad_bottom),
    )

    /** Stop offsets from the design: 0%, 55%, 100%. The colours come from tokens. */
    private val gradientPositions = floatArrayOf(0f, 0.55f, 1f)

    private var contentLeft = 0
    private var contentTop = 0
    private var contentRight = 0
    private var contentBottom = 0

    /**
     * Tells the backdrop where the system bars are, so the discs can be placed
     * against the same content box the rest of the splash lays out in.
     */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (left == contentLeft && top == contentTop &&
            right == contentRight && bottom == contentBottom
        ) return
        contentLeft = left
        contentTop = top
        contentRight = right
        contentBottom = bottom
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // CSS gradient-line construction: the line runs through the box centre in
        // the authored direction, and is long enough that 0% and 100% sit on the
        // corners the angle points away from and towards.
        val radians = Math.toRadians(GRADIENT_ANGLE_DEG)
        val dirX = sin(radians).toFloat()
        val dirY = -cos(radians).toFloat() // screen space: y grows downward
        val length = abs(w * dirX) + abs(h * dirY)
        val halfX = length / 2f * dirX
        val halfY = length / 2f * dirY
        val cx = w / 2f
        val cy = h / 2f

        gradientPaint.shader = LinearGradient(
            cx - halfX, cy - halfY,
            cx + halfX, cy + halfY,
            gradientStops, gradientPositions,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)

        val boxLeft = contentLeft.toFloat()
        val boxTop = contentTop.toFloat()
        val boxWidth = (w - contentLeft - contentRight).toFloat()
        val boxHeight = (h - contentTop - contentBottom).toFloat()
        if (boxWidth <= 0f || boxHeight <= 0f) return

        // One uniform scale off the content box's width keeps the discs the same
        // size relative to the screen as they are in the design frame.
        val scale = boxWidth / DESIGN_W

        canvas.drawCircle(
            boxLeft + DISC_TOP_LEFT_CX * scale,
            boxTop + DISC_TOP_LEFT_CY * scale,
            DISC_TOP_LEFT_R * scale,
            discTopLeftPaint,
        )
        canvas.drawCircle(
            boxLeft + boxWidth - DISC_BOTTOM_RIGHT_INSET_X * scale,
            boxTop + boxHeight - DISC_BOTTOM_RIGHT_INSET_Y * scale,
            DISC_BOTTOM_RIGHT_R * scale,
            discBottomRightPaint,
        )
    }
}
