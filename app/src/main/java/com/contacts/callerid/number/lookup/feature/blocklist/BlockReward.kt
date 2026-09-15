package com.contacts.callerid.number.lookup.feature.blocklist

import android.app.Activity
import android.content.Context
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.repository.BlocklistRepository
import com.contacts.callerid.number.lookup.monetize.delivery.RewardPrompt
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore

/**
 * Puts a rewarded ad in front of blocking a number once the free slots are gone
 * — the single gate behind every Block action in the app.
 *
 * The first [DEFAULT_FREE_QUOTA] numbers (Remote Config `blocklist_free_quota`)
 * are free. Past that, blocking runs a short confirmation and a rewarded ad, and
 * the number is added only once the reward has been earned.
 *
 * The quota is measured against how many numbers are *currently* blocked rather
 * than how many have ever been added, so unblocking gives the slot back. That is
 * the reading a user can hold in their head — you pay for the slots you keep —
 * and it avoids charging twice for a number someone unblocks and blocks again.
 *
 * Sibling of [com.contacts.callerid.number.lookup.feature.finder.NameRevealReward], which
 * does the same job for revealing a caller name.
 */
object BlockReward {

    /** Remote Config key: how many numbers may be blocked without watching an ad. */
    const val RC_FREE_QUOTA = "blocklist_free_quota"

    /**
     * Compiled default, used until a config arrives and whenever the key is
     * absent — so a stale config never turns the gate into a surprise paywall.
     */
    const val DEFAULT_FREE_QUOTA = 3

    /**
     * The configured quota. Negative means "never gate", which is how the feature
     * is switched off from Remote Config without a release; zero means every
     * block needs an ad.
     */
    fun freeQuota(context: Context): Int =
        AdPreferenceStore.getInstance(context).getInt(RC_FREE_QUOTA, DEFAULT_FREE_QUOTA)

    /**
     * Runs [onAllowed] when [number] may be blocked, after a rewarded ad if the
     * free slots are used up. It does not touch the blocklist itself — each
     * caller keeps its own add, toast and refresh — so this stays a gate and
     * nothing more.
     *
     * Free, with no dialog, when:
     *  - the number is already blocked (adding it again is a no-op),
     *  - ads are off for this install,
     *  - the quota is negative, or
     *  - fewer than [freeQuota] numbers are blocked.
     *
     * Cancelling the dialog runs nothing. A device that cannot fill a rewarded ad
     * still gets through: [RewardedAdPresenter] falls back rather than failing, so
     * this is a gate on attention, never a wall.
     */
    fun allow(activity: Activity, number: String, onAllowed: () -> Unit) {
        val value = number.trim()
        if (value.isEmpty()) return

        if (!needsReward(activity, value)) {
            onAllowed()
            return
        }
        confirm(activity, value, onAllowed)
    }

    /** Whether blocking [number] right now would cost an ad. */
    fun needsReward(context: Context, number: String): Boolean {
        val repository = BlocklistRepository(context)
        if (repository.isNumberBlocked(number)) return false
        if (!AdPreferenceStore.getInstance(context).getBoolean("IsAdsON")) return false

        val quota = freeQuota(context)
        if (quota < 0) return false
        return repository.getAll().size >= quota
    }

    /**
     * The shared rewarded-ad prompt, with nothing masked — blocking reveals no
     * hidden value, so the strip carries the number itself rather than a row of
     * dots standing for something withheld.
     */
    private fun confirm(activity: Activity, number: String, onAllowed: () -> Unit) {
        RewardPrompt.show(
            activity,
            RewardPrompt.blockUnlock(number, freeQuota(activity)),
            onAllowed,
        )
    }
}
