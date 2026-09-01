package com.callora.callerid.adkit.runtime
import android.app.Activity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.callora.callerid.adkit.contract.AdSlotType
import com.callora.callerid.adkit.policy.AdEarningsTracker
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.policy.logKeyEvent
import com.callora.callerid.numberlookup.BuildConfig
import com.facebook.ads.AdView as FbAdView

// --------------------------------------------------------------
// ENUMS
// --------------------------------------------------------------
enum class BannerDimension { ADAPTIVE, INLINE, NORMAL }
enum class BannerVariant { AUTO, GOOGLE, FACEBOOK, CUSTOM }

// --------------------------------------------------------------
// OBSERVER
// --------------------------------------------------------------
interface BannerLifecycleObserver {
    fun onAdLoaded() {}
    fun onAdFailed() {}
}

// --------------------------------------------------------------
// BANNER ADS MANAGER
// --------------------------------------------------------------
class BannerAdLoader {

    private var googleBanner: AdView? = null
    private var facebookBanner: FbAdView? = null

    companion object {
        var bannerCounter = 0
    }

    // -----------------------------
    // SHOW BANNER ENTRY POINT
    // -----------------------------
    fun renderBanner(
        activity: Activity,
        container: FrameLayout,
        type: BannerVariant = BannerVariant.AUTO,
        size: BannerDimension = BannerDimension.ADAPTIVE,
        isCollapsable: Boolean = false,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerLifecycleObserver? = null,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsPrefStore.getInstance(activity)

        // Ads OFF
        if (!hasNetworkAccess(activity)|| !pref.getBoolean("IsAdsON") || !pref.getBoolean("BannerAdLoader")) {
            hide(container)
            observer?.onAdFailed()
            return
        }

        // Banner counter logic
        if (bannerCounter < pref.getInt("BannerCounter")) {
            bannerCounter++
            hide(container)
            observer?.onAdFailed()
            return
        }
        bannerCounter = 0

        when (type) {
            BannerVariant.AUTO -> {
                when (AdSlotType.fromString(pref.getString("IsAdType"))) {
                    AdSlotType.GOOGLE -> loadGoogleBanner(
                        activity,
                        container,
                        size,
                        isCollapsable,
                        shimmer,
                        observer,
                        customAdUnitId,
                        disableInternalFallback
                    )

                    AdSlotType.FACEBOOK -> loadFacebookBanner(
                        activity, container, shimmer, observer, disableInternalFallback
                    )

                    AdSlotType.CUSTOM, AdSlotType.UNKNOWN -> {
                        if (disableInternalFallback) {
                            observer?.onAdFailed()
                        } else {
                            HouseAdsManager().fetchHouseAd(
                                activity,
                                container,
                                HouseAdsManager.CustomAdType.BANNER
                            )
                        }
                    }
                }
            }

            BannerVariant.GOOGLE -> loadGoogleBanner(
                activity,
                container,
                size,
                isCollapsable,
                shimmer,
                observer,
                customAdUnitId,
                disableInternalFallback
            )

            BannerVariant.FACEBOOK -> loadFacebookBanner(
                activity, container, shimmer, observer, disableInternalFallback
            )

            BannerVariant.CUSTOM -> {
                if (disableInternalFallback) {
                    observer?.onAdFailed()
                } else {
                    HouseAdsManager().fetchHouseAd(
                        activity,
                        container,
                        HouseAdsManager.CustomAdType.BANNER
                    )
                }
            }
        }
    }

    // -----------------------------
    // GOOGLE BANNER
    // -----------------------------
    private fun loadGoogleBanner(
        activity: Activity,
        container: FrameLayout,
        size: BannerDimension,
        isCollapsable: Boolean,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerLifecycleObserver?,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsPrefStore.getInstance(activity)
        val adUnitId = customAdUnitId ?: pref.getString("googleBanner")

        if (adUnitId.isNullOrEmpty()) {
            if (disableInternalFallback) observer?.onAdFailed()
            else fallbackToFBOrCustom(activity, container, shimmer, observer)
            return
        }

        hide(container)
        // Show shimmer while loading
        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        container.removeAllViews()
        shimmer?.let { container.addView(it) }
        container.visibility = View.VISIBLE

        // Preload banner
        if (googleBanner == null) googleBanner = AdView(activity)
        googleBanner?.adUnitId = adUnitId

        if (isCollapsable) {
            googleBanner?.setAdSize(getAdSize(activity, container))
        } else {
            googleBanner?.setAdSize(getGoogleSize(activity, container, size, isCollapsable))

        }

        googleBanner?.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.i(
                    "BannerAdLoader",
                    "Ad loaded. adView.isCollapsible() is ${googleBanner?.isCollapsible}.",
                )
                // Log load
                activity.logKeyEvent("Banner_Load")

                if (BuildConfig.DEBUG) AdEarningsTracker.emitDebugRevenue(activity)

                googleBanner!!.setOnPaidEventListener {
                    AdEarningsTracker.trackPaidEvent(activity, it)
                }


                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                container.removeAllViews()

                container.addView(googleBanner)
                container.visibility = View.VISIBLE
                observer?.onAdLoaded()

            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                try {
                    activity.logKeyEvent("Banner_fail_Load")
                } catch (_: Exception) {
                }
                Log.e("BannerAdLoader", "Google Banner Failed: ${error.message}")
                observer?.onAdFailed()
                if (!disableInternalFallback) {
                    fallbackToFBOrCustom(activity, container, shimmer, observer)
                }
            }

            override fun onAdClicked() {
                activity.logKeyEvent("google_banner")
            }
        }

        val request = if (isCollapsable) {
            val extras = Bundle()
            extras.putString("collapsible", "bottom")
            AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()
        } else AdRequest.Builder().build()

        googleBanner?.loadAd(request)
    }

    private fun getAdSize(
        activity: Activity,
        adContainer: FrameLayout
    ): com.google.android.gms.ads.AdSize {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)

        val density = display.density
        val widthPixels =
            if (adContainer.width == 0) display.widthPixels.toFloat()
            else adContainer.width.toFloat()

        val adWidth = (widthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    private fun getGoogleSize(
        activity: Activity,
        container: FrameLayout,
        size: BannerDimension,
        isCollapsable: Boolean
    ): AdSize {
        return when (size) {
            BannerDimension.NORMAL -> AdSize.BANNER
            BannerDimension.INLINE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )

            BannerDimension.ADAPTIVE -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )
        }
    }

    private fun getWidthDp(activity: Activity): Int {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)
        return (display.widthPixels / display.density).toInt()
    }

    // -----------------------------
    // FACEBOOK BANNER
    // -----------------------------
    private fun loadFacebookBanner(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerLifecycleObserver?,
        disableInternalFallback: Boolean = false
    ) {
        val pref = AdsPrefStore.getInstance(activity)
        val fbId = pref.getString("faceB_BannerAds")

        if (fbId.isNullOrEmpty()) {
            if (disableInternalFallback) {
                observer?.onAdFailed()
            } else {
                HouseAdsManager().fetchHouseAd(
                    activity,
                    container,
                    HouseAdsManager.CustomAdType.BANNER
                )
            }
            return
        }

        hide(container)
        // Show shimmer while loading
        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        container.removeAllViews()
        shimmer?.let { container.addView(it) }

        if (facebookBanner == null) facebookBanner =
            FbAdView(activity, fbId, com.facebook.ads.AdSize.BANNER_HEIGHT_50)

        facebookBanner?.loadAd(
            facebookBanner!!.buildLoadAdConfig()
                .withAdListener(object : com.facebook.ads.AdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        container.removeAllViews()

                        container.addView(facebookBanner)
                        container.visibility = View.VISIBLE
                        observer?.onAdLoaded()
                        activity.logKeyEvent("facebook_banner_load")
                    }

                    override fun onError(
                        ad: Ad?,
                        error: AdError?
                    ) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        observer?.onAdFailed()
                        Log.e("BannerAdLoader", "FB Banner Failed: ${error?.errorMessage}")
                        if (!disableInternalFallback) {
                            HouseAdsManager().fetchHouseAd(
                                activity,
                                container,
                                HouseAdsManager.CustomAdType.BANNER
                            )
                        }
                    }

                    override fun onAdClicked(ad: Ad?) {
                    }

                    override fun onLoggingImpression(ad: Ad?) {}
                })
                .build()
        )
    }

    // -----------------------------
    // FALLBACK
    // -----------------------------
    private fun fallbackToFBOrCustom(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: BannerLifecycleObserver?
    ) {
        val pref = AdsPrefStore.getInstance(activity)
        if (pref.getBoolean("IsFail_FB")) {
            // Pass shimmer to Facebook banner loader
            loadFacebookBanner(activity, container, shimmer, observer)
        } else {
            // Optionally, you can show shimmer for custom ads if HouseAdsManager supports it
            shimmer?.startShimmer()
            shimmer?.visibility = View.VISIBLE
            HouseAdsManager().fetchHouseAd(
                activity,
                container,
                HouseAdsManager.CustomAdType.BANNER
            )
        }
    }

    // -----------------------------
    // HIDE
    // -----------------------------
    private fun hide(container: FrameLayout) {
        container.removeAllViews()
        container.visibility = View.GONE
    }

}

