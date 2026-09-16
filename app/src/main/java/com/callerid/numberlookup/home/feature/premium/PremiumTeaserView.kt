package com.callerid.numberlookup.home.feature.premium

import android.animation.AnimatorInflater
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * The Premium offer as it appears on Settings.
 *
 * It is the paywall's hero, not a version of it: the same gradient card, the
 * same "Free vs Premium" line with its two-tone spark and gold crown, the same
 * subtitle, at the design's own sizes. Tapping a row should open something the
 * user already recognises, and the surest way to get that is for the two to be
 * the same object rather than two takes on one idea.
 *
 * Both of the hero's living animations run here, at the same speeds:
 *
 *  - `shine`      — [ShineTextView] owns it: a 260%-wide gradient sliding
 *                   across the word "Premium" over 4.5s, linear, forever
 *  - `sparkPulse` — scale 1.14 and rotate 10 degrees over 2.6s, eased per half
 *                   cycle the way CSS eases between keyframes
 *
 * The hero's *entrance* — the word-by-word stagger, `popIn`, `slideFromLeft`,
 * `slideFromRight` — is deliberately left on the paywall. That is a landing
 * animation for a screen you arrive at; on Settings it would mean one row
 * assembling itself over the first half second while every other row is already
 * there, which reads as the page being slow rather than as polish.
 *
 * ## Why this is a view and not layout in the Settings file
 *
 * The loops have to stop when the card is not on screen, and this card is
 * [android.view.View.GONE] for anyone who already owns Premium. Owning its own
 * start/stop on attach and on visibility means Settings never has to think about
 * it — the same arrangement [com.callerid.numberlookup.home.feature.widgets.AiTileView]
 * uses for the Ask AI tile.
 */
class PremiumTeaserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val spark: View
    private val premium: ShineTextView
    private val loops = mutableListOf<ValueAnimator>()

    init {
        inflate(context, R.layout.view_premium_teaser, this)
        spark = findViewById(R.id.teaserSpark)
        premium = findViewById(R.id.teaserPremium)

        background = ContextCompat.getDrawable(context, R.drawable.bg_premium_hero)
        clipToOutline = true

        elevation = dp(ELEVATION_DP)
        outlineAmbientShadowColor = ContextCompat.getColor(context, R.color.premium_hero_shadow)
        outlineSpotShadowColor = ContextCompat.getColor(context, R.color.premium_hero_shadow)

        isClickable = true
        isFocusable = true
        stateListAnimator =
            AnimatorInflater.loadStateListAnimator(context, R.animator.premium_plan_press)
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
        // GONE for anyone who already owns Premium — nothing should be animating
        // behind a card that is not there.
        if (visibility == VISIBLE && isAttachedToWindow) startLoops() else stopLoops()
    }

    private fun startLoops() {
        if (loops.isNotEmpty() || visibility != VISIBLE) return

        // ShineTextView starts itself on attach, but not after a stop that came
        // from the card being hidden rather than detached.
        premium.start()

        val curve = AnimationUtils.loadInterpolator(context, R.interpolator.premium_ease_in_out)
        listOf(
            ObjectAnimator.ofFloat(spark, View.SCALE_X, 1f, SPARK_PULSE_SCALE),
            ObjectAnimator.ofFloat(spark, View.SCALE_Y, 1f, SPARK_PULSE_SCALE),
            ObjectAnimator.ofFloat(spark, View.ROTATION, 0f, SPARK_PULSE_ROTATION),
        ).forEach { animator ->
            loops += animator.apply {
                duration = SPARK_PULSE_CYCLE_MS / 2
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = curve
                start()
            }
        }
    }

    private fun stopLoops() {
        loops.forEach { it.cancel() }
        loops.clear()
        premium.stop()
        // Cancelling mid-pulse leaves the spark wherever it stopped.
        spark.scaleX = 1f
        spark.scaleY = 1f
        spark.rotation = 0f
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private companion object {
        const val ELEVATION_DP = 9f

        const val SPARK_PULSE_CYCLE_MS = 2_600L
        const val SPARK_PULSE_SCALE = 1.14f
        const val SPARK_PULSE_ROTATION = 10f
    }
}
