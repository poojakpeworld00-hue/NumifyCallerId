package com.numify.callerid.monetize.delivery

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.fullpage.TransitionInterstitialAd

/**
 * Preload-then-show handling for Google AdMob rewarded ads. When the rewarded ad
 * cannot load or cannot show, it falls back to openDirectLink.
 *
 * Usage:
 *   RewardedAdPresenter.preload(activity)          // early on, in setupViews or onResume
 *   RewardedAdPresenter().show(activity) { ... }   // on the item click
 */
class RewardedAdPresenter {

    companion object {
        private var loadedAd: RewardedAd? = null
        private var isLoading = false

        /** Call this early (e.g. setupViews / onResume) to warm up the ad. */
        fun preload(context: Context) {
            val pref = AdPreferenceStore.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (loadedAd != null || isLoading) return

            val unitId = pref.getString("googleRewarded")
            if (unitId.isNullOrEmpty()) return

            isLoading = true
            Log.d("RewardedAdPresenter", "Preloading…")

            RewardedAd.load(
                context,
                unitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadedAd = ad
                        isLoading = false
                        Log.d("RewardedAdPresenter", "Preloaded OK")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadedAd = null
                        isLoading = false
                        Log.e("RewardedAdPresenter", "Preload failed: ${error.message}")
                    }
                }
            )
        }

        /** Show DirectLink fallback, then invoke [onClosed] when it's done. */
        private fun showDirectLinkFallback(activity: Activity, onClosed: () -> Unit) {
            val pref = AdPreferenceStore.getInstance(activity)
            if (pref.getBoolean("IsCustomADS")) {
                Log.d("RewardedAdPresenter", "Falling back to DirectLink")
                TransitionInterstitialAd.openDirectLink(activity) { onClosed() }
            } else {
                onClosed()
            }
        }
    }

    /**
     * Shows the preloaded rewarded ad.
     * - Ads off: [onRewarded] runs straight away.
     * - Nothing preloaded: DirectLink fallback, then [onRewarded].
     * - Ad shown: [onRewarded] runs only once the reward has been earned.
     * - Ad fails to show: DirectLink fallback, then [onRewarded].
     */
    fun show(activity: Activity, onRewarded: () -> Unit) {
        val pref = AdPreferenceStore.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            onRewarded()
            return
        }

        val ad = loadedAd
        if (ad == null) {
            Log.d("RewardedAdPresenter", "No preloaded ad — showing DirectLink fallback")
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
                Log.e("RewardedAdPresenter", "Show failed: ${error.message} — showing DirectLink fallback")
                preload(activity)
                showDirectLinkFallback(activity, onRewarded)
            }
        }

        ad.show(activity) {
            Log.d("RewardedAdPresenter", "Reward earned: ${it.type} x${it.amount}")
        }
    }
}
