package com.callerid.numberlookup.home.feature.finder

import android.content.Context
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore

/**
 * How many hidden names a free user may open, and what happens past that.
 *
 * Every name behind the eye used to be reachable: watch an ad, see a name, as
 * many times as you liked. That is the whole of what Premium sells on these
 * screens, given away one ad at a time.
 *
 * So the first [revealableLimit] stay exactly as they were - the eye, the
 * rewarded ad, the name - and everything past that is Premium's. Those rows show
 * a lock instead of an eye and go to the paywall rather than to an ad.
 *
 * The limit comes from Remote Config, because where the line sits is a pricing
 * question and pricing questions should not need a release.
 */
object NameRevealPolicy {

    /** Remote Config key, carried in the same blob as the ad settings. */
    private const val RC_KEY = "FreeNameReveals"

    /** Used when Remote Config has not said otherwise. */
    const val DEFAULT_LIMIT = 5

    /**
     * How many names a free user may open on one screen, counting from the top.
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

    /**
     * Whether the name at [index] is one a free user may open.
     *
     * Index, not a running tally: a list that stops offering after the fifth tap
     * looks broken, while a list whose sixth row wears a lock explains itself.
     */
    fun isRevealable(context: Context, index: Int): Boolean =
        unlocksEverything(context) || index < revealableLimit(context)
}
