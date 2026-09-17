package com.callerid.numberlookup.home.feature.finder

import android.content.Context
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore

/**
 * How many hidden names a free user may open, and what happens past that.
 *
 * Every name behind the eye used to be reachable: watch an ad, see a name, as
 * many times as you liked. That is the whole of what Premium sells on these
 * screens, given away one ad at a time. So an allowance of
 * [revealableLimit] names is free, and everything past it is Premium's - those
 * rows show a lock instead of an eye and go to the paywall rather than an ad.
 *
 * **Spent, not positional.** The first version counted by row index: rows one to
 * five offered the eye and the sixth wore a lock. That read better in a list,
 * and it gave the allowance away for nothing - clearing the lookup history put
 * every row back at index zero, so five more names were free, and again after
 * the next clear. The allowance is now a count of names actually opened, kept
 * away from the history it used to be derived from, so clearing the list cannot
 * refill it.
 *
 * The limit comes from Remote Config, because where the line sits is a pricing
 * question and pricing questions should not need a release.
 */
object NameRevealPolicy {

    /** Remote Config key, carried in the same blob as the ad settings. */
    private const val RC_KEY = "name_reveal_free_quota"

    /**
     * Its own store.
     *
     * Deliberately not the lookup history's, and not the app's general
     * preferences: this number has to outlive "clear history", which is exactly
     * the button a user reaches for when the names stop being free.
     */
    private const val PREFS = "name_reveal_policy"
    private const val KEY_SPENT = "spent"

    /** Used when Remote Config has not said otherwise. */
    const val DEFAULT_LIMIT = 5

    /**
     * How many names a free user may open in total.
     *
     * Zero is a legitimate answer - it locks the feature to Premium outright -
     * so only negatives are corrected.
     */
    fun revealableLimit(context: Context): Int =
        AdPreferenceStore.getInstance(context)
            .getInt(RC_KEY, DEFAULT_LIMIT)
            .coerceAtLeast(0)

    /** Premium holds no line: every name is simply there. */
    fun unlocksEverything(context: Context): Boolean = PremiumStore.isPremium(context)

    /** How many of the allowance have been opened. */
    fun spent(context: Context): Int = prefs(context).getInt(KEY_SPENT, 0)

    /** What is left of it, floored at zero. */
    fun remaining(context: Context): Int =
        (revealableLimit(context) - spent(context)).coerceAtLeast(0)

    /**
     * Whether there is still an allowance to spend.
     *
     * A name already open does not ask this - it is open, and re-locking it
     * because the allowance ran out afterwards would take back something the
     * user already paid an ad for.
     */
    fun canReveal(context: Context): Boolean =
        unlocksEverything(context) || remaining(context) > 0

    /**
     * Records one name opened.
     *
     * Called where the reveal actually lands - after the reward, never on the
     * tap - so an ad the user backs out of costs them nothing. Premium spends
     * nothing at all.
     */
    fun consume(context: Context) {
        if (unlocksEverything(context)) return
        prefs(context).edit().putInt(KEY_SPENT, spent(context) + 1).apply()
    }

    /** Debug and support only: hands the whole allowance back. */
    fun resetSpent(context: Context) {
        prefs(context).edit().remove(KEY_SPENT).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
