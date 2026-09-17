package com.callerid.numberlookup.home.feature.premium

import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieAnimationView
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
import com.callerid.numberlookup.home.R

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

    /**
     * Which artwork the chip wears.
     *
     * Two Lottie pieces are on trial against the drawn crown. They are pills, not
     * squares, so picking one also changes the chip's proportions - which is why
     * this is one constant and not three copies of the header.
     */
    enum class Art { CROWN_GLYPH, LOTTIE_GO_PRO, LOTTIE_CROWN }

    private val lottie: LottieAnimationView? =
        if (ART == Art.CROWN_GLYPH) null else LottieAnimationView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            setAnimation(
                if (ART == Art.LOTTIE_GO_PRO) R.raw.premium_go_pro else R.raw.premium_crown
            )
            repeatCount = LottieDrawable.INFINITE
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    private val crown = ImageView(context).apply {
        layoutParams = LayoutParams(dpInt(CROWN_DP), dpInt(CROWN_DP), Gravity.CENTER)
        setImageResource(R.drawable.ic_premium_crown)
        contentDescription = null
    }

    private val loops = mutableListOf<ValueAnimator>()

    init {
        // The Lottie pieces draw their own pill and their own motion, so they get
        // neither the chip background nor the crown behind them - both would show
        // through at the corners and read as two badges stacked.
        if (lottie != null) addView(lottie) else addView(crown)
        if (lottie == null) {
            background = ContextCompat.getDrawable(context, R.drawable.bg_premium_chip)
        }
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

    /**
     * The pills are 50x27 in their own artboard, so the chip takes those
     * proportions rather than the 40dp square the glyph sits in. Stated here so
     * that switching art does not mean editing the three headers that host it.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (lottie == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(dpInt(LOTTIE_W_DP), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dpInt(LOTTIE_H_DP), MeasureSpec.EXACTLY),
        )
    }

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
        lottie?.let { it.playAnimation(); return }
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
        lottie?.let { it.pauseAnimation(); return }
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
        /** Flip this to compare the three. */
        val ART = Art.LOTTIE_GO_PRO
        const val LOTTIE_W_DP = 62f
        const val LOTTIE_H_DP = 34f
        const val CROWN_DP = 18f

        const val PULSE_CYCLE_MS = 2_600L
        const val PULSE_SCALE = 1.14f
        const val PULSE_ROTATION = 10f
    }
}
