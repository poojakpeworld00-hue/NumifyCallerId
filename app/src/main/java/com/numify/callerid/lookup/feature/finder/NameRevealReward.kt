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
 * Gates revealing a caller name behind a rewarded ad — the shared flow used by the
 * Lookup card and the recent-lookup list. Ads off → reveals immediately; ads on →
 * "watch ad" confirm dialog → rewarded ad → reveal.
 */
object NameRevealReward {

    /** First letter + dots (e.g. "John" → "J•••"). */
    fun blur(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    /**
     * Runs the reveal flow for [fullName] (shown blurred in the confirm dialog next
     * to [number]); [onRevealed] fires once the reward is earned (or immediately
     * when ads are off).
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
        db.lblPreviewName.text = blur(fullName)
        db.lblPreviewNumber.text = number
        db.padWatchAd.setOnClickListener {
            dialog.dismiss()
            RewardedAdPresenter().show(activity) { onRevealed() }
        }
        db.padCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
