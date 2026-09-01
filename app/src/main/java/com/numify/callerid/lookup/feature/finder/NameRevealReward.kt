package com.numify.callerid.lookup.feature.finder

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.RewardedAdPresenter
import com.numify.callerid.lookup.databinding.DialogWatchAdBinding

/**
 * Puts a rewarded ad in front of revealing a caller name - the shared flow behind
 * both the Lookup card and the recent-lookup list. With ads off it reveals
 * straight away; with ads on it runs a "watch ad" confirmation, the rewarded ad,
 * and then the reveal.
 */
object NameRevealReward {

    /** First letter + dots (e.g. "John" → "J•••"). */
    fun blur(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    /**
     * Runs the reveal flow for [fullName], shown blurred in the confirm dialog
     * beside [number]. [onRevealed] fires once the reward has been earned, or
     * immediately when ads are switched off.
     */
    fun reveal(activity: Activity, fullName: String, number: String, onRevealed: () -> Unit) {
        // Ads off → straight through, no ad, no dialog.
        if (!AdPreferenceStore.getInstance(activity).getBoolean("IsAdsON")) {
            onRevealed()
            return
        }

        val db = DialogWatchAdBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.textPreviewName.text = blur(fullName)
        db.textPreviewNumber.text = number
        db.buttonWatchAd.setOnClickListener {
            dialog.dismiss()
            RewardedAdPresenter().show(activity) { onRevealed() }
        }
        db.buttonCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
