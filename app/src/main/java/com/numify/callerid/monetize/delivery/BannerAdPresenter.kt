package com.numify.callerid.monetize.delivery
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
import com.numify.callerid.monetize.model.AdPlacementType
import com.numify.callerid.monetize.strategy.RevenueMonitor
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.recordEvent
import com.numify.callerid.lookup.BuildConfig
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
class BannerAdPresenter {

    private var googleBanner: AdView? = null
    private var facebookBanner: FbAdView? = null

    companion object {
        var bannerCounter = 0
    }

    // -----------------------------
    // SHOW BANNER ENTRY POINT
    // -----------------------------
    fun displayBanner(
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
        val pref = AdPreferenceStore.getInstance(activity)

        // Ads OFF
        if (!isNetworkAvailable(activity)|| !pref.getBoolean("IsAdsON") || !pref.getBoolean("BannerAdPresenter")) {
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
                when (AdPlacementType.fromString(pref.getString("IsAdType"))) {
                    AdPlacementType.GOOGLE -> loadGoogleBanner(
                        activity,
                        container,
                        size,
                        isCollapsable,
                        shimmer,
                        observer,
                        customAdUnitId,
                        disableInternalFallback
                    )

                    AdPlacementType.FACEBOOK -> loadFacebookBanner(
                        activity, container, shimmer, observer, disableInternalFallback
                    )

                    AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> {
                        if (disableInternalFallback) {
                            observer?.onAdFailed()
                        } else {
                            PromoAdManager().loadPromoAd(
                                activity,
                                container,
                                PromoAdManager.CustomAdType.BANNER
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
                    PromoAdManager().loadPromoAd(
                        activity,
                        container,
                        PromoAdManager.CustomAdType.BANNER
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
        val pref = AdPreferenceStore.getInstance(activity)
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
                    "BannerAdPresenter",
                    "Ad loaded. adView.isCollapsible() is ${googleBanner?.isCollapsible}.",
                )
                // Log load
                activity.recordEvent("Banner_Load")

                if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)

                googleBanner!!.setOnPaidEventListener {
                    RevenueMonitor.reportPaidEvent(activity, it)
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
                    activity.recordEvent("Banner_fail_Load")
                } catch (_: Exception) {
                }
                Log.e("BannerAdPresenter", "Google Banner Failed: ${error.message}")
                observer?.onAdFailed()
                if (!disableInternalFallback) {
                    fallbackToFBOrCustom(activity, container, shimmer, observer)
                }
            }

            override fun onAdClicked() {
                activity.recordEvent("google_banner")
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
        val pref = AdPreferenceStore.getInstance(activity)
        val fbId = pref.getString("faceB_BannerAds")

        if (fbId.isNullOrEmpty()) {
            if (disableInternalFallback) {
                observer?.onAdFailed()
            } else {
                PromoAdManager().loadPromoAd(
                    activity,
                    container,
                    PromoAdManager.CustomAdType.BANNER
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
                        activity.recordEvent("facebook_banner_load")
                    }

                    override fun onError(
                        ad: Ad?,
                        error: AdError?
                    ) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        observer?.onAdFailed()
                        Log.e("BannerAdPresenter", "FB Banner Failed: ${error?.errorMessage}")
                        if (!disableInternalFallback) {
                            PromoAdManager().loadPromoAd(
                                activity,
                                container,
                                PromoAdManager.CustomAdType.BANNER
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
        val pref = AdPreferenceStore.getInstance(activity)
        if (pref.getBoolean("IsFail_FB")) {
            // Pass shimmer to Facebook banner loader
            loadFacebookBanner(activity, container, shimmer, observer)
        } else {
            // Optionally, you can show shimmer for custom ads if PromoAdManager supports it
            shimmer?.startShimmer()
            shimmer?.visibility = View.VISIBLE
            PromoAdManager().loadPromoAd(
                activity,
                container,
                PromoAdManager.CustomAdType.BANNER
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

