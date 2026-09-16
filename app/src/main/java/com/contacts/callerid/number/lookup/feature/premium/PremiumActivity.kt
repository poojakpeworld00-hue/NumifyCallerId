package com.contacts.callerid.number.lookup.feature.premium

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.databinding.ActivityPremiumBinding
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.monetize.billing.BillingRepository
import com.contacts.callerid.number.lookup.monetize.billing.PremiumOffer
import com.contacts.callerid.number.lookup.monetize.billing.PremiumStore
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The Contacts Premium paywall.
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
 */
class PremiumActivity : BaseActivity<ActivityPremiumBinding>() {

    override val layoutId = R.layout.activity_premium

    private val billing by lazy { BillingRepository.getInstance(this) }

    /** The plan the user has selected; null until Play returns something. */
    private var selected: PremiumOffer? = null
    private var offers: List<PremiumOffer> = emptyList()

    override fun initView() {
        // The app draws edge to edge (BaseActivity.applyImmersiveNavigation), so
        // without this the close button sits under the status bar and is clipped
        // — which is exactly what the first build of this screen did.
        ViewCompat.setOnApplyWindowInsetsListener(binding.premiumRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.buttonClose.setOnClickListener { finish() }
        bindBenefits()

        binding.buttonSubscribe.setOnClickListener {
            selected?.let { billing.launchPurchase(this, it) }
        }

        // Restoring is a re-read of Play, not a separate purchase path. Play
        // requires it to be reachable without paying, and it is the answer to
        // "I already bought this on my old phone".
        binding.buttonRestore.setOnClickListener {
            billing.refreshPurchases()
            toast(R.string.premium_restoring)
        }

        // Cancelling or changing a subscription must go to Play, not to us — we
        // cannot cancel on the user's behalf, and hiding that is how a
        // subscription starts to feel like a trap.
        binding.buttonManage.setOnClickListener { openPlaySubscriptions() }

        // Connect if the Application's attempt has not landed yet; both calls
        // are safe because BillingRepository.start() no-ops when already ready.
        billing.start()
    }

    override fun initObservers() {
        // Entitlement. Collected rather than read once, so a purchase completing
        // in Play's sheet flips this screen the moment it does.
        lifecycleScope.launch {
            PremiumStore.isPremiumFlow.collectLatest { premium ->
                binding.stateOwned.isVisible = premium
                binding.buttonSubscribe.isVisible = !premium
            }
        }

        // Live prices.
        lifecycleScope.launch {
            billing.products.collectLatest { list ->
                offers = list
                renderPlans(list)
            }
        }
    }

    /**
     * Draws a row per plan Play returned.
     *
     * The yearly plan is preselected where one exists, because it is the one
     * most people want and an unselected list makes the button dead on arrival.
     */
    private fun renderPlans(list: List<PremiumOffer>) {
        binding.listPlans.removeAllViews()
        binding.textPlansLoading.isVisible = list.isEmpty() && billing.connected.value.not()
        binding.textPlansError.isVisible = list.isEmpty() && billing.connected.value

        if (list.isEmpty()) {
            binding.buttonSubscribe.isEnabled = false
            return
        }

        selected = list.firstOrNull { it.billingPeriod == "P1Y" } ?: list.first()

        list.forEach { offer ->
            val row = layoutInflater.inflate(R.layout.item_premium_plan, binding.listPlans, false)
            row.findViewById<TextView>(R.id.textPlanTitle).text = planTitle(offer)
            row.findViewById<TextView>(R.id.textPlanSub).text = planSubtitle(offer)
            row.findViewById<TextView>(R.id.textPlanPrice).text = offer.price
            row.setOnClickListener {
                selected = offer
                highlightSelection()
            }
            row.tag = offer
            binding.listPlans.addView(row)
        }
        highlightSelection()
        binding.buttonSubscribe.isEnabled = true
    }

    private fun highlightSelection() {
        for (i in 0 until binding.listPlans.childCount) {
            val row = binding.listPlans.getChildAt(i)
            val isSelected = row.tag === selected
            row.isActivated = isSelected
            row.findViewById<ImageView>(R.id.iconPlanTick).isActivated = isSelected
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

    private fun bindBenefits() {
        val benefits = listOf(
            R.string.premium_benefit_ads,
            R.string.premium_benefit_reveal,
            R.string.premium_benefit_block,
            R.string.premium_benefit_ai,
        )
        benefits.forEach { res ->
            val row = layoutInflater.inflate(
                R.layout.item_premium_benefit, binding.listBenefits, false
            )
            row.findViewById<TextView>(R.id.textBenefit).setText(res)
            binding.listBenefits.addView(row)
        }
    }

    /** Play's own subscription centre — the only place a subscription can be cancelled. */
    private fun openPlaySubscriptions() {
        val uri = "https://play.google.com/store/account/subscriptions" +
            "?sku=${BillingRepository.SUBSCRIPTION_ID}&package=$packageName"
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri.toUri())) }
            .onFailure { toast(R.string.premium_manage_unavailable) }
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()

    override fun onResume() {
        super.onResume()
        // Catches a purchase or cancellation made in the Play app while this
        // screen sat in the background.
        billing.refreshPurchases()
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, PremiumActivity::class.java)
    }
}
