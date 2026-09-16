package com.contacts.callerid.number.lookup.monetize.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything that talks to Google Play Billing.
 *
 * The whole purchase story, in the order it actually happens:
 *
 *  1. **Connect.** [start] opens a connection to the Play Store app on the
 *     device. Nothing else works until this succeeds, and it can fail for
 *     ordinary reasons (Play updating, no Play Services on the device), so it
 *     retries with a backoff rather than giving up.
 *  2. **Restore.** Immediately on connect, [refreshPurchases] asks Play what
 *     this account already owns. **This is the step people forget**, and without
 *     it a paying user who reinstalls, or signs in on a second device, is silently
 *     charged-but-not-premium. It also runs on every app start, which is how a
 *     refund or a cancelled subscription takes the entitlement back.
 *  3. **Offer.** [loadProducts] fetches live prices and localised currency from
 *     Play. Prices are never hardcoded — Play decides them per country, and a
 *     hardcoded "$4.99" is wrong in most of the world.
 *  4. **Buy.** [launchPurchase] opens Play's own sheet. The app never sees a card
 *     number; it hands Play a product and gets a callback.
 *  5. **Grant and acknowledge.** [handlePurchase] grants the entitlement and then
 *     **acknowledges** the purchase. Acknowledgement is not optional: Play
 *     automatically refunds any purchase left unacknowledged for three days, so a
 *     missing acknowledge reads to the user as "I paid and then it was taken
 *     away".
 *
 * A [PENDING] purchase — cash at a counter, a parent's approval — is deliberately
 * not granted. It is not paid for yet; it becomes PURCHASED later and arrives
 * through the same listener or the next restore.
 */
class BillingRepository private constructor(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * `enablePendingPurchases` is mandatory from Billing 6 and the params form is
     * mandatory from 7 — the client refuses to build without it. It only declares
     * that we can cope with a purchase that is not yet paid for; see
     * [handlePurchase], which declines to grant one.
     */
    private val client: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private val _products = MutableStateFlow<List<PremiumOffer>>(emptyList())

    /** Live, localised offers to show on the paywall. Empty until Play answers. */
    val products: StateFlow<List<PremiumOffer>> = _products.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var retryDelayMs = 1_000L

    // ── 1. Connect ───────────────────────────────────────────────────────────

    /** Opens the Play connection and keeps it open, retrying if it drops. */
    fun start() {
        if (client.isReady) return
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "connected")
                    _connected.value = true
                    retryDelayMs = 1_000L
                    // Restore first, then prices: knowing what is already owned
                    // decides whether the paywall should be shown at all.
                    refreshPurchases()
                    loadProducts()
                } else {
                    Log.w(TAG, "setup failed: ${result.responseCode} ${result.debugMessage}")
                    scheduleRetry()
                }
            }

            override fun onBillingServiceDisconnected() {
                _connected.value = false
                scheduleRetry()
            }
        })
    }

    /**
     * Exponential backoff to a 15-minute ceiling.
     *
     * Play disconnects routinely — the Store app updating is enough — and a tight
     * retry loop against a service that is down is a battery drain that fixes
     * nothing.
     */
    private fun scheduleRetry() {
        scope.launch {
            kotlinx.coroutines.delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(15 * 60_000L)
            start()
        }
    }

    // ── 2. Restore ───────────────────────────────────────────────────────────

    /**
     * Asks Play what this account owns and sets the entitlement to match.
     *
     * Both product types are queried because Premium can come from either a
     * subscription or the lifetime unlock, and the result is authoritative in
     * both directions — finding nothing revokes, which is what makes a refund or
     * a lapsed subscription take effect.
     *
     * Note the deliberate ordering: the entitlement is only written after *both*
     * queries have answered. Writing after each would briefly revoke a lifetime
     * owner while the subscription query came back empty.
     */
    fun refreshPurchases() {
        scope.launch {
            if (!client.isReady) return@launch

            val subs = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS).build()
            ).purchasesList

            val inApp = client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.INAPP).build()
            ).purchasesList

            val owned = (subs + inApp).filter { it.isEntitling() }
            Log.d(TAG, "restore: ${owned.size} entitling purchase(s)")

            PremiumStore.setPremium(appContext, owned.isNotEmpty())
            // Anything owned but never acknowledged would be auto-refunded in
            // three days, so this catches a purchase that completed while the
            // app was killed.
            owned.forEach { acknowledgeIfNeeded(it) }
        }
    }

    // ── 3. Offer ─────────────────────────────────────────────────────────────

    /**
     * Fetches live prices from Play.
     *
     * The subscription is queried as one product whose base plans are read back
     * generically rather than hardcoded, so adding a weekly plan later is a Play
     * Console change with no release. The lifetime unlock is a separate one-time
     * product because Play models the two differently.
     */
    fun loadProducts() {
        scope.launch {
            if (!client.isReady) return@launch
            val offers = mutableListOf<PremiumOffer>()

            querySubscriptions()?.let { offers += it }
            queryLifetime()?.let { offers += it }

            if (offers.isEmpty()) {
                Log.w(
                    TAG,
                    "no products returned — check the IDs exist, are ACTIVE, and " +
                        "that this build is signed with the uploaded key"
                )
            }
            _products.value = offers
        }
    }

    private suspend fun querySubscriptions(): List<PremiumOffer>? {
        val result = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SUBSCRIPTION_ID)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            ).build()
        )
        val details = result.productDetailsList?.firstOrNull() ?: return null

        // One Play subscription product carries many base plans (monthly,
        // yearly, …), each with its own offer token. The token is what the
        // purchase flow actually needs — a subscription cannot be bought by
        // product id alone.
        return details.subscriptionOfferDetails?.map { offer ->
            val phase = offer.pricingPhases.pricingPhaseList.last()
            PremiumOffer(
                productId = details.productId,
                offerToken = offer.offerToken,
                title = offer.basePlanId,
                price = phase.formattedPrice,
                billingPeriod = phase.billingPeriod,
                isLifetime = false,
                details = details,
            )
        }
    }

    private suspend fun queryLifetime(): PremiumOffer? {
        val result = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(LIFETIME_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()
        )
        val details = result.productDetailsList?.firstOrNull() ?: return null
        val price = details.oneTimePurchaseOfferDetails ?: return null
        return PremiumOffer(
            productId = details.productId,
            offerToken = null,
            title = details.name,
            price = price.formattedPrice,
            billingPeriod = null,
            isLifetime = true,
            details = details,
        )
    }

    // ── 4. Buy ───────────────────────────────────────────────────────────────

    /**
     * Opens Play's purchase sheet. The result does not come back here — it
     * arrives in [onPurchasesUpdated], including when the user completes the
     * purchase after the app was backgrounded.
     */
    fun launchPurchase(activity: Activity, offer: PremiumOffer) {
        if (!client.isReady) {
            start()
            return
        }
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.details)
            .apply { offer.offerToken?.let { setOfferToken(it) } }
            .build()

        client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
        )
    }

    // ── 5. Grant and acknowledge ─────────────────────────────────────────────

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases?.forEach { scope.launch { handlePurchase(it) } }

            // Not an error: the user closed the sheet. Saying nothing is right.
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Log.d(TAG, "purchase cancelled by user")

            // Already owned but not reflected here — a restore is the fix, not
            // an error message.
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refreshPurchases()

            else -> Log.w(TAG, "purchase failed: ${result.responseCode} ${result.debugMessage}")
        }
    }

    private suspend fun handlePurchase(purchase: Purchase) {
        if (!purchase.isEntitling()) {
            Log.d(TAG, "purchase not entitling yet (state=${purchase.purchaseState})")
            return
        }
        PremiumStore.setPremium(appContext, true)
        acknowledgeIfNeeded(purchase)
    }

    /**
     * Tells Play the entitlement was delivered.
     *
     * **Play refunds anything left unacknowledged for three days.** It is safe to
     * call twice, so it runs on both the purchase path and the restore path
     * rather than being tracked separately.
     */
    private suspend fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val result = client.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        )
        Log.d(TAG, "acknowledge: ${result.responseCode}")
    }

    /** PURCHASED, and not a PENDING purchase that has not been paid for yet. */
    private fun Purchase.isEntitling(): Boolean =
        purchaseState == Purchase.PurchaseState.PURCHASED

    companion object {
        private const val TAG = "Billing"

        /**
         * Play Console product ids. **These must match the Console exactly** — a
         * typo returns an empty product list and a paywall with no prices, which
         * is the single most common way this integration appears broken.
         *
         * Create in Play Console → Monetise:
         *  - Subscriptions → a subscription with id [SUBSCRIPTION_ID], and one
         *    base plan per price you want to offer (e.g. `monthly`, `yearly`).
         *    Base plan ids are read back at runtime, so they are not listed here.
         *  - In-app products → a **non-consumable** product with id [LIFETIME_ID].
         */
        const val SUBSCRIPTION_ID = "contacts_premium_sub"
        const val LIFETIME_ID = "contacts_premium_lifetime"

        @Volatile
        private var instance: BillingRepository? = null

        /**
         * One connection per process. Billing connections are expensive and Play
         * rate-limits reconnects, so every screen shares this.
         */
        fun getInstance(context: Context): BillingRepository =
            instance ?: synchronized(this) {
                instance ?: BillingRepository(context).also { instance = it }
            }
    }
}

/** One purchasable option, with the price Play localised for this user. */
data class PremiumOffer(
    val productId: String,
    /** Required for subscriptions, null for the one-time unlock. */
    val offerToken: String?,
    val title: String,
    /** Already formatted and localised by Play — never build this yourself. */
    val price: String,
    /** ISO-8601 period such as `P1M` / `P1Y`; null for lifetime. */
    val billingPeriod: String?,
    val isLifetime: Boolean,
    val details: ProductDetails,
)
