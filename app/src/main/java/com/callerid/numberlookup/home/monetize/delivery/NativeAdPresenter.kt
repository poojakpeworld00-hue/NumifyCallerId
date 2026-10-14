package com.callerid.numberlookup.home.monetize.delivery

import com.callerid.numberlookup.home.monetize.strategy.AdAnalytics
import android.R.attr.visibility
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.callerid.numberlookup.home.monetize.model.AdPlacementType
import com.callerid.numberlookup.home.monetize.model.AdPlacementType.*
import com.callerid.numberlookup.home.monetize.strategy.DisplayCadenceManager.nativeCounter
import com.callerid.numberlookup.home.monetize.strategy.RevenueMonitor
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.databinding.AudienceMidNativeBinding
import com.callerid.numberlookup.home.databinding.AudienceNativeBinding
import com.callerid.numberlookup.home.databinding.AdmobBigNativeBinding
import com.callerid.numberlookup.home.databinding.AdmobBigNativeTopBinding
import com.callerid.numberlookup.home.databinding.AdmobMidNativeTwoBinding
import com.callerid.numberlookup.home.databinding.AdmobMidNativeBinding
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
import kotlin.collections.plusAssign
import kotlin.text.compareTo

class NativeAdPresenter() {
    interface NativeAdObserver {
        fun onNativeAdLoaded()
        fun onNativeAdFailed()
    }

    companion object {
        private var nativeAd: NativeAd? = null

        /** True while a shared-pool load is in flight, so a second one is not started. */
        private var isLoading = false
        private var loadStartedAt = 0L

        /** A load with no callback by now is treated as lost, so it cannot block loads forever. */
        private const val LOAD_STALE_MS = 30_000L

        private val loadInFlight: Boolean
            get() = isLoading && System.currentTimeMillis() - loadStartedAt < LOAD_STALE_MS

        /**
         * Screens that asked to show a native before the shared one had landed.
         * Each runs at most once: when an ad loads (one waiter per ad, in order),
         * when the load fails (all of them, to fall back), or on [NATIVE_WAIT_MS].
         */
        private val waiters = ArrayList<Pair<Activity, () -> Unit>>()

        private val mainHandler = Handler(Looper.getMainLooper())

        /** How long a slot keeps its shimmer up waiting for the ad before falling back. */
        private const val NATIVE_WAIT_MS = 8_000L

        /**
         * The screen each pooled ad was shown on. A pooled ad is loaded by one
         * screen and shown by another; its impression and click callbacks are
         * wired at load time, so they look it up here to report the screen the
         * user actually saw it on.
         */
        private val shownOn = java.util.WeakHashMap<NativeAd, String>()

        /** Records that [ad] went on screen in [activity]: `ad_native_shown`. */
        private fun markShown(activity: Activity, ad: NativeAd?) {
            ad ?: return
            val screen = AdAnalytics.screenOf(activity)
            shownOn[ad] = screen
            AdAnalytics.shown(activity, AdAnalytics.NATIVE, screen)
        }

        /**
         * Hands the fresh ad to the oldest waiter whose screen is still up; the
         * rest keep waiting for the refill. A screen that closed while waiting is
         * dropped, so it cannot swallow the ad meant for the next one.
         */
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
     * Runs [show] once the shared native is ready, or once it is clear it will not
     * be - [show] itself still checks `nativeAd` and falls back when it is null.
     *
     * The display functions used to look at the cache once and fall back at once.
     * Splash only *starts* the load and moves on, so the first screen after it -
     * Language, on a fresh install - nearly always got there first, found nothing,
     * showed the (usually empty) fallback, and the ad that landed a second later
     * was only parked for some later screen. Now the slot keeps its shimmer and
     * waits for that load, starting one if none is running.
     */
    private fun whenNativeReady(context: Activity, show: () -> Unit) {
        if (nativeAd != null) {
            show()
            return
        }
        val entry = context to show
        waiters += entry
        mainHandler.postDelayed({
            if (waiters.remove(entry)) show()
        }, NATIVE_WAIT_MS)
        if (!loadInFlight) loadNativeAds(context)
    }

    private fun Activity.isActivityDestroyedCompat(): Boolean {
        if (isFinishing) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed
    }

    fun loadNativeAds(context: Activity, observer: NativeAdObserver? = null) {
        val adsPreference = AdPreferenceStore.getInstance(context)
        // Ads toggle and type check
        if (!adsPreference.getBoolean("IsAdsON") || AdPlacementType.fromString(adsPreference.getString("IsAdType")) != AdPlacementType.GOOGLE) {
            observer?.onNativeAdFailed()
            releaseAllWaiters()
            return
        }
        // Firebase "NativeBannerPresenter" master switch — disable native ad loading
        if (!adsPreference.getBoolean("NativeAd")) {
            observer?.onNativeAdFailed()
            releaseAllWaiters()
            return
        }
        // Screen-wise aware: resolves to ScreenAds.default native id, or the
        // global googleNative when screen_wise_ad is off.
        val adUnit = ScreenPlacementPlan.nativeAdUnitId(context)
        if (adUnit.isEmpty()) {
            releaseAllWaiters()
            return
        }
        // One shared load at a time. Every show already starts the refill, so a
        // second request here would only replace the first ad before anyone saw it.
        if (loadInFlight && observer == null) return
        isLoading = true
        loadStartedAt = System.currentTimeMillis()
        AdAnalytics.req(context, AdAnalytics.NATIVE)
        // The ad this load produced, for its impression/click callbacks below.
        var loadedAd: NativeAd? = null
        fun displayScreen() = loadedAd?.let { shownOn[it] } ?: AdAnalytics.screenOf(context)
        val adLoader =
            AdLoader.Builder(context, adUnit)
                .forNativeAd { nativeAds ->
                    loadedAd = nativeAds
                    AdAnalytics.loaded(context, AdAnalytics.NATIVE)
                    // Always cache the ad — even when the activity that
                    // triggered this load is destroyed by the time the ad
                    // lands. The cache is a static field; it survives
                    // activity transitions and can be shown by the next
                    // caller. Previously this branch destroyed the ad,
                    // which is why "1 load → then nothing" was the symptom:
                    // the splash refill landed after splash was gone, got
                    // destroyed, and every subsequent show found null.
                    nativeAd?.destroy()
                    nativeAd = nativeAds
                    isLoading = false
                    // A screen may be waiting with its shimmer up — even when the
                    // activity that started this load is long gone.
                    releaseOneWaiter()

                    if (context.isActivityDestroyedCompat()) {
                        // Originating activity is gone — cache only, skip
                        // observer/log calls that need a live context.
                        return@forNativeAd
                    }

                    observer?.onNativeAdLoaded()
                    try {
                        context.recordEvent("NativeAds_load")
                    } catch (e: Exception) {
                    }


                    Log.e("NativeAds", "Google Load: nativeAd")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        super.onAdFailedToLoad(loadAdError)
                        isLoading = false
                        AdAnalytics.loadFailed(context, AdAnalytics.NATIVE, loadAdError.code)
                        releaseAllWaiters()
                        if (context.isActivityDestroyedCompat()) return
                        observer?.onNativeAdFailed()
                        Log.e(
                            "NativeAds",
                            "Google onAdFailedToLoad:nativeAd ${loadAdError.message}"
                        )
                        try {
                            context.recordEvent("NativeAds_Fail")
                        } catch (e: Exception) {
                        }
                        nativeAd = null
                    }

                    // Not overridden before, so no counted impression or click was
                    // ever logged for a Google native.
                    override fun onAdImpression() {
                        AdAnalytics.impression(context, AdAnalytics.NATIVE, displayScreen())
                    }

                    override fun onAdClicked() {
                        AdAnalytics.clicked(context, AdAnalytics.NATIVE, displayScreen())
                    }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }

    //================================================================================Own unit
    /**
     * Loads a native ad from [adUnitId] - not the shared pool - and shows it in
     * the [size] layout: `big`, `big_top` (button on top), `mid`, `mid2` or
     * `small`. For slots with their own ad unit, such as the uninstall funnel's.
     *
     * No NativeCounter and no Meta/promo fallback: when the unit has nothing,
     * the slot is hidden.
     */
    fun displayNativeWithId(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout?,
        adUnitId: String,
        size: String,
    ) {
        if (context.isActivityDestroyedCompat()) return
        // Onboarding / ScreenAds spellings map onto the same layouts.
        val key = when (size.trim().lowercase()) {
            "big", "bignative", "big_native" -> "big"
            "big_top", "bignativetop" -> "big_top"
            "mid2", "mediumnativealt" -> "mid2"
            "small", "nativebanner", "native_banner" -> "small"
            else -> "mid"
        }
        if (key == "small") {
            NativeBannerPresenter().displayNativeBannerWithId(context, layout, shimmer, adUnitId)
            return
        }
        val adsPreference = AdPreferenceStore.getInstance(context)
        val hide = {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
        }
        if (!isNetworkAvailable(context)
            || !adsPreference.getBoolean("IsAdsON")
            || !adsPreference.getBoolean("NativeAd")
        ) return hide()

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true

        AdAnalytics.req(context, AdAnalytics.NATIVE)
        AdLoader.Builder(context, adUnitId)
            .forNativeAd { ad ->
                AdAnalytics.loaded(context, AdAnalytics.NATIVE)
                if (context.isActivityDestroyedCompat()) {
                    ad.destroy()
                    return@forNativeAd
                }
                val root: View = when (key) {
                    "big" -> AdmobBigNativeBinding.inflate(context.layoutInflater)
                        .also { bigNativeTemplate(ad, it, context) }.root
                    "big_top" -> AdmobBigNativeTopBinding.inflate(context.layoutInflater)
                        .also { bigNativeTemplateTop(ad, it, context) }.root
                    "mid2" -> AdmobMidNativeTwoBinding.inflate(context.layoutInflater)
                        .also { MidNativeTemplate2(ad, it, context) }.root
                    else -> AdmobMidNativeBinding.inflate(context.layoutInflater)
                        .also { MidNativeTemplate(ad, it, context) }.root
                }
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                layout.addView(root)
                ad.setOnPaidEventListener { RevenueMonitor.reportPaidEvent(context, it) }
                if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)
                context.recordEvent("NativeAds_show_${key}_own_unit")
                AdAnalytics.shown(context, AdAnalytics.NATIVE)
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.e("NativeAds", "Native $key ($adUnitId) failed: ${error.message}")
                    AdAnalytics.loadFailed(context, AdAnalytics.NATIVE, error.code)
                    if (!context.isActivityDestroyedCompat()) hide()
                }

                override fun onAdImpression() {
                    AdAnalytics.impression(context, AdAnalytics.NATIVE)
                }

                override fun onAdClicked() {
                    AdAnalytics.clicked(context, AdAnalytics.NATIVE)
                }
            })
            .withNativeAdOptions(NativeAdOptions.Builder().build())
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    //================================================================================BigNAtive
    fun displayLargeNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        isButtonTop: Boolean? = false,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {

        val adsPreference = AdPreferenceStore.getInstance(context)

        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // 🔥 2. Network + Ads ON + NativeBannerPresenter master switch
        if (!isNetworkAvailable(context)
            || !adsPreference.getBoolean("IsAdsON")
            || !adsPreference.getBoolean("NativeAd")
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        // 🔥 3. Counter logic
        if (nativeCounter < adsPreference.getInt("NativeCounter")) {
            nativeCounter++
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0


        // 🔥 4. UI loading state
        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()

        when (AdPlacementType.fromString(adsPreference.getString("IsAdType"))) {
            AdPlacementType.GOOGLE -> {
                layout.post { whenNativeReady(context) {  // 🔥 UI thread safe
                    try {
                        if (context.isFinishing || context.isDestroyed) return@whenNativeReady

                        if (nativeAd != null) {

                            val rootView: View = if (isButtonTop == true) {
                                val binding =
                                    AdmobBigNativeTopBinding.inflate(context.layoutInflater)

                                bigNativeTemplateTop(nativeAd!!, binding, context)
                                binding.root
                            } else {
                                val binding =
                                    AdmobBigNativeBinding.inflate(context.layoutInflater)

                                bigNativeTemplate(nativeAd!!, binding, context)
                                binding.root
                            }

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(rootView)

                            context.recordEvent("NativeAds_showBigNative_Google")
                            markShown(context, nativeAd)

                            if (BuildConfig.DEBUG) {
                                RevenueMonitor.logDebugRevenue(context)
                            }

                            nativeAd?.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context)

                            return@whenNativeReady
                        }

                        // 🔥 GOOGLE FAIL → fallback
                        if (adsPreference.getBoolean("IsFail_FB")) {
                            showMetaNativeFallback(context, layout, imageView, shimmer)
                        } else {
                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            PromoAdManager().loadPromoAd(
                                context,
                                layout,
                                PromoAdManager.CustomAdType.BIG_NATIVE,
                                imageView
                            )
                        }

                    } catch (e: Exception) {
                        Log.e("NativeAds", "Google BigNative crash", e)
                    }
                } }
            }

            AdPlacementType.FACEBOOK -> {
                showMetaNativeFallback(context, layout, imageView)
            }

            AdPlacementType.UNKNOWN, AdPlacementType.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                PromoAdManager().loadPromoAd(
                    context,
                    layout,
                    PromoAdManager.CustomAdType.BIG_NATIVE,
                    imageView
                )
            }
        }
    }

    private fun bigNativeTemplate(
        nativeAd: NativeAd,
        binding: AdmobBigNativeBinding,
        context: Activity
    ) {
        binding.apply {

            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            val bgColor = AdPreferenceStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdPreferenceStore.getInstance(context).getString("NativebtnColor")
            val txtColor = AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"
            val btntxtColor = AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#FFFFFF"

            binding.mainNativeadView.backgroundTintList = ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))
            binding.mainNativeadView.callToActionView?.backgroundTintList = ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))

            (binding.mainNativeadView.headlineView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (binding.mainNativeadView.bodyView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (adCallToAction as TextView).setTextColor(colorOrDefault(btntxtColor, "#FFFFFF"))

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }


            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    private fun bigNativeTemplateTop(
        nativeAd: NativeAd,
        binding: AdmobBigNativeTopBinding,
        context: Activity
    ) {
        binding.apply {

            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            val bgColor = AdPreferenceStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdPreferenceStore.getInstance(context).getString("NativebtnColor")
            val txtColor = AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"
            val btntxtColor = AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#FFFFFF"

            binding.mainNativeadView.backgroundTintList = ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))
            binding.mainNativeadView.callToActionView?.backgroundTintList = ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))

            (binding.mainNativeadView.headlineView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (binding.mainNativeadView.bodyView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (adCallToAction as TextView).setTextColor(colorOrDefault(btntxtColor, "#FFFFFF"))

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }


            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }


    // Helper function for FB fallback
    private fun showMetaNativeFallback(
        context: Activity,
        layout: FrameLayout,
        imageView: ImageView? = null,
        shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            PromoAdManager().loadPromoAd(
                context,
                layout,
                PromoAdManager.CustomAdType.BIG_NATIVE,
                imageView
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        val fb = AdAnalytics.FACEBOOK
        AdAnalytics.req(context, AdAnalytics.NATIVE, network = fb)

        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        bindMetaNative(fbNative, layout, context)
                        AdAnalytics.shown(context, AdAnalytics.NATIVE, network = fb)
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    AdAnalytics.loadFailed(context, AdAnalytics.NATIVE, adError?.errorCode ?: -1, network = fb)
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        layout.findFocus()?.clearFocus()
                        PromoAdManager().loadPromoAd(
                            context,
                            layout,
                            PromoAdManager.CustomAdType.BIG_NATIVE,
                            imageView
                        )
                    }
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    AdAnalytics.loaded(context, AdAnalytics.NATIVE, network = fb)
                    context.recordEvent("NativeAds_showBigNative_FB_Load")
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {
                    AdAnalytics.clicked(context, AdAnalytics.NATIVE, network = fb)
                }
                override fun onLoggingImpression(ad: Ad?) {
                    AdAnalytics.impression(context, AdAnalytics.NATIVE, network = fb)
                }
            }).build()
        )
    }

    fun bindMetaNative(
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
        val binding = AudienceNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)
        val bgColor = AdPreferenceStore.getInstance(activity).getString("NativeBgColor")
        val btnColor = AdPreferenceStore.getInstance(activity).getString("NativebtnColor")
        val txtColor =
            AdPreferenceStore.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            AdPreferenceStore.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdBody.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }
        // ✅ Bind ad data to views
        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdBody.text = nativeAd.adBodyText
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        // ✅ Register clickable views
        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeAdMedia, binding.nativeAdIcon, clickableViews
        )
    }

    //================================================================================BigNAtive
//================================================================================MidNAtive
    fun displayMediumNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // Check network & ad toggle + NativeBannerPresenter master switch
        if (!isNetworkAvailable(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeAd")
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        // MidNative counter logic
        if (nativeCounter < adsPref.getInt("MidNativeCounter")) {
            nativeCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0

        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()
        layout.visible()


        when (AdPlacementType.fromString(adsPref.getString("IsAdType"))) {
            AdPlacementType.GOOGLE -> {
                layout.post { whenNativeReady(context) {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@whenNativeReady
                        if (nativeAd != null) {
                            val binding = AdmobMidNativeBinding.inflate(context.layoutInflater)
                            MidNativeTemplate(
                                nativeAd!!,
                                binding,
                                context
                            )

                            layout.removeAllViews()
                            // Stop shimmer before adding real ad
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            // Log load
                            context.recordEvent("NativeAds_showMidNative_Google")
                            markShown(context, nativeAd)

                            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context) // preload next Google ad
                            return@whenNativeReady
                        }
                        // Google failed → fallback
                        if (adsPref.getBoolean("IsFail_FB")) {
                            showMidFBNativeFallback(context, layout, shimmer, imageView)
                        } else {
                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            PromoAdManager().loadPromoAd(
                                context,
                                layout,
                                PromoAdManager.CustomAdType.MID_NATIVE
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("MidNativeAds", "Google MidNative failed: ${e.message}")
                    }
                } }


            }

            AdPlacementType.FACEBOOK -> {

                showMidFBNativeFallback(context, layout, shimmer, imageView)
            }

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                PromoAdManager().loadPromoAd(
                    context,
                    layout,
                    PromoAdManager.CustomAdType.MID_NATIVE
                )
            }
        }
    }

    private fun showMidFBNativeFallback(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeAds")

        if (fbId.isNullOrEmpty()) {
            layout.removeAllViews()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            PromoAdManager().loadPromoAd(
                context,
                layout,
                PromoAdManager.CustomAdType.MID_NATIVE
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        val fb = AdAnalytics.FACEBOOK
        AdAnalytics.req(context, AdAnalytics.NATIVE, network = fb)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        bindMetaMediumNative(fbNative, layout, context)
                        context.recordEvent("NativeAds_showMid_FB")
                        AdAnalytics.shown(context, AdAnalytics.NATIVE, network = fb)
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    AdAnalytics.loadFailed(context, AdAnalytics.NATIVE, adError?.errorCode ?: -1, network = fb)
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        PromoAdManager().loadPromoAd(
                            context,
                            layout,
                            PromoAdManager.CustomAdType.MID_NATIVE
                        )
                    }
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    AdAnalytics.loaded(context, AdAnalytics.NATIVE, network = fb)
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {
                    AdAnalytics.clicked(context, AdAnalytics.NATIVE, network = fb)
                }
                override fun onLoggingImpression(ad: Ad?) {
                    AdAnalytics.impression(context, AdAnalytics.NATIVE, network = fb)
                }
            }).build()
        )
    }

    fun bindMetaMediumNative(
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
        val binding = AudienceMidNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)

        val bgColor = AdPreferenceStore.getInstance(activity).getString("NativeBgColor")
        val btnColor = AdPreferenceStore.getInstance(activity).getString("NativebtnColor")
        val txtColor =
            AdPreferenceStore.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            AdPreferenceStore.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdBody.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        // ✅ Bind ad data to views
        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdBody.text = nativeAd.adBodyText
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        // ✅ Register clickable views
        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeAdIcon, clickableViews
        )
    }

    private fun MidNativeTemplate(
        nativeAd: NativeAd,
        binding: AdmobMidNativeBinding,
        context: Activity
    ) {
        binding.apply {
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline

            val bgColor = AdPreferenceStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdPreferenceStore.getInstance(context).getString("NativebtnColor")
            val txtColor = AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"
            val btntxtColor = AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#FFFFFF"

            binding.mainNativeadView.backgroundTintList = ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))
            binding.mainNativeadView.callToActionView?.backgroundTintList = ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))

            (binding.mainNativeadView.headlineView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (binding.mainNativeadView.bodyView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (adCallToAction as TextView).setTextColor(colorOrDefault(btntxtColor, "#FFFFFF"))

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }

            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    // Extension helpers
    fun View.visible() {
        this.visibility = View.VISIBLE
    }

    fun View.invisible() {
        this.visibility = View.GONE
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


    fun displayMediumNativeAlt(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // Check network & ad toggle
        if (!isNetworkAvailable(context) || !adsPref.getBoolean("IsAdsON") || !adsPref.getBoolean("NativeAd")) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        // MidNative counter logic
        if (nativeCounter < adsPref.getInt("MidNativeCounter")) {
            nativeCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0

        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()
        layout.visible()
        when (AdPlacementType.fromString(adsPref.getString("IsAdType"))) {
            GOOGLE -> {
                layout.post { whenNativeReady(context) {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@whenNativeReady
                        if (Companion.nativeAd != null) {
                            val binding = AdmobMidNativeTwoBinding.inflate(context.layoutInflater)
                            MidNativeTemplate2(
                                Companion.nativeAd!!,
                                binding,
                                context
                            )

                            layout.removeAllViews()
                            // Stop shimmer before adding real ad
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            // Log load
                            context.recordEvent("NativeAds_showMidNative2_Google")
                            markShown(context, Companion.nativeAd)

                            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context) // preload next Google ad
                            return@whenNativeReady
                        }
                        // Google failed → fallback
                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        PromoAdManager().loadPromoAd(
                            context,
                            layout,
                            PromoAdManager.CustomAdType.MID_NATIVE
                        )
                    } catch (e: Exception) {
                        Log.e("MidNativeAds", "Google MidNative failed: ${e.message}")
                    }
                } }


            }

            CUSTOM, UNKNOWN -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                PromoAdManager().loadPromoAd(
                    context,
                    layout,
                    PromoAdManager.CustomAdType.MID_NATIVE
                )
            }

            AdPlacementType.FACEBOOK -> {
                showMidFBNativeFallback(context, layout, shimmer, imageView)
            }
        }
    }

    private fun MidNativeTemplate2(
        nativeAd: NativeAd,
        binding: AdmobMidNativeTwoBinding,
        context: Activity
    ) {
        binding.apply {

            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            val bgColor = AdPreferenceStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdPreferenceStore.getInstance(context).getString("NativebtnColor")
            val txtColor = AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"
            val btntxtColor = AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#FFFFFF"

            binding.mainNativeadView.backgroundTintList = ColorStateList.valueOf(colorOrDefault(bgColor, "#FFFFFF"))
            binding.mainNativeadView.callToActionView?.backgroundTintList = ColorStateList.valueOf(colorOrDefault(btnColor, "#000000"))

            (binding.mainNativeadView.headlineView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (binding.mainNativeadView.bodyView as TextView).setTextColor(colorOrDefault(txtColor, "#000000"))
            (adCallToAction as TextView).setTextColor(colorOrDefault(btntxtColor, "#FFFFFF"))

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }


            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }


}
