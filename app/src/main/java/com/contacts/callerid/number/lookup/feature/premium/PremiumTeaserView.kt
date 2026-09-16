package com.contacts.callerid.number.lookup.feature.premium

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
import androidx.core.view.doOnLayout
import com.contacts.callerid.number.lookup.R

/**
 * The Premium offer as it appears on Settings.
 *
 * It is the paywall's hero compressed to the height of a settings row — the same
 * gradient, the same gold crown, the same travelling highlight, the same trial
 * pill — so that tapping it opens something the user already recognises instead
 * of a screen that looks unrelated to the row that led there. Every drawable it
 * uses belongs to the paywall already; nothing here is a second style to keep in
 * sync.
 *
 * Two of the paywall's loops run here, at the same speeds:
 *
 *  - `crownFloat` — 3.4s, 5dp of rise, eased per half-cycle
 *  - `heroSweep`  — a band 40% of the card, -120% to 240% over 3.2s
 *
 * The CTA's glow is deliberately left out: on a page of settings rows, a third
 * moving thing stops reading as emphasis and starts reading as noise.
 *
 * ## Why this is a view and not layout in the Settings file
 *
 * The loops have to stop when the card is not on screen, and this card is
 * [android.view.View.GONE] for anyone who already owns Premium. Owning its own
 * start/stop on attach and on visibility means Settings never has to think about
 * it — the same arrangement [com.contacts.callerid.number.lookup.feature.widgets.AiTileView]
 * uses for the Ask AI tile.
 */
class PremiumTeaserView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val crown: View
    private val sweep: View
    private val loops = mutableListOf<ValueAnimator>()

    init {
        inflate(context, R.layout.view_premium_teaser, this)
        crown = findViewById(R.id.teaserCrown)
        sweep = findViewById(R.id.teaserSweep)

        background = ContextCompat.getDrawable(context, R.drawable.bg_premium_hero)
        // The sweep runs to the card's edges and has to stop at the rounded
        // corner rather than square it off.
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
        startCrownFloat()
        startSweep()
    }

    private fun stopLoops() {
        loops.forEach { it.cancel() }
        loops.clear()
        crown.translationY = 0f
        sweep.translationX = sweep.width * SWEEP_FROM
    }

    /** `crownFloat`: half the cycle on REVERSE, so each half eases as CSS does. */
    private fun startCrownFloat() {
        loops += ObjectAnimator.ofFloat(crown, View.TRANSLATION_Y, 0f, -dp(CROWN_RISE_DP)).apply {
            duration = CROWN_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.premium_ease_in_out)
            start()
        }
    }

    /**
     * `heroSweep`. The band is 40% of the card and travels -120% to 240% of *its
     * own* width, which is how CSS reads a percentage translate.
     *
     * Sized on layout because 40% of the card is not known until the card is
     * measured, and the settings page is a scroll view whose width the card only
     * learns at the end of the first pass.
     */
    private fun startSweep() {
        doOnLayout {
            val band = (width * SWEEP_WIDTH_FRACTION).toInt()
            if (band <= 0) return@doOnLayout

            if (sweep.layoutParams.width != band) {
                sweep.layoutParams = sweep.layoutParams.apply { this.width = band }
            }
            sweep.post {
                if (!isAttachedToWindow || visibility != VISIBLE) return@post
                loops += ObjectAnimator.ofFloat(
                    sweep, View.TRANSLATION_X, band * SWEEP_FROM, band * SWEEP_TO
                ).apply {
                    duration = SWEEP_CYCLE_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator =
                        AnimationUtils.loadInterpolator(context, R.interpolator.premium_ease_in_out)
                    start()
                }
            }
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private companion object {
        const val ELEVATION_DP = 8f

        const val CROWN_CYCLE_MS = 3_400L
        const val CROWN_RISE_DP = 5f

        const val SWEEP_CYCLE_MS = 3_200L
        const val SWEEP_WIDTH_FRACTION = 0.40f
        const val SWEEP_FROM = -1.20f
        const val SWEEP_TO = 2.40f
    }
}
