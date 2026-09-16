package com.contacts.callerid.number.lookup.feature.premium

import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.contacts.callerid.number.lookup.R

/**
 * The Premium entry point as a header chip.
 *
 * The paywall's gold crown tile at the size the app's screen headers already
 * use for their action chips, so it sits in a header row next to the filter and
 * the settings button without inventing a third shape. The crown carries the
 * hero's `sparkPulse` — scale 1.14 and 10 degrees of rotation over 2.6s, eased
 * per half cycle — which is what makes it read as an offer rather than as
 * another tool.
 *
 * Self-managing, like [PremiumTeaserView] and the Ask AI tile: the pulse starts
 * on attach and stops on detach or when the chip is hidden, so a screen that is
 * scrolled away or a chip that is GONE for someone who already owns Premium
 * leaves nothing running.
 *
 * It renders nothing but itself — the caller decides where it goes, whether it
 * is visible, and what a tap does.
 */
class PremiumBadgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val crown = ImageView(context).apply {
        layoutParams = LayoutParams(dpInt(CROWN_DP), dpInt(CROWN_DP), Gravity.CENTER)
        setImageResource(R.drawable.ic_premium_crown)
        contentDescription = null
    }

    private val loops = mutableListOf<ValueAnimator>()

    init {
        addView(crown)
        background = ContextCompat.getDrawable(context, R.drawable.bg_premium_chip)
        isClickable = true
        isFocusable = true
        foreground = obtainSelectableItemBackground()
        contentDescription = context.getString(R.string.settings_premium)
        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(context, R.animator.premium_plan_press)
    }

    private fun obtainSelectableItemBackground() =
        context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            .use { it.getDrawable(0) }

    private inline fun <T> android.content.res.TypedArray.use(block: (android.content.res.TypedArray) -> T): T =
        try { block(this) } finally { recycle() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (loops.isNotEmpty() || visibility != VISIBLE) return
        val curve = AnimationUtils.loadInterpolator(context, R.interpolator.premium_ease_in_out)
        listOf(
            ObjectAnimator.ofFloat(crown, View.SCALE_X, 1f, PULSE_SCALE),
            ObjectAnimator.ofFloat(crown, View.SCALE_Y, 1f, PULSE_SCALE),
            ObjectAnimator.ofFloat(crown, View.ROTATION, 0f, PULSE_ROTATION),
        ).forEach { animator ->
            loops += animator.apply {
                duration = PULSE_CYCLE_MS / 2
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = curve
                start()
            }
        }
    }

    private fun stopPulse() {
        loops.forEach { it.cancel() }
        loops.clear()
        // Cancelling mid-pulse leaves the crown wherever it stopped.
        crown.scaleX = 1f
        crown.scaleY = 1f
        crown.rotation = 0f
    }

    private fun dpInt(value: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    ).toInt()

    private companion object {
        const val CROWN_DP = 18f

        const val PULSE_CYCLE_MS = 2_600L
        const val PULSE_SCALE = 1.14f
        const val PULSE_ROTATION = 10f
    }
}
