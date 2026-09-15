package com.contacts.callerid.number.lookup.monetize.delivery

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
import com.contacts.callerid.number.lookup.monetize.model.AdPlacementType
import com.contacts.callerid.number.lookup.monetize.strategy.RevenueMonitor
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore
import com.contacts.callerid.number.lookup.monetize.strategy.recordEvent
import com.contacts.callerid.number.lookup.monetize.delivery.fullpage.ExitInterstitialAd
import com.contacts.callerid.number.lookup.monetize.delivery.fullpage.TransitionInterstitialAd
import com.contacts.callerid.number.lookup.BuildConfig

object AppOpenAdManager {
    private const val LOG_TAG = "AppOpenAdManager"
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
        val adsPreference = AdPreferenceStore.getInstance(context)
        // Firebase "AppopenAds" master switch — disable app-open loading entirely
        if (!adsPreference.getBoolean("AppopenAds")) {
            Log.e(LOG_TAG, "AppopenAds disabled by Firebase flag")
            return
        }
        if (AdPlacementType.fromString(adsPreference.getString("IsAdType")) == AdPlacementType.GOOGLE) {
            isLoadingAd = true
            val request = AdRequest.Builder().build()


            if (adsPreference.getString("IsAdType").equals("Google", true)) {
                AdPreferenceStore.getInstance(context).getString("googleAppopen")?.let { adUnitId ->
                    AppOpenAd.load(
                        context, adUnitId, request, object : AppOpenAdLoadCallback() {
                            override fun onAdLoaded(ad: AppOpenAd) {

                                try {
                                    context.recordEvent("appopen_ad_loaded")
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
                                    context.recordEvent("appopen_ad_fail")
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

    fun showAdIfReady(
        activity: Activity, onShowAdCompleteListener: OnShowAdCompleteListener
    ) {
        if (!AdPreferenceStore.getInstance(activity).getBoolean("IsAdsON")) {
            return
        }
        // Firebase "AppopenAds" master switch — skip showing app-open ads entirely
        if (!AdPreferenceStore.getInstance(activity).getBoolean("AppopenAds")) {
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
        if (TransitionInterstitialAd.Companion.isInterShow) {
            Log.e(
                LOG_TAG, "Inter is Show"
            )
            return
        }
        if (ExitInterstitialAd.Companion.isInterBAckShow) {
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
                    activity.recordEvent("appopen_ad_dismissed")
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
                    activity.recordEvent("appopen_ad_fail")
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
        activity.recordEvent("appopen_ad_shown")

        if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)

        appOpenAd!!.setOnPaidEventListener {
            RevenueMonitor.reportPaidEvent(activity, it)
        }

        isShowingAd = true

        if (AdPreferenceStore.getInstance(activity).getString("IsAdType").equals("Google", true)) {
            appOpenAd?.show(activity)
        }
    }

    interface OnShowAdCompleteListener {
        fun onShowAdComplete()
    }

}
