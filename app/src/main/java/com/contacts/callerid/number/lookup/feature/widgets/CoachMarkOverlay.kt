package com.contacts.callerid.number.lookup.feature.widgets

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * Full-screen modal coach-mark. It dims the screen, cuts a rounded "spotlight"
 * hole over [target] so that view stays visible, and places [bubbleRes] just
 * below it. A tap anywhere dismisses it.
 *
 * Both the hole and the bubble are repositioned on every layout pass rather than
 * measured once when shown. Whatever is underneath carries on moving after the
 * coach-mark appears - a permission strip resolves, an ad loads - and a one-shot
 * measurement ends up highlighting whichever view has since slid into that spot,
 * with the bubble hovering over something it was never pointing at.
 */
class CoachMarkOverlay private constructor(context: Context) : FrameLayout(context) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SCRIM_COLOR }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val holeRect = RectF()
    private val holeRadius = dp(16f)

    private var onDismiss: (() -> Unit)? = null
    private var target: View? = null
    private var bubble: View? = null
    private var layoutWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null

    init {
        setWillNotDraw(false)
        // A software layer is required for PorterDuff.CLEAR to punch a fully
        // transparent hole (instead of painting black).
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        setOnClickListener { dismiss() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRoundRect(holeRect, holeRadius, holeRadius, holePaint)
    }

    /** Re-reads the target's position and moves the hole and bubble onto it. */
    private fun sync() {
        val t = target ?: return
        if (!t.isShown || t.width == 0) return

        val loc = IntArray(2)
        t.getLocationInWindow(loc)
        val pad = dp(6f)
        holeRect.set(
            loc[0] - pad,
            loc[1] - pad,
            loc[0] + t.width + pad,
            loc[1] + t.height + pad,
        )

        bubble?.let { b ->
            val below = (holeRect.bottom + dp(10f)).toInt()
            // Flip above the target when there is no room under it, so the bubble
            // never covers the very thing the hole is exposing.
            val fits = below + b.height <= height - dp(16f)
            val top = if (fits || b.height == 0) below
            else (holeRect.top - dp(10f) - b.height).toInt().coerceAtLeast(dp(16f).toInt())
            (b.layoutParams as LayoutParams).topMargin = top
            b.requestLayout()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener { sync() }
        viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)
    }

    override fun onDetachedFromWindow() {
        layoutWatcher?.let { viewTreeObserver.removeOnGlobalLayoutListener(it) }
        layoutWatcher = null
        super.onDetachedFromWindow()
    }

    fun dismiss() {
        (parent as? ViewGroup)?.removeView(this)
        onDismiss?.invoke()
        onDismiss = null
        target = null
        bubble = null
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val SCRIM_COLOR = 0xB3000000.toInt() // ~70% black

        /**
         * Shows the coach-mark for [target]. Safe to call as soon as the target
         * exists — the overlay tracks it from there.
         */
        fun show(
            activity: Activity,
            target: View,
            bubbleRes: Int,
            bind: ((View) -> Unit)? = null,
            onDismiss: (() -> Unit)? = null
        ): CoachMarkOverlay {
            val root = activity.window.decorView as ViewGroup
            val overlay = CoachMarkOverlay(activity).apply {
                this.onDismiss = onDismiss
                this.target = target
            }

            val bubble = LayoutInflater.from(activity).inflate(bubbleRes, overlay, false)
            // [bind] runs before the bubble is measured, so a caller filling in
            // per-step copy cannot leave the overlay positioning itself against
            // the wrong height — which is what a post-hoc setText would do.
            bind?.invoke(bubble)
            bubble.layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
            bubble.setOnClickListener { overlay.dismiss() }
            overlay.bubble = bubble
            overlay.addView(bubble)

            root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            return overlay
        }
    }
}
