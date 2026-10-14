package com.callerid.numberlookup.home.monetize.delivery

import com.callerid.numberlookup.home.monetize.strategy.AdAnalytics
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.callerid.numberlookup.home.monetize.model.AdPlacementType
import com.callerid.numberlookup.home.monetize.strategy.DisplayCadenceManager.nativeBannerCounter
import com.callerid.numberlookup.home.monetize.strategy.RevenueMonitor
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.TAG_EVENT
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.databinding.AudienceNativeBannerBinding
import com.callerid.numberlookup.home.databinding.AdmobSmallNativeBinding
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdOptionsView
import com.facebook.ads.NativeAdListener
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

class NativeBannerPresenter {
    companion object {
        private var nativeAdBanner: NativeAd? = null

        /** True while a load is in flight, so a second one is not started. */
        private var isLoading = false
        private var loadStartedAt = 0L

        /** A load with no callback by now is treated as lost, so it cannot block loads forever. */
        private const val LOAD_STALE_MS = 30_000L

        private val loadInFlight: Boolean
            get() = isLoading && System.currentTimeMillis() - loadStartedAt < LOAD_STALE_MS

        /**
         * Slots that asked to show a native banner before one had landed. Each runs
         * at most once: when an ad loads (one waiter per ad, oldest first), when the
         * load fails or cannot start (all of them, to fall back), or on
         * [BANNER_WAIT_MS]. Same mechanism as NativeAdPresenter's shared pool.
         */
        private val waiters = ArrayList<Pair<Activity, () -> Unit>>()

        private val mainHandler = Handler(Looper.getMainLooper())

        /** How long a slot keeps its shimmer up waiting for the ad before falling back. */
        private const val BANNER_WAIT_MS = 8_000L

        /**
         * The screen each pooled ad was shown on, so its impression and click -
         * wired at load time - report where the user saw it, not who loaded it.
         */
        private val shownOn = java.util.WeakHashMap<NativeAd, String>()

        /** Hands the fresh ad to the oldest waiter whose screen is still up. */
        private fun releaseOneWaiter() {
            while (waiters.isNotEmpty()) {
                val (activity, show) = waiters.removeAt(0)
                if (activity.isFinishing || activity.isDestroyed) continue
                show()
                return
            }
        }

        /** Load failed or cannot start: every waiter falls back now rather than at the timeout. */
        private fun releaseAllWaiters() {
            val pending = ArrayList(waiters)
            waiters.clear()
            pending.forEach { it.second.invoke() }
        }
    }

    /**
     * Runs [show] once a native banner is ready, or once it is clear it will not
     * be - [show] itself still checks `nativeAdBanner` and falls back when null.
     *
     * displayNativeBanner used to look at the cache once and fall back at once,
     * and only a *successful* show started the next load. So a slot that arrived
     * before the splash preload landed stayed empty - and if that preload failed,
     * or never ran (returning users skip the splash), nothing ever loaded one
     * again that session. Now the slot waits, starting a load if none is running.
     */
    private fun whenBannerReady(context: Activity, show: () -> Unit) {
        if (nativeAdBanner != null) {
            show()
            return
        }
        val entry = context to show
        waiters += entry
        mainHandler.postDelayed({
            if (waiters.remove(entry)) show()
        }, BANNER_WAIT_MS)
        if (!loadInFlight) loadNativeBannerAds(context)
    }

    fun loadNativeBannerAds(activity: Activity) {
        val adsPref = AdPreferenceStore.getInstance(activity)
        if (!adsPref.getBoolean("IsAdsON")) return releaseAllWaiters()
        // Firebase "NativeBannerPresenter" master switch — disable native banner loading
        if (!adsPref.getBoolean("NativeBannerPresenter")) return releaseAllWaiters()


        when (AdPlacementType.fromString(adsPref.getString("IsAdType"))) {
            AdPlacementType.GOOGLE -> {
                val adUnitId = adsPref.getString("googleNative")
                if (adUnitId.isNullOrEmpty()) return releaseAllWaiters()
                // One load at a time; a second would only replace the first unseen.
                if (loadInFlight) return
                isLoading = true
                loadStartedAt = System.currentTimeMillis()
                AdAnalytics.req(activity, AdAnalytics.NATIVE_BANNER)
                var loadedAd: NativeAd? = null
                fun displayScreen() = loadedAd?.let { shownOn[it] } ?: AdAnalytics.screenOf(activity)

                val adLoader = AdLoader.Builder(activity, adUnitId).forNativeAd { ad ->
                    loadedAd = ad
                    AdAnalytics.loaded(activity, AdAnalytics.NATIVE_BANNER)
                    nativeAdBanner?.destroy()
                    nativeAdBanner = ad
                    isLoading = false
                    releaseOneWaiter()
                    try {
                        activity.recordEvent("NativeBanner_Load")
                    } catch (e: Exception) {
                    }

                    Log.d("NativeBannerPresenter", "Ad loaded successfully")
                }.withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e("NativeBannerPresenter", "Ad failed to load: ${error.message}")
                        AdAnalytics.loadFailed(activity, AdAnalytics.NATIVE_BANNER, error.code)
                        nativeAdBanner = null
                        isLoading = false
                        // No retry here; the next slot that asks starts a fresh load.
                        releaseAllWaiters()

                        try {
                            activity.recordEvent("NativeBanner_fail")
                        } catch (e: Exception) {
                        }
                    }

                    override fun onAdImpression() {
                        AdAnalytics.impression(activity, AdAnalytics.NATIVE_BANNER, displayScreen())
                    }

                    override fun onAdClicked() {
                        AdAnalytics.clicked(activity, AdAnalytics.NATIVE_BANNER, displayScreen())
                    }
                }).withNativeAdOptions(NativeAdOptions.Builder().build()).build()

                adLoader.loadAd(AdRequest.Builder().build())
            }

            AdPlacementType.FACEBOOK -> {
                // FB ad type → directly try FB
                Log.e(TAG_EVENT, "AdPlacementType FaceBook NOt Pre load Google Native")
                return releaseAllWaiters()
            }

            AdPlacementType.UNKNOWN, AdPlacementType.CUSTOM -> {
                // Custom ad type
                Log.e(TAG_EVENT, "AdPlacementType Custom NOt Pre load Google Native")
                return releaseAllWaiters()
            }
        }


    }

    fun displayNativeBanner(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        Log.e("NativeAds", "Google Show: nativeAd")
        val adsPref = AdPreferenceStore.getInstance(context)

        // 🔥 CRASH FIX 1: Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return


        if (!isNetworkAvailable(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeBannerPresenter")
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        if (nativeBannerCounter < adsPref.getInt("MidNativeCounter")) {
            nativeBannerCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        nativeBannerCounter = 0

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true

        when (AdPlacementType.fromString(adsPref.getString("IsAdType"))) {
            AdPlacementType.GOOGLE -> {

                layout.post { whenBannerReady(context) {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@whenBannerReady
                        if (nativeAdBanner != null) {
                            val binding = AdmobSmallNativeBinding.inflate(context.layoutInflater)
                            bindGoogleNativeAd(nativeAdBanner!!, binding, context)

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            layout.addView(binding.root)
                            nativeAdBanner = null
                            loadNativeBannerAds(context)
                            return@whenBannerReady
                        } else {
                            // Google failed → FB fallback or Custom
                            if (adsPref.getBoolean("IsFail_FB")) {
                                showFBNativeBannerFallback(context, layout)
                            } else {
                                layout.removeAllViews()
                                shimmer?.stopShimmer()
                                shimmer?.isVisible = false
                                PromoAdManager().loadPromoAd(
                                    context, layout, PromoAdManager.CustomAdType.BANNER
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NativeBannerPresenter", "Google NativeBannerPresenter failed: ${e.message}")
                    }
                } }
            }

            AdPlacementType.FACEBOOK -> {
                showFBNativeBannerFallback(context, layout)
            }

            AdPlacementType.UNKNOWN, AdPlacementType.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                PromoAdManager().loadPromoAd(
                    context, layout, PromoAdManager.CustomAdType.BANNER
                )
            }
        }
    }

    /**
     * A native banner from a given ad unit, loaded on the spot and shown in
     * [layout] — for a placement that has its own unit (the uninstall funnel's
     * `language_ad`) rather than the shared, preloaded `googleNative` one.
     *
     * Same look and logging as [displayNativeBanner]. No MidNativeCounter: a
     * placement with its own unit is meant to show each time. The slot is hidden
     * when ads are off, offline, or the load fails.
     */
    fun displayNativeBannerWithId(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout?, adUnitId: String
    ) {
        if (context.isFinishing || context.isDestroyed) return
        val adsPref = AdPreferenceStore.getInstance(context)
        val hide = {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
        }
        if (!isNetworkAvailable(context) || !adsPref.getBoolean("IsAdsON")) return hide()

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true

        AdAnalytics.req(context, AdAnalytics.NATIVE_BANNER)
        AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                AdAnalytics.loaded(context, AdAnalytics.NATIVE_BANNER)
                if (context.isFinishing || context.isDestroyed) {
                    ad.destroy()
                    return@forNativeAd
                }
                val binding = AdmobSmallNativeBinding.inflate(context.layoutInflater)
                bindGoogleNativeAd(ad, binding, context)
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                layout.addView(binding.root)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e("NativeBannerPresenter", "Native ($adUnitId) failed: ${error.message}")
                    AdAnalytics.loadFailed(context, AdAnalytics.NATIVE_BANNER, error.code)
                    if (!context.isFinishing && !context.isDestroyed) hide()
                }

                override fun onAdImpression() {
                    AdAnalytics.impression(context, AdAnalytics.NATIVE_BANNER)
                }

                override fun onAdClicked() {
                    AdAnalytics.clicked(context, AdAnalytics.NATIVE_BANNER)
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    private fun bindGoogleNativeAd(
        nativeAd: NativeAd, binding: AdmobSmallNativeBinding, context: Activity
    ) {
        // Log load
        context.recordEvent("NativeBanner_Show_Google")
        // Both the pooled and the own-unit path bind here: the moment it is shown.
        val screen = AdAnalytics.screenOf(context)
        shownOn[nativeAd] = screen
        AdAnalytics.shown(context, AdAnalytics.NATIVE_BANNER, screen)

        if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)

        nativeAd.setOnPaidEventListener {
            RevenueMonitor.reportPaidEvent(context, it)
        }

        binding.apply {
            mainNativeadView.headlineView = adHeadline
            mainNativeadView.bodyView = adBody
            mainNativeadView.callToActionView = adCallToAction
            mainNativeadView.iconView = adAppIcon

            (adHeadline as TextView).text = nativeAd.headline

            val bgColor = AdPreferenceStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdPreferenceStore.getInstance(context).getString("NativebtnColor")


            val txtColor =
                AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"

            (binding.mainNativeadView.headlineView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            (binding.mainNativeadView.bodyView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            val btntxtColor =
                AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#000000"

            (adCallToAction as TextView).apply {
                setTextColor(Color.parseColor(btntxtColor))
            }

            mainNativeadView.backgroundTintList =
                ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))

            adCallToAction.backgroundTintList =
                ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))


            if (nativeAd.body != null) {
                adBody.visibility = View.VISIBLE
                (adBody as TextView).text = nativeAd.body
            } else {
                adBody.visibility = View.GONE
            }

            if (nativeAd.icon != null) {
                adAppIcon.visibility = View.VISIBLE
                (adAppIcon as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            } else {
                adAppIcon.visibility = View.GONE
            }

            if (nativeAd.callToAction != null) {
                adCallToAction.visibility = View.VISIBLE
                (adCallToAction as TextView).text = nativeAd.callToAction

            } else {
                adCallToAction.visibility = View.GONE
            }

            mainNativeadView.setNativeAd(nativeAd)
        }
    }

    fun colorOrDefault(colorString: String?, defaultColor: String): Int {
        return try {
            if (!colorString.isNullOrBlank()) {
                Color.parseColor(colorString)
            } else {
                Color.parseColor(defaultColor)
            }
        } catch (e: IllegalArgumentException) {
            Color.parseColor(defaultColor)
        }
    }


    private fun showFBNativeBannerFallback(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeBannerAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            PromoAdManager().loadPromoAd(
                context, layout, PromoAdManager.CustomAdType.BANNER
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        val fb = AdAnalytics.FACEBOOK
        AdAnalytics.req(context, AdAnalytics.NATIVE_BANNER, network = fb)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    layout.removeAllViews()
                    bindMetaNativeBanner(fbNative, layout, context)
                    context.recordEvent("NativeBAnner_FB")
                    AdAnalytics.shown(context, AdAnalytics.NATIVE_BANNER, network = fb)
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    AdAnalytics.loadFailed(context, AdAnalytics.NATIVE_BANNER, adError?.errorCode ?: -1, network = fb)
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                    PromoAdManager().loadPromoAd(
                        context, layout, PromoAdManager.CustomAdType.BANNER
                    )
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    AdAnalytics.loaded(context, AdAnalytics.NATIVE_BANNER, network = fb)
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {
                    AdAnalytics.clicked(context, AdAnalytics.NATIVE_BANNER, network = fb)
                }
                override fun onLoggingImpression(ad: Ad?) {
                    AdAnalytics.impression(context, AdAnalytics.NATIVE_BANNER, network = fb)
                }
            }).build()
        )
    }

    fun bindMetaNativeBanner(
        nativeAd: com.facebook.ads.NativeAd,
        viewGroup: ViewGroup,
        activity: Activity,
        adSize: String? = null
    ) {
        // ✅ Make sure container is visible
        viewGroup.isVisible = true

        // Unregister any old ad view
        nativeAd.unregisterView()

        // ✅ Inflate layout with ViewBinding
        val binding =
            AudienceNativeBannerBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)

        // ✅ Bind ad data to views
        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        val bgColor = AdPreferenceStore.getInstance(activity).getString("NativeBgColor")
        val btnColor = AdPreferenceStore.getInstance(activity).getString("NativebtnColor")
        val txtColor = AdPreferenceStore.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            AdPreferenceStore.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        // ✅ Register clickable views
        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeIconView, clickableViews
        )
    }

}
