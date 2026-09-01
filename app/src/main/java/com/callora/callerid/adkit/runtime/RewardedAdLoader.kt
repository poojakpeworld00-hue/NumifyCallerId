package com.callora.callerid.adkit.runtime

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.runtime.interstitial.StandardInterstitial

/**
 * Preload-and-show pattern for Google AdMob Rewarded ads.
 * Falls back to launchDirectLink when the rewarded ad fails to load or show.
 *
 * Usage:
 *   RewardedAdLoader.preload(activity)          // call early (setupViews / onResume)
 *   RewardedAdLoader().show(activity) { ... }   // call on item click
 */
class RewardedAdLoader {

    companion object {
        private var loadedAd: RewardedAd? = null
        private var isLoading = false

        /** Call this early (e.g. setupViews / onResume) to warm up the ad. */
        fun preload(context: Context) {
            val pref = AdsPrefStore.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (loadedAd != null || isLoading) return

            val unitId = pref.getString("googleRewarded")
            if (unitId.isNullOrEmpty()) return

            isLoading = true
            Log.d("RewardedAdLoader", "Preloading…")

            RewardedAd.load(
                context,
                unitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadedAd = ad
                        isLoading = false
                        Log.d("RewardedAdLoader", "Preloaded OK")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadedAd = null
                        isLoading = false
                        Log.e("RewardedAdLoader", "Preload failed: ${error.message}")
                    }
                }
            )
        }

        /** Show DirectLink fallback, then invoke [onClosed] when it's done. */
        private fun showDirectLinkFallback(activity: Activity, onClosed: () -> Unit) {
            val pref = AdsPrefStore.getInstance(activity)
            if (pref.getBoolean("IsCustomADS")) {
                Log.d("RewardedAdLoader", "Falling back to DirectLink")
                StandardInterstitial.launchDirectLink(activity) { onClosed() }
            } else {
                onClosed()
            }
        }
    }

    /**
     * Show the preloaded rewarded ad.
     * - Ads OFF → [onRewarded] called immediately.
     * - No preloaded ad → DirectLink fallback, then [onRewarded].
     * - Ad shown → [onRewarded] fires only after reward is earned.
     * - Ad fails to show → DirectLink fallback, then [onRewarded].
     */
    fun show(activity: Activity, onRewarded: () -> Unit) {
        val pref = AdsPrefStore.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            onRewarded()
            return
        }

        val ad = loadedAd
        if (ad == null) {
            Log.d("RewardedAdLoader", "No preloaded ad — showing DirectLink fallback")
            preload(activity)
            showDirectLinkFallback(activity, onRewarded)
            return
        }

        // Consume the held reference so we don't show it twice
        loadedAd = null

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                preload(activity)
                onRewarded()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.e("RewardedAdLoader", "Show failed: ${error.message} — showing DirectLink fallback")
                preload(activity)
                showDirectLinkFallback(activity, onRewarded)
            }
        }

        ad.show(activity) {
            Log.d("RewardedAdLoader", "Reward earned: ${it.type} x${it.amount}")
        }
    }
}
