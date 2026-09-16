package com.callerid.numberlookup.home.monetize.billing

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this install has Contacts Premium.
 *
 * One boolean, written only by [BillingRepository] after Play has confirmed a
 * purchase, and read by every gate in the app. Kept in SharedPreferences so the
 * app knows the answer at cold start — before the billing connection is up and
 * before any network is available — because the alternative is showing ads to a
 * paying user for the first second of every launch.
 *
 * **This is a cache, not the record.** Play is the record. Anyone with a rooted
 * device can flip this flag, and that is accepted: the whole entitlement here
 * unlocks conveniences in a free app, not anything worth server-side
 * verification. [BillingRepository.refreshPurchases] re-reads Play on every
 * start, so the cache is corrected — in both directions — within a second of
 * launch. A refund or a lapsed subscription revokes it on the next run.
 *
 * [isPremium] is also a flow so a screen can react the instant a purchase
 * completes without polling or being restarted.
 */
object PremiumStore {

    private const val PREFS = "premium_store"
    private const val KEY_PREMIUM = "is_premium"

    private val _isPremium = MutableStateFlow(false)

    /** Observable entitlement, for screens that should change the moment it does. */
    val isPremiumFlow: StateFlow<Boolean> = _isPremium.asStateFlow()

    /**
     * Whether Premium is active right now.
     *
     * Safe to call from anywhere, including before [init]: it falls back to
     * reading the preference directly, so a gate that runs early cannot be
     * answered "false" merely because nothing had warmed the cache yet.
     */
    fun isPremium(context: Context): Boolean {
        if (_isPremium.value) return true
        return prefs(context).getBoolean(KEY_PREMIUM, false).also { stored ->
            if (stored) _isPremium.value = true
        }
    }

    /** Loads the cached entitlement into memory. Call once from the Application. */
    fun init(context: Context) {
        _isPremium.value = prefs(context).getBoolean(KEY_PREMIUM, false)
    }

    /**
     * Records what Play says. Called with `false` too — a refund, a cancelled
     * subscription or a restore that finds nothing all have to be able to take
     * the entitlement away, or the first purchase would be permanent.
     */
    fun setPremium(context: Context, premium: Boolean) {
        if (_isPremium.value == premium &&
            prefs(context).getBoolean(KEY_PREMIUM, false) == premium
        ) return
        prefs(context).edit().putBoolean(KEY_PREMIUM, premium).apply()
        _isPremium.value = premium
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
