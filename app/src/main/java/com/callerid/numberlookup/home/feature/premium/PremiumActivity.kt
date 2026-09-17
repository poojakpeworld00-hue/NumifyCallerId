package com.callerid.numberlookup.home.feature.premium

import com.callerid.numberlookup.home.feature.MainShellActivity
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.Typography
import com.callerid.numberlookup.home.common.openPolicyLink
import com.callerid.numberlookup.home.common.openTermLink
import com.callerid.numberlookup.home.databinding.ActivityPremiumBinding
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.billing.BillingRepository
import com.callerid.numberlookup.home.monetize.billing.PremiumOffer
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
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
 * The handoff gives the hero five separate entrances rather than one, and the
 * page three more. All of them are transcribed, not approximated:
 *
 * | design                       | here                                        |
 * |------------------------------|---------------------------------------------|
 * | `.a1` `fadeUp` 0ms           | header, hero title row                      |
 * | `.a2` `fadeUp` 100ms         | comparison table                            |
 * | `.a3` `fadeUp` 200ms         | plans, fine print                           |
 * | `.a1b` `fadeUp` 380ms 450ms  | hero subtitle                               |
 * | `.ttl-1` `slideFromLeft` 60  | "Free"                                      |
 * | `.ttl-2` `slideFromLeft` 150 | "vs"                                        |
 * | `.ttl-3` `slideFromRight` 240| "Premium"                                   |
 * | `.crown-pop` `popIn` 380ms   | the crown                                   |
 * | `.spark-spin` `popIn` 0ms    | the spark, then `sparkPulse` forever        |
 * | `shine` 4.5s linear 0.9s     | [ShineTextView], which owns it              |
 * | `ctaGlow` 2.4s               | [startCtaGlow]                              |
 *
 * Two of those pile onto the same element on purpose: the spark and the crown
 * both carry a `.ttl-*` class *and* a `.crown-pop`/`.spark-spin` one, and CSS
 * resolves the conflict by source order — the later rule wins and the slide
 * never runs on them. That is why they pop rather than slide here.
 *
 * Every animation moves only `alpha`, `translation*`, `scale*`, `rotation` or
 * `elevation` — properties the framework hands to the render thread without a
 * relayout — so the two looping ones cost nothing per frame and the page holds
 * 60fps while it scrolls. They start in [onStart] and are cancelled in [onStop];
 * an infinite animator left running behind a backgrounded screen is a real
 * battery cost.
 */
class PremiumActivity : BaseActivity<ActivityPremiumBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "PremiumActivity"

    override val layoutId = R.layout.activity_premium

    /**
     * Off, uniquely in this app.
     *
     * Every size on this screen is transcribed from the handoff, and the app-wide
     * +8% ([Typography]) pushes the 26sp hero title and the 14sp table rows into
     * the space the design leaves around them — the rows stop reading as the
     * design's. The user's own system font size still applies.
     */
    override val appliesAppTextScale = false

    private val billing by lazy { BillingRepository.getInstance(this) }

    /** The plan the user has selected; null until Play returns something. */
    private var selected: PremiumOffer? = null

    /** The looping animations, held so [onStop] can stop them. */
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

        bindCompareTable()

        binding.buttonClose.setOnClickListener { finish() }

        // Back to a rebuilt app rather than to whatever was underneath.
        //
        // Ad gates read the entitlement, so every screen built from here on is
        // ad-free - but screens already sitting on the stack were built while it
        // was still false and are holding ad views they have no reason to drop.
        // Clearing the task is the difference between being told the ads are gone
        // and finding they are.
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

    // ── Entrance ─────────────────────────────────────────────────────────────

    /**
     * One entry per animated piece: the view, how it enters, and when.
     *
     * Read straight off the design's class list. The hero card is absent on
     * purpose — it has no class, so it is simply there from the first frame
     * while its contents arrive one at a time.
     */
    private fun entrancePieces(): List<Entrance> = listOf(
        Entrance(binding.groupHeader, Move.UP, 420L, 0L),
        Entrance(binding.groupHeroTitle, Move.UP, 420L, 0L),
        Entrance(binding.iconHeroSpark, Move.POP, 500L, 0L),
        Entrance(binding.textHeroFree, Move.LEFT, 500L, 60L),
        Entrance(binding.textHeroVs, Move.LEFT, 500L, 150L),
        Entrance(binding.textHeroPremium, Move.RIGHT, 550L, 240L),
        Entrance(binding.iconHeroCrown, Move.POP, 500L, 380L),
        Entrance(binding.textHeroSub, Move.UP, 450L, 380L),
        Entrance(binding.cardCompare, Move.UP, 420L, 100L),
        Entrance(binding.listPlans, Move.UP, 420L, 200L),
        Entrance(binding.textPlansStatus, Move.UP, 420L, 200L),
        Entrance(binding.textFinePrint, Move.UP, 420L, 200L),
    )

    /**
     * Puts every animated piece into its pre-animation state before the first
     * frame is drawn.
     *
     * This is the design's `animation-fill-mode: backwards`. Without it each
     * piece paints once at its final position and then jumps back to the start,
     * which reads as a flicker rather than an entrance.
     */
    private fun prepareEntrance() {
        entrancePieces().forEach { piece ->
            piece.view.alpha = HIDDEN_ALPHA
            when (piece.move) {
                Move.UP -> piece.view.translationY = dp(FADE_UP_DP)
                Move.LEFT -> piece.view.translationX = dp(-SLIDE_LEFT_DP)
                Move.RIGHT -> piece.view.translationX = dp(SLIDE_RIGHT_DP)
                Move.POP -> {
                    piece.view.scaleX = POP_SCALE_FROM
                    piece.view.scaleY = POP_SCALE_FROM
                    piece.view.rotation = POP_ROTATION_FROM
                }
            }
        }
    }

    private fun playEntrance() {
        if (entranceHasPlayed) return
        entranceHasPlayed = true

        entrancePieces().forEach { piece ->
            val view = piece.view
            val animators = mutableListOf(
                ObjectAnimator.ofFloat(view, View.ALPHA, HIDDEN_ALPHA, 1f)
            )
            when (piece.move) {
                Move.UP -> animators +=
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dp(FADE_UP_DP), 0f)

                Move.LEFT -> animators +=
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, dp(-SLIDE_LEFT_DP), 0f)

                Move.RIGHT -> animators +=
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_X, dp(SLIDE_RIGHT_DP), 0f)

                Move.POP -> {
                    animators += ObjectAnimator.ofFloat(view, View.SCALE_X, POP_SCALE_FROM, 1f)
                    animators += ObjectAnimator.ofFloat(view, View.SCALE_Y, POP_SCALE_FROM, 1f)
                    animators += ObjectAnimator.ofFloat(view, View.ROTATION, POP_ROTATION_FROM, 0f)
                }
            }
            AnimatorSet().apply {
                playTogether(animators.toList())
                duration = piece.durationMs
                startDelay = piece.delayMs
                interpolator = piece.move.curve()
                start()
            }
        }
        startSparkPulse()
    }

    /** How one piece arrives, and on which of the design's four curves. */
    private enum class Move { UP, LEFT, RIGHT, POP }

    private fun Move.curve(): Interpolator = when (this) {
        Move.UP -> interpolator(R.interpolator.premium_fade_up)
        Move.LEFT, Move.RIGHT -> interpolator(R.interpolator.premium_slide)
        Move.POP -> interpolator(R.interpolator.premium_pop)
    }

    private data class Entrance(
        val view: View,
        val move: Move,
        val durationMs: Long,
        val delayMs: Long,
    )

    // ── The loops ────────────────────────────────────────────────────────────

    /**
     * `sparkPulse`: `0%,100% { scale(1) rotate(0) } 50% { scale(1.14) rotate(10deg) }`
     * over 2.6s, `ease-in-out`, starting once `popIn` has finished.
     *
     * Half the cycle on `REVERSE` rather than a three-keyframe animator: CSS
     * applies the timing function between each *pair* of keyframes, so easing
     * each half separately is what the design actually does.
     */
    private fun startSparkPulse() {
        val spark = binding.iconHeroSpark
        val curve = interpolator(R.interpolator.premium_ease_in_out)
        listOf(
            ObjectAnimator.ofFloat(spark, View.SCALE_X, 1f, SPARK_PULSE_SCALE),
            ObjectAnimator.ofFloat(spark, View.SCALE_Y, 1f, SPARK_PULSE_SCALE),
            ObjectAnimator.ofFloat(spark, View.ROTATION, 0f, SPARK_PULSE_ROTATION),
        ).forEach { animator ->
            loops += animator.apply {
                duration = SPARK_PULSE_CYCLE_MS / 2
                startDelay = SPARK_PULSE_DELAY_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = curve
                start()
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

    // ── The comparison table ─────────────────────────────────────────────────

    /**
     * One row per capability, in the design's order: what it is, what the free
     * tier gives you, and a tick for Premium.
     *
     * Static rather than driven by the real limits, because it is the offer, not
     * a status readout — the "5 / day" a free user sees here is the same cap the
     * lookup screen enforces, and stating it is the whole point of the table.
     */
    private fun bindCompareTable() {
        val rows = listOf(
            CompareRow(R.string.premium_row_ads, R.string.premium_row_ads_sub, R.string.premium_row_ads_free),
            CompareRow(R.string.premium_row_lookup, R.string.premium_row_lookup_sub, R.string.premium_row_lookup_free),
            CompareRow(R.string.premium_row_ai, R.string.premium_row_ai_sub, R.string.premium_row_ai_free),
            CompareRow(R.string.premium_row_block, R.string.premium_row_block_sub, R.string.premium_row_block_free),
        )

        binding.listCompare.removeAllViews()
        rows.forEachIndexed { index, item ->
            val row = layoutInflater.inflate(
                R.layout.item_premium_compare, binding.listCompare, false
            )
            row.findViewById<TextView>(R.id.textRowTitle).setText(item.title)
            row.findViewById<TextView>(R.id.textRowSub).setText(item.subtitle)
            row.findViewById<TextView>(R.id.textRowFree).setText(item.free)

            // The design hides the last hairline with the card's overflow:hidden.
            row.findViewById<View>(R.id.rowDivider).isVisible = index < rows.lastIndex

            binding.listCompare.addView(row)
        }
    }

    private data class CompareRow(
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @StringRes val free: Int,
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
            binding.textFinePrint.setText(R.string.premium_fine_print_plain)
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
            row.findViewById<TextView>(R.id.textPlanPrice).text = offer.price
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

            // Only the price changes colour with selection; the design leaves the
            // plan name at full ink in both rows.
            row.findViewById<TextView>(R.id.textPlanPrice).setTextColor(
                color(if (isSelected) R.color.premium_accent else R.color.premium_ink)
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
            binding.textFinePrint.setText(R.string.premium_fine_print_plain)
        } else {
            binding.textFinePrint.text = getString(R.string.premium_fine_print, offer.price)
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
        startCtaGlow()
        binding.textHeroPremium.start()
        binding.premiumRoot.doOnLayout { playEntrance() }
        // The entrance runs once; on a later onStart the spark still needs its
        // loop back, since onStop cancelled it.
        if (entranceHasPlayed && loops.none { it.target === binding.iconHeroSpark }) {
            startSparkPulse()
        }
    }

    override fun onStop() {
        super.onStop()
        loops.forEach { it.cancel() }
        loops.clear()
        binding.textHeroPremium.stop()
        // Cancelling mid-pulse leaves the spark wherever it stopped.
        binding.iconHeroSpark.apply {
            scaleX = 1f
            scaleY = 1f
            rotation = 0f
        }
    }

    override fun onResume() {
        super.onResume()
        // Catches a purchase or cancellation made in the Play app while this
        // screen sat in the background.
        billing.refreshPurchases()
    }

    /** `target` is only set on ObjectAnimator; a plain ValueAnimator has none. */
    private val ValueAnimator.target: Any?
        get() = (this as? ObjectAnimator)?.target

    companion object {
        /** The design's `opacity: 0.001`, not 0 — it keeps the layer warm. */
        private const val HIDDEN_ALPHA = 0.001f

        private const val FADE_UP_DP = 12f
        private const val SLIDE_LEFT_DP = 14f
        private const val SLIDE_RIGHT_DP = 16f
        private const val POP_SCALE_FROM = 0.5f
        private const val POP_ROTATION_FROM = -18f

        private const val SPARK_PULSE_CYCLE_MS = 2_600L
        private const val SPARK_PULSE_DELAY_MS = 500L
        private const val SPARK_PULSE_SCALE = 1.14f
        private const val SPARK_PULSE_ROTATION = 10f

        private const val CTA_GLOW_CYCLE_MS = 2_400L
        private const val CTA_ELEVATION_LOW_DP = 12f
        private const val CTA_ELEVATION_HIGH_DP = 16f

        private const val DISABLED_CTA_ALPHA = 0.45f

        private const val PLAN_GAP_DP = 10f
        private const val PLAN_SELECTED_ELEVATION_DP = 10f

        fun newIntent(context: Context): Intent = Intent(context, PremiumActivity::class.java)
    }
}
