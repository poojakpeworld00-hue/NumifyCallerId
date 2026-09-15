package com.contacts.callerid.number.lookup.feature.finder

import android.app.Activity
import com.contacts.callerid.number.lookup.monetize.delivery.RewardPrompt

/**
 * Puts a rewarded ad in front of revealing a caller name - the shared flow behind
 * both the Lookup card and the recent-lookup list.
 *
 * The dialog, the ads-off short circuit and the ad itself all live in
 * [RewardPrompt] now, so this screen's prompt is the same one the blocklist and
 * the "Also known as" reveal show. What is left here is the masking rule, which
 * is this feature's own.
 */
object NameRevealReward {

    /** First letter + dots (e.g. "John" → "J•••"). */
    fun blur(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    /**
     * Runs the reveal flow for [fullName], shown masked in the confirm dialog
     * beside [number]. [onRevealed] fires once the reward has been earned, or
     * immediately when ads are switched off.
     */
    fun reveal(activity: Activity, fullName: String, number: String, onRevealed: () -> Unit) {
        RewardPrompt.show(activity, RewardPrompt.nameReveal(fullName, number), onRevealed)
    }
}
