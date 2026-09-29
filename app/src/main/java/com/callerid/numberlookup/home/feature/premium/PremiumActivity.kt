package com.callerid.numberlookup.home.feature.premium

import com.callerid.numberlookup.home.feature.MainShellActivity
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.Typography
import com.callerid.numberlookup.home.common.openPolicyLink
import com.callerid.numberlookup.home.common.openTermLink
import com.callerid.numberlookup.home.databinding.ActivityPremiumBinding
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.billing.BillingRepository
import com.callerid.numberlookup.home.monetize.billing.PlanPricing
import com.callerid.numberlookup.home.monetize.billing.PremiumOffer
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Contacts Premium paywall, design 1a: "Paywall with weekly-normalised
 * pricing".
 *
 * Every plan leads with its price per week so the three compare directly, and
 * the real charge sits under each plan name and in the line over the button,
 * following the selection. The arithmetic is [PlanPricing]'s and runs on Play's
 * own micros and currency, so the page is right in every country Play sells in:
 * a year is 52 weeks, a month 52/12, per-week prices round down to the cent and
 * savings down to a whole percent, against the weekly plan.
 *
 * It owns no billing logic of its own - [BillingRepository] holds the Play
 * connection for the whole process, and this screen only renders what that
 * reports and forwards taps to it. Three states, driven by flows so none can go
 * stale: loading, offering, and owned (already Premium, so it confirms rather
 * than sells).
 *
 * ## Motion, from the design's keyframes
 *
 * | design                                  | here                              |
 * |-----------------------------------------|-----------------------------------|
 * | close: countdown ring, then `pwPop` .28s | [CloseCountdownView], [showClose] |
 * | crown tile `pwFloat` 5s, ±4px            | [startCrownFloat]                 |
 * | crown and CTA `pwShine`                  | [ShineBandView] (its own loop)    |
 * | CTA `pwGlow` 2.4s                        | [startCtaGlow]                    |
 * | plan rows `transition: all .18s ease`    | [animateSelection]                |
 *
 * The loops start in [onStart] and stop in [onStop]; an infinite animator left
 * running behind a backgrounded screen is a real battery cost.
 */
class PremiumActivity : BaseActivity<ActivityPremiumBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "PremiumActivity"

    override val layoutId = R.layout.activity_premium

    /**
     * Off, uniquely in this app: every size on this screen is transcribed from
     * the design, and the app-wide +8% ([Typography]) would push the rows past
     * the space it gives them. The user's own system font size still applies.
     */
    override val appliesAppTextScale = false

    private val billing by lazy { BillingRepository.getInstance(this) }

    /** The plan the user has selected; null until Play returns something. */
    private var selected: PremiumOffer? = null

    /** The rows on screen, with what each needs to redraw its selection. */
    private val rows = mutableListOf<PlanRow>()

    /** The weekly plan, which every saving is measured against. */
    private var weekly: PremiumOffer? = null

    /** The looping animations, held so [onStop] can stop them. */
    private val loops = mutableListOf<ValueAnimator>()

    /** `cubic-bezier(.2,.8,.2,1)` - pwPop. */
    private val popCurve = PathInterpolatorCompat.create(0.2f, 0.8f, 0.2f, 1f)

    /** CSS `ease` - the rows' .18s transition. */
    private val easeCurve = PathInterpolatorCompat.create(0.25f, 0.1f, 0.25f, 1f)

    /** CSS `ease-in-out` - pwFloat and pwGlow. */
    private val easeInOut = PathInterpolatorCompat.create(0.42f, 0f, 0.58f, 1f)

    private val argb = ArgbEvaluator()

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        // The hero is full bleed under a transparent status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        // The hero runs under the status bar, so only the sides and the bottom
        // are inset here; the hero takes the top as padding of its own.
        val heroPadTop = binding.paywallHero.paddingTop
        val footerPadBottom = binding.premiumFooter.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.premiumRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right)
            binding.paywallHero.updatePadding(top = heroPadTop + bars.top)
            binding.premiumFooter.updatePadding(bottom = footerPadBottom + bars.bottom)
            binding.stateOwned.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        bindPerks()

        // The close cross waits out a short countdown, drawn where it will appear.
        // "Continue without Premium" is there from the start, so the countdown
        // never holds anyone on the page.
        binding.closeCountdown.onFinished = { showClose() }
        binding.closeCountdown.contentDescription = resources.getQuantityString(
            R.plurals.paywall_close_in, CLOSE_DELAY_SECONDS, CLOSE_DELAY_SECONDS
        )
        binding.closeCountdown.start(CLOSE_DELAY_SECONDS * 1000L)
        binding.buttonClose.setOnClickListener { finish() }
        binding.buttonContinueFree.paintFlags =
            binding.buttonContinueFree.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.buttonContinueFree.setOnClickListener { finish() }

        // Back to a rebuilt app rather than to whatever was underneath: screens
        // already on the stack were built while ads were on and are holding ad
        // views they have no reason to drop.
        binding.buttonGoToApp.setOnClickListener {
            startActivity(
                Intent(this, MainShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
            finish()
        }

        binding.buttonSubscribe.setOnClickListener {
            selected?.let { offer -> billing.launchPurchase(this, offer) }
        }

        // Restoring is a re-read of Play, not a separate purchase path. Play
        // requires it to be reachable without paying.
        binding.buttonRestore.setOnClickListener {
            billing.refreshPurchases()
            toast(R.string.premium_restoring)
        }

        binding.buttonTerms.setOnClickListener { openTermLink() }
        binding.buttonPrivacy.setOnClickListener { openPolicyLink() }

        // Cancelling or changing a subscription must go to Play, not to us.
        binding.buttonManage.setOnClickListener { openPlaySubscriptions() }

        // Safe to call twice: start() no-ops when already connected.
        billing.start()
    }

    override fun initObservers() {
        // Debug builds only: the offer with the design's sample prices, whatever
        // this account owns and whatever Play returns to an unsigned build.
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_PREVIEW, false)) {
            WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars = false
            renderPlans(previewOffers())
            return
        }

        // Entitlement. Collected rather than read once, so a purchase completing
        // in Play's sheet flips this screen the moment it does.
        lifecycleScope.launch {
            PremiumStore.isPremiumFlow.collectLatest { premium ->
                binding.stateOwned.isVisible = premium
                binding.premiumFooter.isVisible = !premium
                binding.premiumScroll.isVisible = !premium
                // Light icons over the dark hero; dark ones over the owned page.
                WindowCompat.getInsetsController(window, binding.root)
                    .isAppearanceLightStatusBars = premium
            }
        }

        // Live prices.
        lifecycleScope.launch {
            billing.products.collectLatest { renderPlans(it) }
        }
    }

    // ── Hero ─────────────────────────────────────────────────────────────────

    /** The four benefit tiles: icon, the icon's tinted well, and the label. */
    private fun bindPerks() {
        bindPerk(binding.root.findViewById(R.id.perkLookups), R.drawable.ic_ds_nav_lookup,
            R.color.paywall_perk_lookup, R.color.paywall_perk_lookup_bg, R.string.paywall_perk_lookups)
        bindPerk(binding.root.findViewById(R.id.perkBlock), R.drawable.ic_ds_block_slash,
            R.color.paywall_perk_block, R.color.paywall_perk_block_bg, R.string.paywall_perk_block)
        bindPerk(binding.root.findViewById(R.id.perkAi), R.drawable.ic_ai_sparkle,
            R.color.paywall_perk_ai, R.color.paywall_perk_ai_bg, R.string.paywall_perk_ai)
        bindPerk(binding.root.findViewById(R.id.perkAds), R.drawable.ic_shield_check,
            R.color.paywall_perk_ads, R.color.paywall_perk_ads_bg, R.string.paywall_perk_ads)
    }

    private fun bindPerk(
        tile: View,
        @DrawableRes icon: Int,
        @ColorRes tint: Int,
        @ColorRes well: Int,
        @StringRes label: Int,
    ) {
        tile.findViewById<ImageView>(R.id.perkIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color(tint))
        }
        tile.findViewById<View>(R.id.perkIconWell).backgroundTintList =
            ColorStateList.valueOf(color(well))
        tile.findViewById<TextView>(R.id.perkLabel).setText(label)
    }

    /** `pwPop`: the cross replaces the ring, from scale .6 and transparent, in 280ms. */
    private fun showClose() {
        binding.closeCountdown.isVisible = false
        binding.buttonClose.apply {
            isVisible = true
            alpha = 0f
            scaleX = CLOSE_POP_FROM
            scaleY = CLOSE_POP_FROM
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(CLOSE_POP_MS).setInterpolator(popCurve).start()
        }
    }

    // ── Plans ────────────────────────────────────────────────────────────────

    /**
     * Draws a row per subscription plan, longest first: Yearly, 1 month, Weekly.
     *
     * The lifetime unlock is not offered here - the design sells the three
     * subscriptions and moved the BEST VALUE badge off it - but Play still
     * restores it for anyone who already owns it.
     */
    private fun renderPlans(list: List<PremiumOffer>) {
        binding.listPlans.removeAllViews()
        rows.clear()

        val plans = list
            .filter { !it.isLifetime && it.billingPeriod != null && it.priceMicros > 0 }
            .sortedByDescending { PlanPricing.weeksIn(it.billingPeriod) }

        if (plans.isEmpty()) {
            binding.textPlansStatus.isVisible = true
            binding.textPlansStatus.setText(
                if (billing.connected.value) R.string.premium_unavailable else R.string.premium_loading
            )
            binding.textAboveCta.text = ""
            binding.textCta.setText(R.string.paywall_choose_plan)
            setCtaEnabled(false)
            selected = null
            return
        }

        binding.textPlansStatus.isVisible = false
        weekly = plans.firstOrNull { it.billingPeriod == PERIOD_WEEK }
        // Keep the user's choice across a price refresh; otherwise open on Weekly,
        // as the design does, or the first plan if there is no weekly one.
        selected = plans.firstOrNull { it.title == selected?.title }
            ?: weekly
            ?: plans.first()

        plans.forEachIndexed { index, offer ->
            val view = layoutInflater.inflate(R.layout.item_premium_plan, binding.listPlans, false)
            if (index > 0) {
                (view.layoutParams as ViewGroup.MarginLayoutParams).topMargin = dp(PLAN_GAP_DP).toInt()
            }
            val row = PlanRow(view, offer)
            bindRow(row)
            row.view.findViewById<View>(R.id.rowPlan).setOnClickListener { select(offer) }
            binding.listPlans.addView(view)
            rows += row
            row.apply(if (offer === selected) 1f else 0f)
        }
        applySelectedCopy()
        setCtaEnabled(true)
    }

    /** One row's views and the colours it moves between as it is (de)selected. */
    private inner class PlanRow(val view: View, val offer: PremiumOffer) {
        val row: View = view.findViewById(R.id.rowPlan)
        val radio: View = view.findViewById(R.id.radioPlan)
        val check: ImageView = view.findViewById(R.id.iconPlanCheck)
        val title: TextView = view.findViewById(R.id.textPlanTitle)
        val chip: TextView = view.findViewById(R.id.textPlanSave)
        val total: TextView = view.findViewById(R.id.textPlanTotal)
        val was: TextView = view.findViewById(R.id.textPlanWas)
        val price: TextView = view.findViewById(R.id.textPlanPrice)
        val perWeek: TextView = view.findViewById(R.id.textPlanPerWeek)

        private val radius = dp(18f)
        private val stroke = dp(2f).toInt()
        private val base = GradientDrawable().apply {
            cornerRadius = radius
            setColor(color(R.color.white))
            setStroke(stroke, color(R.color.paywall_row_rule))
        }
        private val chosen = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(color(R.color.premium_cta_start), color(R.color.premium_cta_end)),
        ).apply {
            cornerRadius = radius
            setStroke(stroke, color(R.color.premium_cta_start))
            alpha = 0
        }
        private val radioShape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.white))
        }

        /** The chip's resting look: green for a saving, blue for a free trial. */
        var chipBg = color(R.color.paywall_chip_save_bg)
        var chipInk = color(R.color.paywall_chip_save_ink)

        /** 0 = unselected, 1 = selected; in between while the selection moves. */
        var fraction = 0f
            private set

        var animator: ValueAnimator? = null

        init {
            row.background = LayerDrawable(arrayOf(base, chosen))
            radio.background = radioShape
            was.paintFlags = was.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }

        /** Draws the row [t] of the way from unselected to selected. */
        fun apply(t: Float) {
            fraction = t
            chosen.alpha = (t * 255).toInt()
            row.elevation = dp(PLAN_SELECTED_ELEVATION_DP) * t
            val ink = mix(R.color.premium_ink, R.color.white, t)
            val sub = mix(R.color.premium_ink_muted, R.color.paywall_row_on_sub, t)
            title.setTextColor(ink)
            price.setTextColor(ink)
            total.setTextColor(sub)
            perWeek.setTextColor(sub)
            was.setTextColor(mix(R.color.premium_price_muted, R.color.paywall_row_on_was, t))
            chip.backgroundTintList = ColorStateList.valueOf(
                argb.evaluate(t, chipBg, color(R.color.paywall_row_on_chip)) as Int
            )
            chip.setTextColor(argb.evaluate(t, chipInk, color(R.color.white)) as Int)
            radioShape.setStroke(stroke, mix(R.color.premium_radio_idle, R.color.white, t))
            check.imageTintList = ColorStateList.valueOf(color(R.color.premium_accent))
            check.alpha = t
            row.isSelected = t >= 0.5f
        }
    }

    /**
     * Fills one row: name, the billed total, the struck weekly price, the price
     * per week, and whichever of chip and badge the plan carries.
     *
     *  - Yearly: BEST VALUE · SAVE x% badge, in gold. Its saving is in the badge,
     *    so it carries no chip.
     *  - Weekly: MOST POPULAR badge, and a "3 days free" chip when Play offers
     *    this user a trial.
     *  - Anything else (1 month): a Save x% chip.
     */
    private fun bindRow(row: PlanRow) {
        val offer = row.offer
        val save = PlanPricing.savePct(offer, weekly)
        val isWeekly = offer === weekly
        val isYearly = offer.billingPeriod == PERIOD_YEAR

        row.title.text = planTitle(offer)
        row.total.text = when (offer.billingPeriod) {
            PERIOD_WEEK -> getString(R.string.paywall_billed_weekly)
            PERIOD_MONTH -> getString(R.string.paywall_billed_monthly, offer.price)
            PERIOD_YEAR -> getString(R.string.paywall_billed_yearly, offer.price)
            else -> offer.price
        }
        row.price.text = PlanPricing.money(PlanPricing.perWeekMicros(offer), offer.currencyCode)

        val weeklyPlan = weekly
        row.was.isVisible = !isWeekly && weeklyPlan != null && save > 0
        if (row.was.isVisible && weeklyPlan != null) {
            row.was.text = PlanPricing.money(weeklyPlan.priceMicros, weeklyPlan.currencyCode)
        }

        val trialDays = PlanPricing.daysIn(offer.freeTrialPeriod)
        when {
            isYearly -> row.chip.isVisible = false
            isWeekly && trialDays > 0 -> {
                row.chip.isVisible = true
                row.chip.text = resources.getQuantityString(R.plurals.paywall_trial_free, trialDays, trialDays)
                row.chipBg = color(R.color.paywall_chip_trial_bg)
                row.chipInk = color(R.color.premium_accent)
            }
            !isWeekly && save > 0 -> {
                row.chip.isVisible = true
                row.chip.text = getString(R.string.paywall_save, save)
            }
            else -> row.chip.isVisible = false
        }

        val badge = row.view.findViewById<TextView>(R.id.badgeBestValue)
        when {
            isYearly -> {
                badge.isVisible = true
                badge.text = if (save > 0) getString(R.string.paywall_badge_best_value, save)
                else getString(R.string.paywall_badge_best_value_plain)
                badge.setBackgroundResource(R.drawable.bg_paywall_badge_value)
            }
            isWeekly -> {
                badge.isVisible = true
                badge.setText(R.string.paywall_badge_popular)
                badge.setBackgroundResource(R.drawable.bg_paywall_badge_popular)
            }
            else -> badge.isVisible = false
        }
    }

    private fun select(offer: PremiumOffer) {
        if (offer === selected) return
        selected = offer
        rows.forEach { animateSelection(it, if (it.offer === offer) 1f else 0f) }
        applySelectedCopy()
    }

    /** `transition: all .18s ease` - background, shadow and every colour together. */
    private fun animateSelection(row: PlanRow, to: Float) {
        row.animator?.cancel()
        if (row.fraction == to) return
        row.animator = ValueAnimator.ofFloat(row.fraction, to).apply {
            duration = SELECT_MS
            interpolator = easeCurve
            addUpdateListener { row.apply(it.animatedValue as Float) }
            start()
        }
    }

    /**
     * The button and the line over it, for the selected plan.
     *
     * The line always opens on the full billed amount - "3 days free, then
     * $149.99/year ($2.88/week)" - because Google Play requires the real charge
     * beside the purchase button, ahead of any breakdown. The per-week figure is
     * the aside, and drops out for the weekly plan, where it would repeat itself.
     */
    private fun applySelectedCopy() {
        val offer = selected ?: return
        val isWeekly = offer.billingPeriod == PERIOD_WEEK
        val full = when (offer.billingPeriod) {
            PERIOD_YEAR -> getString(R.string.paywall_price_per_year, offer.price)
            PERIOD_MONTH -> getString(R.string.paywall_price_per_month, offer.price)
            PERIOD_WEEK -> getString(R.string.paywall_price_per_week, offer.price)
            else -> offer.price
        }
        val perWeek = PlanPricing.money(PlanPricing.perWeekMicros(offer), offer.currencyCode)
        val trialDays = PlanPricing.daysIn(offer.freeTrialPeriod)

        if (trialDays > 0) {
            val trial = resources.getQuantityString(R.plurals.paywall_trial_free, trialDays, trialDays)
            binding.textCta.text =
                resources.getQuantityString(R.plurals.paywall_cta_trial, trialDays, trialDays)
            binding.textAboveCta.text = if (isWeekly) {
                getString(R.string.paywall_above_trial_weekly, trial, full)
            } else {
                getString(R.string.paywall_above_trial, trial, full, perWeek)
            }
        } else {
            binding.textCta.text = getString(R.string.paywall_cta_continue, full)
            binding.textAboveCta.text = if (isWeekly) {
                getString(R.string.paywall_above_plain_weekly, full)
            } else {
                getString(R.string.paywall_above_plain, full, perWeek)
            }
        }
        binding.buttonSubscribe.contentDescription = binding.textCta.text
    }

    /**
     * The design has no disabled state because it assumes prices are there. They
     * are not until Play answers, so the button waits at half strength rather
     * than swallowing taps at full.
     */
    private fun setCtaEnabled(enabled: Boolean) {
        binding.buttonSubscribe.isEnabled = enabled
        binding.buttonSubscribe.alpha = if (enabled) 1f else DISABLED_CTA_ALPHA
    }

    /**
     * A human name for the plan, from the ISO-8601 period rather than the base
     * plan id - a base plan can be called anything, a period cannot.
     */
    private fun planTitle(offer: PremiumOffer): String = when (offer.billingPeriod) {
        PERIOD_WEEK -> getString(R.string.premium_plan_weekly)
        PERIOD_MONTH -> getString(R.string.premium_plan_monthly)
        PERIOD_YEAR -> getString(R.string.premium_plan_yearly)
        else -> offer.title
    }

    /** The design's price table as offers: $149.99/yr, $19.99/mo, $9.99/wk with 3 days free. */
    private fun previewOffers(): List<PremiumOffer> = listOf(
        Triple("yearly", "P1Y", 149_990_000L),
        Triple("monthly", "P1M", 19_990_000L),
        Triple("weekly", "P1W", 9_990_000L),
    ).map { (id, period, micros) ->
        PremiumOffer(
            productId = BillingRepository.SUBSCRIPTION_ID,
            offerToken = null,
            title = id,
            price = PlanPricing.money(micros, "USD"),
            billingPeriod = period,
            isLifetime = false,
            details = null,
            priceMicros = micros,
            currencyCode = "USD",
            freeTrialPeriod = if (period == PERIOD_WEEK) "P3D" else null,
        )
    }

    // ── The loops ────────────────────────────────────────────────────────────

    /** `pwFloat`: the crown tile rises 4dp and settles, every 5s, ease-in-out. */
    private fun startCrownFloat() {
        loops += ObjectAnimator.ofFloat(binding.crownTile, View.TRANSLATION_Y, 0f, -dp(CROWN_FLOAT_DP)).apply {
            duration = CROWN_FLOAT_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = easeInOut
            start()
        }
    }

    /**
     * `pwGlow`: the button's shadow breathing between `0 10px 24px .34` and
     * `0 12px 30px .55` over 2.4s. Elevation carries the growing blur, the
     * shadow colour (API 28+) the deepening tint.
     */
    private fun startCtaGlow() {
        val view = binding.buttonSubscribe
        val from = dp(CTA_ELEVATION_LOW_DP)
        val to = dp(CTA_ELEVATION_HIGH_DP)
        val glowLow = color(R.color.premium_cta_glow_low)
        val glowHigh = color(R.color.premium_cta_glow)

        loops += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CTA_GLOW_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = easeInOut
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                view.elevation = from + (to - from) * t
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val tint = argb.evaluate(t, glowLow, glowHigh) as Int
                    view.outlineSpotShadowColor = tint
                    view.outlineAmbientShadowColor = tint
                }
            }
            start()
        }
    }

    // ── Plumbing ─────────────────────────────────────────────────────────────

    /** Play's own subscription centre - the only place a subscription can be cancelled. */
    private fun openPlaySubscriptions() {
        val uri = "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingRepository.SUBSCRIPTION_ID}&package=$packageName"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
            .onFailure { toast(R.string.premium_manage_unavailable) }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun color(@ColorRes res: Int): Int = ContextCompat.getColor(this, res)

    private fun mix(@ColorRes from: Int, @ColorRes to: Int, t: Float): Int =
        argb.evaluate(t, color(from), color(to)) as Int

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    override fun onStart() {
        super.onStart()
        // Started here rather than in initView: onStart also runs when the screen
        // comes back from Play's sheet, which is where the loops were stopped.
        startCtaGlow()
        startCrownFloat()
    }

    override fun onStop() {
        super.onStop()
        loops.forEach { it.cancel() }
        loops.clear()
        binding.crownTile.translationY = 0f
    }

    override fun onResume() {
        super.onResume()
        // Catches a purchase or cancellation made in the Play app while this
        // screen sat in the background.
        billing.refreshPurchases()
    }

    companion object {
        /** Debug builds: show the offer with sample prices (see previewOffers). */
        const val EXTRA_PREVIEW = "paywall_preview"

        private const val PERIOD_WEEK = "P1W"
        private const val PERIOD_MONTH = "P1M"
        private const val PERIOD_YEAR = "P1Y"

        /** The design's `closeDelay`, 5s by default. */
        private const val CLOSE_DELAY_SECONDS = 5
        private const val CLOSE_POP_MS = 280L
        private const val CLOSE_POP_FROM = 0.6f

        private const val SELECT_MS = 180L

        private const val CROWN_FLOAT_CYCLE_MS = 5_000L
        private const val CROWN_FLOAT_DP = 4f

        private const val CTA_GLOW_CYCLE_MS = 2_400L
        private const val CTA_ELEVATION_LOW_DP = 10f
        private const val CTA_ELEVATION_HIGH_DP = 12f

        private const val DISABLED_CTA_ALPHA = 0.45f

        private const val PLAN_GAP_DP = 10f
        private const val PLAN_SELECTED_ELEVATION_DP = 14f

        fun newIntent(context: Context): Intent = Intent(context, PremiumActivity::class.java)
    }
}
