package com.callora.callerid.adkit.runtime

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import io.lighthouse.push.extended.LightHouseRichPush
import com.callora.callerid.adkit.contract.AdSlotType
import com.callora.callerid.adkit.policy.AdEarningsTracker
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.policy.logKeyEvent
import com.callora.callerid.adkit.runtime.interstitial.BackInterstitial
import com.callora.callerid.adkit.runtime.interstitial.StandardInterstitial
import com.callora.callerid.numberlookup.BuildConfig

object OpenAdManager {
    private const val LOG_TAG = "OpenAdManager"
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    var isShowingAd: Boolean = false
    var callbackshow:Boolean = false
    var isOpenAppDismiss: Boolean = false

    // When true, the next background→foreground transition skips the App Open
    // ad exactly once. Set before app-initiated trips to system settings (e.g.
    // the overlay-permission flow) so that programmatic return isn't monetised.
    var skipNextAppOpenAd: Boolean = false

    val isAdAvailable: Boolean
        get() = appOpenAd != null

    fun loadAd(context: Context?) {
        if (isLoadingAd || isAdAvailable) {
            return
        }
        if (context == null) {
            return
        }
        val adsPreference = AdsPrefStore.getInstance(context)
        // Firebase "AppopenAds" master switch — disable app-open loading entirely
        if (!adsPreference.getBoolean("AppopenAds")) {
            Log.e(LOG_TAG, "AppopenAds disabled by Firebase flag")
            return
        }
        if (AdSlotType.fromString(adsPreference.getString("IsAdType")) == AdSlotType.GOOGLE) {
            isLoadingAd = true
            val request = AdRequest.Builder().build()


            if (adsPreference.getString("IsAdType").equals("Google", true)) {
                AdsPrefStore.getInstance(context).getString("googleAppopen")?.let { adUnitId ->
                    AppOpenAd.load(
                        context, adUnitId, request, object : AppOpenAdLoadCallback() {
                            override fun onAdLoaded(ad: AppOpenAd) {

                                try {
                                    context.logKeyEvent("appopen_ad_loaded")
                                } catch (e: Exception) {
                                }

                                Log.d(LOG_TAG, "Ad was loaded.")
                                appOpenAd = ad
                                isLoadingAd = false
                            }

                            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                                Log.e(
                                    LOG_TAG, "Ad failed to load: ${loadAdError.message}"
                                )

                                try {
                                    context.logKeyEvent("appopen_ad_fail")
                                } catch (e: Exception) {
                                }
                                isLoadingAd = false
                            }
                        })
                }
            }

        } else {
            return
        }
    }

    fun presentAdIfReady(
        activity: Activity, onShowAdCompleteListener: OnShowAdCompleteListener
    ) {
        if (!AdsPrefStore.getInstance(activity).getBoolean("IsAdsON")) {
            return
        }
        // Firebase "AppopenAds" master switch — skip showing app-open ads entirely
        if (!AdsPrefStore.getInstance(activity).getBoolean("AppopenAds")) {
            Log.e(LOG_TAG, "AppopenAds disabled by Firebase flag")
            onShowAdCompleteListener.onShowAdComplete()
            return
        }
        if (callbackshow) {
            Log.e(
                LOG_TAG, "callbackshow is Show"
            )
            return
        }
        // Never cover a LightHouse rich-push overlay with an App Open ad.
        if (LightHouseRichPush.shouldDeferOverlay(activity)) {
            Log.e(LOG_TAG, "Deferring App Open — rich-push overlay active")
            return
        }
        if (StandardInterstitial.Companion.isInterShow) {
            Log.e(
                LOG_TAG, "Inter is Show"
            )
            return
        }
        if (BackInterstitial.Companion.isInterBAckShow) {
            Log.e(
                LOG_TAG, "Inter Back is Show"
            )
            return
        }
        if (isShowingAd) {
            Log.e(
                LOG_TAG, "The app open ad is already showing."
            )
            return
        }
        if (!isAdAvailable) {
            Log.e(
                LOG_TAG, "The app open ad is not ready yet."
            )
            onShowAdCompleteListener.onShowAdComplete()
            loadAd(activity)
            return
        }

        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.e(
                    LOG_TAG, "Ad dismissed fullscreen content."
                )

                try {
                    activity.logKeyEvent("appopen_ad_dismissed")
                } catch (_: Exception) {
                }

                appOpenAd = null
                isShowingAd = false
                isOpenAppDismiss = true
                onShowAdCompleteListener.onShowAdComplete()
                loadAd(
                    activity
                )
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(
                    LOG_TAG, adError.message
                )
                try {
                    activity.logKeyEvent("appopen_ad_fail")
                } catch (_: Exception) {
                }
                appOpenAd = null
                isShowingAd = false
                onShowAdCompleteListener.onShowAdComplete()
                loadAd(
                    activity
                )
            }

            override fun onAdShowedFullScreenContent() {
                Log.e(
                    LOG_TAG, "Ad showed fullscreen content."
                )
            }
        }

        // Log load
        activity.logKeyEvent("appopen_ad_shown")

        if (BuildConfig.DEBUG) AdEarningsTracker.emitDebugRevenue(activity)

        appOpenAd!!.setOnPaidEventListener {
            AdEarningsTracker.trackPaidEvent(activity, it)
        }

        isShowingAd = true

        if (AdsPrefStore.getInstance(activity).getString("IsAdType").equals("Google", true)) {
            appOpenAd?.show(activity)
        }
    }

    interface OnShowAdCompleteListener {
        fun onShowAdComplete()
    }

}
