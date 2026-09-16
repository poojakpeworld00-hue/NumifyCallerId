package com.contacts.callerid.number.lookup.feature.premium

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.common.Typography
import com.contacts.callerid.number.lookup.common.openPolicyLink
import com.contacts.callerid.number.lookup.common.openTermLink
import com.contacts.callerid.number.lookup.databinding.ActivityPremiumBinding
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.monetize.billing.BillingRepository
import com.contacts.callerid.number.lookup.monetize.billing.PremiumOffer
import com.contacts.callerid.number.lookup.monetize.billing.PremiumStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Contacts Premium paywall, built to the "Numify Premium" design handoff.
 *
 * It owns no billing logic of its own — [BillingRepository] holds the Play
 * connection for the whole process, and this screen only renders what that
 * reports and forwards taps to it. That split is what lets the entitlement
 * arrive while this screen is closed (a purchase completed from the Play app, a
 * restore on launch) and still be correct.
 *
 * Three states, driven entirely by flows so none of them can go stale:
 *  - **loading** — Play has not answered yet
 *  - **offering** — plans with live, localised prices
 *  - **owned** — already Premium, so the screen confirms rather than sells
 *
 * There is no fourth "buying" state: once Play's sheet is up it owns the screen,
 * and the result comes back through the entitlement flow like any other.
 *
 * ## The animations
 *
 * Five, all transcribed from the design's stylesheet rather than invented:
 *
 *  - `.a1`…`.a4` `fadeUp`  → [playEntrance], 420ms with a 0/90/180/270ms stagger
 *  - `crownFloat`          → [startCrownFloat], 3.4s, 5dp of rise
 *  - `heroSweep`           → [startHeroSweep], 3.2s, -120% to 240%
 *  - `ctaGlow`             → [startCtaGlow], a 2.4s shadow pulse
 *  - `.plan:active`        → `@animator/premium_plan_press`, in the row layout
 *
 * Every one of them animates only `alpha`, `translation*` or `elevation` —
 * properties the framework can hand to the render thread without a relayout — so
 * the three looping ones cost nothing per frame and hold 60fps while the page
 * scrolls. They start in [onStart] and are cancelled in [onStop]; an infinite
 * animator left running behind a backgrounded screen is a real battery cost.
 */
class PremiumActivity : BaseActivity<ActivityPremiumBinding>() {

    override val layoutId = R.layout.activity_premium

    /**
     * Off, uniquely in this app.
     *
     * Every size on this screen is transcribed from the handoff, and the app-wide
     * +8% ([Typography]) pushes the 14sp benefit titles and the 22sp hero into
     * the space the design leaves around them — the rows stop reading as the
     * design's. The user's own system font size still applies.
     */
    override val appliesAppTextScale = false

    private val billing by lazy { BillingRepository.getInstance(this) }

    /** The plan the user has selected; null until Play returns something. */
    private var selected: PremiumOffer? = null

    /** The three looping animations, held so [onStop] can stop them. */
    private val loops = mutableListOf<ValueAnimator>()

    private var entranceHasPlayed = false

    override fun initView() {
        // The app draws edge to edge (BaseActivity.applyImmersiveNavigation), so
        // without this the close button sits under the status bar and is clipped
        // — which is exactly what the first build of this screen did.
        ViewCompat.setOnApplyWindowInsetsListener(binding.premiumRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.groupA2Header.textSectionLabel.setText(R.string.premium_section_benefits)
        binding.groupA3Header.textSectionLabel.setText(R.string.premium_section_plans)
        binding.groupA4Terms.setText(R.string.premium_fine_print_plain)

        bindBenefits()

        binding.buttonClose.setOnClickListener { finish() }

        binding.buttonSubscribe.setOnClickListener {
            selected?.let { offer -> billing.launchPurchase(this, offer) }
        }

        // Restoring is a re-read of Play, not a separate purchase path. Play
        // requires it to be reachable without paying, and it is the answer to
        // "I already bought this on my old phone".
        binding.buttonRestore.setOnClickListener {
            billing.refreshPurchases()
            toast(R.string.premium_restoring)
        }

        binding.buttonTerms.setOnClickListener { openTermLink() }
        binding.buttonPrivacy.setOnClickListener { openPolicyLink() }

        // Cancelling or changing a subscription must go to Play, not to us — we
        // cannot cancel on the user's behalf, and hiding that is how a
        // subscription starts to feel like a trap.
        binding.buttonManage.setOnClickListener { openPlaySubscriptions() }

        // Connect if the Application's attempt has not landed yet; both calls
        // are safe because BillingRepository.start() no-ops when already ready.
        billing.start()

        prepareEntrance()
    }

    override fun initObservers() {
        // Entitlement. Collected rather than read once, so a purchase completing
        // in Play's sheet flips this screen the moment it does.
        lifecycleScope.launch {
            PremiumStore.isPremiumFlow.collectLatest { premium ->
                binding.stateOwned.isVisible = premium
                binding.premiumFooter.isVisible = !premium
                binding.premiumScroll.isVisible = !premium
            }
        }

        // Live prices.
        lifecycleScope.launch {
            billing.products.collectLatest { renderPlans(it) }
        }
    }

    // ── Entrance: the design's .a1 … .a4 ─────────────────────────────────────

    /**
     * The entrance groups, in the order the design assigns their classes.
     *
     * `a1` covers two siblings (the header row and the hero) because the design
     * puts the class on both, with the same 0ms delay.
     */
    private fun entranceGroups(): List<Pair<View, Long>> = listOf(
        binding.groupA1Header to 0L,
        binding.groupA1Hero to 0L,
        binding.groupA2Header.root to 90L,
        binding.listBenefits to 90L,
        binding.groupA3Header.root to 180L,
        binding.listPlans to 180L,
        binding.textPlansStatus to 180L,
        binding.groupA4Terms to 270L,
    )

    /**
     * Puts the entrance groups into their pre-animation state before the first
     * frame is drawn.
     *
     * This is the design's `animation-fill-mode: backwards`. Without it the
     * groups paint once at their final position and then jump back to the start,
     * which reads as a flicker rather than an entrance.
     */
    private fun prepareEntrance() {
        val offset = dp(ENTRANCE_OFFSET_DP)
        entranceGroups().forEach { (view, _) ->
            view.alpha = 0.001f
            view.translationY = offset
        }
    }

    /** `fadeUp 0.42s cubic-bezier(.2,.7,.2,1)`, staggered 0/90/180/270ms. */
    private fun playEntrance() {
        if (entranceHasPlayed) return
        entranceHasPlayed = true

        val curve = interpolator(R.interpolator.premium_fade_up)
        entranceGroups().forEach { (view, delay) ->
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, View.ALPHA, 0.001f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dp(ENTRANCE_OFFSET_DP), 0f),
                )
                duration = ENTRANCE_DURATION_MS
                startDelay = delay
                interpolator = curve
                start()
            }
        }
    }

    // ── The three loops ──────────────────────────────────────────────────────

    /**
     * `crownFloat`: `0%,100% { translateY(0) } 50% { translateY(-5px) }` over
     * 3.4s, `ease-in-out`, forever.
     *
     * Half the cycle on `REVERSE` rather than a three-keyframe animator, because
     * CSS applies the timing function between each *pair* of keyframes — easing
     * each half separately is what the design actually does.
     */
    private fun startCrownFloat() {
        loops += ObjectAnimator.ofFloat(
            binding.heroCrown, View.TRANSLATION_Y, 0f, -dp(CROWN_RISE_DP)
        ).apply {
            duration = CROWN_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = interpolator(R.interpolator.premium_ease_in_out)
            start()
        }
    }

    /**
     * `heroSweep`: a band 40% of the hero's width travelling `translateX(-120%)`
     * → `translateX(240%)` over 3.2s, `ease-in-out`, restarting.
     *
     * Those percentages are of the band's own width — that is how CSS reads a
     * percentage translate — so they are multiplied by the band below, not by
     * the card.
     *
     * Sized on layout because 40% of the hero is not known until the hero has
     * been measured, and it has to be re-derived after a rotation.
     */
    private fun startHeroSweep() {
        val hero = binding.groupA1Hero
        val sweep = binding.heroSweep
        hero.doOnLayout {
            val band = (hero.width * HERO_SWEEP_WIDTH_FRACTION).toInt()
            if (band <= 0) return@doOnLayout

            if (sweep.layoutParams.width != band) {
                sweep.layoutParams = sweep.layoutParams.apply { width = band }
            }
            sweep.post {
                loops += ObjectAnimator.ofFloat(
                    sweep,
                    View.TRANSLATION_X,
                    band * HERO_SWEEP_FROM,
                    band * HERO_SWEEP_TO,
                ).apply {
                    duration = HERO_SWEEP_CYCLE_MS
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.RESTART
                    interpolator = interpolator(R.interpolator.premium_ease_in_out)
                    start()
                }
            }
        }
    }

    /**
     * `ctaGlow`: the button's shadow breathing between
     * `0 12px 26px rgba(46,95,232,.34)` and `0 16px 34px rgba(46,95,232,.55)`
     * over 2.4s.
     *
     * Android has no box-shadow, only elevation and — from API 28 — a shadow
     * colour, so both move together: elevation carries the offset and blur
     * growing, the colour carries the darkening. Below API 28 the elevation
     * alone still reads as the same pulse, just without the brand tint.
     */
    private fun startCtaGlow() {
        val view = binding.buttonSubscribe
        val from = dp(CTA_ELEVATION_LOW_DP)
        val to = dp(CTA_ELEVATION_HIGH_DP)
        val evaluator = ArgbEvaluator()
        val glowLow = color(R.color.premium_cta_glow_low)
        val glowHigh = color(R.color.premium_cta_glow)

        loops += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CTA_GLOW_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = interpolator(R.interpolator.premium_ease_in_out)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                view.elevation = from + (to - from) * t
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val tint = evaluator.evaluate(t, glowLow, glowHigh) as Int
                    view.outlineSpotShadowColor = tint
                    view.outlineAmbientShadowColor = tint
                }
            }
            start()
        }
    }

    // ── Benefits ─────────────────────────────────────────────────────────────

    /**
     * One row per benefit, in the design's order.
     *
     * Each glyph carries its own stroke colour (the vectors reference their own
     * `premium_benefit_*_fg`), so only the tile behind them is tinted here.
     */
    private fun bindBenefits() {
        val benefits = listOf(
            Benefit(
                R.string.premium_benefit_ads_title,
                R.string.premium_benefit_ads_sub,
                R.drawable.ic_premium_benefit_ads,
                R.color.premium_benefit_ads_bg,
            ),
            Benefit(
                R.string.premium_benefit_lookup_title,
                R.string.premium_benefit_lookup_sub,
                R.drawable.ic_premium_benefit_lookup,
                R.color.premium_benefit_lookup_bg,
            ),
            Benefit(
                R.string.premium_benefit_ai_title,
                R.string.premium_benefit_ai_sub,
                R.drawable.ic_premium_benefit_ai,
                R.color.premium_benefit_ai_bg,
            ),
            Benefit(
                R.string.premium_benefit_block_title,
                R.string.premium_benefit_block_sub,
                R.drawable.ic_premium_benefit_block,
                R.color.premium_benefit_block_bg,
            ),
        )

        binding.listBenefits.removeAllViews()
        benefits.forEachIndexed { index, benefit ->
            val row = layoutInflater.inflate(
                R.layout.item_premium_benefit, binding.listBenefits, false
            )
            row.findViewById<TextView>(R.id.textBenefitTitle).setText(benefit.title)
            row.findViewById<TextView>(R.id.textBenefitSub).setText(benefit.subtitle)
            row.findViewById<ImageView>(R.id.iconBenefit).setImageResource(benefit.icon)

            // A tinted copy, not the shared drawable: tinting that in place would
            // repaint every row the same colour.
            row.findViewById<View>(R.id.tileBenefit).background = tintedTile(benefit.tint)

            // The design hides the last hairline with the card's overflow:hidden.
            row.findViewById<View>(R.id.benefitDivider).isVisible = index < benefits.lastIndex

            binding.listBenefits.addView(row)
        }
    }

    private fun tintedTile(@ColorRes tint: Int): Drawable? {
        val shape = ResourcesCompat.getDrawable(resources, R.drawable.bg_premium_tile, theme)
            ?: return null
        val copy = DrawableCompat.wrap(shape.mutate())
        DrawableCompat.setTint(copy, color(tint))
        return copy
    }

    private data class Benefit(
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @DrawableRes val icon: Int,
        @ColorRes val tint: Int,
    )

    // ── Plans ────────────────────────────────────────────────────────────────

    /**
     * Draws a row per plan Play returned.
     *
     * The lifetime unlock is preselected where one exists — it is the row the
     * design marks BEST VALUE and shows chosen — falling back to the longest
     * subscription, then to whatever came first. An unselected list would leave
     * the button dead on arrival.
     */
    private fun renderPlans(list: List<PremiumOffer>) {
        binding.listPlans.removeAllViews()

        if (list.isEmpty()) {
            binding.textPlansStatus.isVisible = true
            binding.textPlansStatus.setText(
                if (billing.connected.value) R.string.premium_unavailable
                else R.string.premium_loading
            )
            setCtaEnabled(false)
            binding.groupA4Terms.setText(R.string.premium_fine_print_plain)
            selected = null
            return
        }

        binding.textPlansStatus.isVisible = false
        selected = list.firstOrNull { it.isLifetime }
            ?: list.firstOrNull { it.billingPeriod == "P1Y" }
            ?: list.first()

        list.forEach { offer ->
            val row = layoutInflater.inflate(R.layout.item_premium_plan, binding.listPlans, false)
            row.findViewById<TextView>(R.id.textPlanTitle).text = planTitle(offer)
            row.findViewById<TextView>(R.id.textPlanSub).text = planSubtitle(offer)
            row.findViewById<TextView>(R.id.textPlanPrice).text = offer.price
            row.findViewById<TextView>(R.id.textPlanPeriod).text = planPeriod(offer)
            row.findViewById<View>(R.id.badgeBestValue).isVisible = offer.isLifetime

            val tap = row.findViewById<View>(R.id.rowPlan)
            tap.tag = offer
            tap.setOnClickListener {
                selected = offer
                highlightSelection()
            }

            // The design stacks the rows with a 10px gap.
            if (binding.listPlans.childCount > 0) {
                (row.layoutParams as ViewGroup.MarginLayoutParams).topMargin =
                    dp(PLAN_GAP_DP).toInt()
            }
            binding.listPlans.addView(row)
        }
        highlightSelection()
        setCtaEnabled(true)
    }

    /**
     * Enables or disables the call to action, and makes that visible.
     *
     * The design has no disabled state because it assumes prices are there. They
     * are not, until Play answers — and a full-strength gradient button that
     * swallows taps is worse than one that plainly says it is not ready, so the
     * disabled state is the design at half strength rather than a second style.
     */
    private fun setCtaEnabled(enabled: Boolean) {
        binding.buttonSubscribe.isEnabled = enabled
        binding.buttonSubscribe.alpha = if (enabled) 1f else DISABLED_CTA_ALPHA
    }

    /**
     * Applies the selected look to one row and clears it from the others.
     *
     * Everything the design changes on selection is set here rather than through
     * `duplicateParentState`: the tick's *visibility* has to change too, which no
     * state list can express, so splitting the job between XML and code would
     * only make it harder to see what a selected row looks like.
     */
    private fun highlightSelection() {
        val selectedShadow = dp(PLAN_SELECTED_ELEVATION_DP)
        for (i in 0 until binding.listPlans.childCount) {
            val row = binding.listPlans.getChildAt(i).findViewById<View>(R.id.rowPlan)
            val offer = row.tag as? PremiumOffer
            val isSelected = offer != null && offer === selected

            row.isActivated = isSelected
            row.elevation = if (isSelected) selectedShadow else 0f
            row.findViewById<View>(R.id.radioPlan).isActivated = isSelected
            row.findViewById<View>(R.id.iconPlanCheck).isVisible = isSelected

            row.findViewById<TextView>(R.id.textPlanSub).setTextColor(
                color(if (isSelected) R.color.premium_sub_selected else R.color.premium_ink_muted)
            )
            row.findViewById<TextView>(R.id.textPlanPrice).setTextColor(
                color(if (isSelected) R.color.premium_accent else R.color.premium_ink)
            )
            row.findViewById<TextView>(R.id.textPlanPeriod).setTextColor(
                color(
                    if (isSelected) R.color.premium_price_selected_sub
                    else R.color.premium_price_muted
                )
            )
            if (isSelected && offer != null) applyFinePrint(offer)
        }
    }

    /**
     * The design's fine print names a price: "Free for 3 days, then ₹999/month
     * unless cancelled." That price has to come from Play rather than from the
     * design — it differs in every country — so the selected row fills it in,
     * and a plan with no recurring period falls back to the priceless wording.
     */
    private fun applyFinePrint(offer: PremiumOffer) {
        if (offer.isLifetime) {
            binding.groupA4Terms.setText(R.string.premium_fine_print_plain)
        } else {
            binding.groupA4Terms.text =
                getString(R.string.premium_fine_print, offer.price + planPeriod(offer))
        }
    }

    /**
     * A human name for the plan.
     *
     * Play returns the base plan id ("monthly", "yearly") and the ISO-8601 period
     * ("P1M", "P1Y"). The period is the reliable one — a base plan can be called
     * anything — so it is what the label is derived from, with the id as the
     * fallback for a period this app has no wording for.
     */
    private fun planTitle(offer: PremiumOffer): String = when {
        offer.isLifetime -> getString(R.string.premium_plan_lifetime)
        offer.billingPeriod == "P1W" -> getString(R.string.premium_plan_weekly)
        offer.billingPeriod == "P1M" -> getString(R.string.premium_plan_monthly)
        offer.billingPeriod == "P1Y" -> getString(R.string.premium_plan_yearly)
        else -> offer.title
    }

    private fun planSubtitle(offer: PremiumOffer): String = when {
        offer.isLifetime -> getString(R.string.premium_plan_lifetime_sub)
        offer.billingPeriod == "P1W" -> getString(R.string.premium_plan_weekly_sub)
        offer.billingPeriod == "P1M" -> getString(R.string.premium_plan_monthly_sub)
        offer.billingPeriod == "P1Y" -> getString(R.string.premium_plan_yearly_sub)
        else -> getString(R.string.premium_plan_generic_sub)
    }

    /** The small grey suffix under the price: `/month`, `one time`, and so on. */
    private fun planPeriod(offer: PremiumOffer): String = when {
        offer.isLifetime -> getString(R.string.premium_period_lifetime)
        offer.billingPeriod == "P1W" -> getString(R.string.premium_period_week)
        offer.billingPeriod == "P1M" -> getString(R.string.premium_period_month)
        offer.billingPeriod == "P1Y" -> getString(R.string.premium_period_year)
        else -> ""
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Play's own subscription centre — the only place a subscription can be cancelled. */
    private fun openPlaySubscriptions() {
        val uri = "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingRepository.SUBSCRIPTION_ID}&package=$packageName"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
            .onFailure { toast(R.string.premium_manage_unavailable) }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun color(@ColorRes res: Int): Int = ContextCompat.getColor(this, res)

    private fun interpolator(res: Int): Interpolator = AnimationUtils.loadInterpolator(this, res)

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    override fun onStart() {
        super.onStart()
        // Started here rather than in initView: onStart also runs when the screen
        // comes back from Play's sheet, which is where the loops were stopped.
        startCrownFloat()
        startHeroSweep()
        startCtaGlow()
        binding.premiumRoot.doOnLayout { playEntrance() }
    }

    override fun onStop() {
        super.onStop()
        loops.forEach { it.cancel() }
        loops.clear()
        // The sweep is mid-travel when cancelled; without this it stays parked
        // wherever it stopped until the next start moves it.
        binding.heroSweep.translationX = binding.heroSweep.width * HERO_SWEEP_FROM
    }

    override fun onResume() {
        super.onResume()
        // Catches a purchase or cancellation made in the Play app while this
        // screen sat in the background.
        billing.refreshPurchases()
    }

    companion object {
        private const val ENTRANCE_DURATION_MS = 420L
        private const val ENTRANCE_OFFSET_DP = 12f

        private const val CROWN_CYCLE_MS = 3400L
        private const val CROWN_RISE_DP = 5f

        private const val HERO_SWEEP_CYCLE_MS = 3200L
        private const val HERO_SWEEP_WIDTH_FRACTION = 0.40f
        private const val HERO_SWEEP_FROM = -1.20f
        private const val HERO_SWEEP_TO = 2.40f

        private const val CTA_GLOW_CYCLE_MS = 2400L
        private const val CTA_ELEVATION_LOW_DP = 12f
        private const val CTA_ELEVATION_HIGH_DP = 16f

        private const val DISABLED_CTA_ALPHA = 0.45f

        private const val PLAN_GAP_DP = 10f
        private const val PLAN_SELECTED_ELEVATION_DP = 10f

        fun newIntent(context: Context): Intent = Intent(context, PremiumActivity::class.java)
    }
}
