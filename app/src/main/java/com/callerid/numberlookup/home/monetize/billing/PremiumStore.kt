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
    private const val KEY_PLAN = "plan"
    private const val KEY_SINCE = "purchased_at"
    private const val KEY_TERM_DAYS = "term_days"

    /** What was bought. Settings says different things about each. */
    enum class Plan { NONE, SUBSCRIPTION, LIFETIME }

    /**
     * What Premium a holder has, and how far through it they are.
     *
     * [daysUsed] and [daysLeft] are worked out from the last purchase or
     * renewal Play reported, plus the length of the term. They are an estimate,
     * and deliberately not presented as anything more: the real expiry lives on
     * Play's servers, and a grace period, a pause or an upgrade all move it
     * without the app hearing about it. Null for a lifetime unlock, which has no
     * end to count towards.
     */
    data class Standing(
        val plan: Plan,
        val since: Long,
        val termDays: Int,
        val daysUsed: Int?,
        val daysLeft: Int?,
    )

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

    /**
     * What the holder bought, when, and how far through the term they are.
     *
     * A subscription whose term has run past its end reports zero left rather
     * than a negative: Play has not told us it lapsed, and until it does the
     * entitlement stands - an app that starts counting backwards is telling the
     * user something it does not actually know.
     */
    fun standing(context: Context): Standing {
        val p = prefs(context)
        val plan = runCatching { Plan.valueOf(p.getString(KEY_PLAN, null) ?: "") }
            .getOrDefault(if (isPremium(context)) Plan.SUBSCRIPTION else Plan.NONE)
        val since = p.getLong(KEY_SINCE, 0L)
        val termDays = p.getInt(KEY_TERM_DAYS, DEFAULT_TERM_DAYS)

        if (plan != Plan.SUBSCRIPTION || since <= 0L) {
            return Standing(plan, since, termDays, daysUsed = null, daysLeft = null)
        }

        val elapsed = ((System.currentTimeMillis() - since) / DAY_MS).toInt().coerceAtLeast(0)
        return Standing(
            plan = plan,
            since = since,
            termDays = termDays,
            daysUsed = elapsed,
            daysLeft = (termDays - elapsed).coerceAtLeast(0),
        )
    }

    /**
     * Records which plan is behind the entitlement.
     *
     * Kept apart from [setPremium] because the two answer different questions
     * and arrive at different times: whether Premium is on, and what bought it.
     */
    fun setPlan(context: Context, plan: Plan, since: Long, termDays: Int = DEFAULT_TERM_DAYS) {
        prefs(context).edit()
            .putString(KEY_PLAN, plan.name)
            .putLong(KEY_SINCE, since)
            .putInt(KEY_TERM_DAYS, termDays)
            .apply()
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

    /** A month, for a subscription whose base plan we never saw. */
    private const val DEFAULT_TERM_DAYS = 30

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
