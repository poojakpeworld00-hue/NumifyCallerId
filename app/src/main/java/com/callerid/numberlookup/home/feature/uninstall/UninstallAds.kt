package com.callerid.numberlookup.home.feature.uninstall

import android.app.Activity
import android.util.Log
import android.widget.FrameLayout
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.monetize.delivery.BannerAdPresenter
import com.callerid.numberlookup.home.monetize.delivery.NativeAdPresenter
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.monetize.delivery.isNetworkAvailable
import com.callerid.numberlookup.home.monetize.model.AdPlacementType
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.RevenueMonitor
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * The uninstall funnel's own ads, from `uninstall_flow` in Remote Config (see
 * [UninstallFlow.languageAd] and [UninstallFlow.interAd]): the language page's
 * ad, and an interstitial on leaving each listed page.
 *
 * The interstitial has its own unit and its own single slot, loaded while the
 * user reads a page so that leaving it never waits on the network. It is not
 * throttled by InterCounter - a page listed in `on` shows one whenever one is
 * ready - and when none is ready the user simply moves on and the next is
 * loaded. Ads off (including Premium, through IsAdsON) means none of this runs.
 */
object UninstallAds {

    private const val TAG = "UninstallAds"

    private var inter: InterstitialAd? = null
    private var loadingId: String? = null

    private fun adsOn(activity: Activity): Boolean {
        val pref = AdPreferenceStore.getInstance(activity)
        return pref.getBoolean("IsAdsON") &&
            AdPlacementType.fromString(pref.getString("IsAdType")) == AdPlacementType.GOOGLE
    }

    // ── Interstitial ────────────────────────────────────────────────────────

    /**
     * Starts loading the funnel interstitial, if one is configured and none is
     * held. Without a usable `inter_ad.id` it loads the app-wide `googleInter` unit.
     */
    fun preloadInter(activity: Activity) {
        val cfg = UninstallFlow.interAd() ?: return
        val id = cfg.id
            ?: AdPreferenceStore.getInstance(activity).getString("googleInter")?.takeIf { it.isNotBlank() }
            ?: return
        if (!cfg.show || !adsOn(activity) || !isNetworkAvailable(activity)) return
        if (inter != null || loadingId == id) return
        loadingId = id
        InterstitialAd.load(activity.applicationContext, id, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    inter = ad
                    loadingId = null
                    if (BuildConfig.DEBUG) Log.d(TAG, "inter loaded ($id)")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    loadingId = null
                    Log.w(TAG, "inter failed to load ($id): ${error.message}")
                }
            })
    }

    /**
     * Shows the funnel interstitial on leaving [page], then runs [next] once it
     * is closed - or straight away when [page] is not listed, ads are off, or no
     * ad is ready.
     *
     * Without an `inter_ad` block the language page keeps its old behaviour (the
     * app-wide interstitial, InterCounter and all) and no other page shows one.
     */
    fun showInterThen(activity: Activity, page: String, next: () -> Unit) {
        val cfg = UninstallFlow.interAd()
        if (cfg == null) {
            if (page == UninstallFlow.PAGE_LANGUAGE) {
                TransitionInterstitialAd().showInterstitial(activity) { next() }
            } else {
                next()
            }
            return
        }
        if (!cfg.show || page !in cfg.pages || !adsOn(activity)) return next()

        val ad = inter
        if (ad == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "no inter ready on leaving '$page' — moving on")
            preloadInter(activity)
            return next()
        }
        inter = null

        var done = false
        val finish = {
            if (!done) {
                done = true
                TransitionInterstitialAd.isInterShow = false
                next()
                preloadInter(activity)
            }
        }
        ad.setOnPaidEventListener { RevenueMonitor.reportPaidEvent(activity, it) }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                // Keeps the app-open ad from firing when this one closes.
                TransitionInterstitialAd.isInterShow = true
            }

            override fun onAdDismissedFullScreenContent() = finish()

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "inter failed to show on '$page': ${error.message}")
                finish()
            }
        }
        activity.recordEvent("uninstall_inter_show_$page")
        runCatching { ad.show(activity) }.onFailure { finish() }
    }

    // ── Language page ad ────────────────────────────────────────────────────

    /**
     * The funnel's language-page ad. With a `language_ad` block it uses that
     * block's unit and format; without one, the page's
     * `ScreenAds.UninstallLanguageActivity` entry as before.
     */
    fun showLanguageAd(
        activity: Activity, screenKey: String, container: FrameLayout, shimmer: ShimmerFrameLayout?
    ) {
        val cfg = UninstallFlow.languageAd()
            ?: return ScreenPlacementPlan.showAd(screenKey, activity, container, shimmer)
        if (!cfg.show || !adsOn(activity)) {
            container.removeAllViews()
            container.minimumHeight = 0
            container.visibility = android.view.View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = android.view.View.GONE
            return
        }
        val id = cfg.id ?: return ScreenPlacementPlan.showAd(screenKey, activity, container, shimmer)
        if (cfg.type == "banner") {
            BannerAdPresenter().displayBanner(
                activity = activity,
                container = container,
                shimmer = shimmer,
                customAdUnitId = id,
            )
        } else {
            // big, big_top, mid, mid2 or small - the app's native layouts.
            NativeAdPresenter().displayNativeWithId(activity, container, shimmer, id, cfg.type ?: "mid")
        }
    }
}
