package com.numify.callerid.monetize.delivery

import android.R.attr.visibility
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.numify.callerid.monetize.model.AdPlacementType
import com.numify.callerid.monetize.model.AdPlacementType.*
import com.numify.callerid.monetize.strategy.DisplayCadenceManager.nativeCounter
import com.numify.callerid.monetize.strategy.RevenueMonitor
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.ScreenPlacementPlan
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.databinding.AudienceMidNativeBinding
import com.numify.callerid.lookup.databinding.AudienceNativeBinding
import com.numify.callerid.lookup.databinding.AdmobBigNativeBinding
import com.numify.callerid.lookup.databinding.AdmobBigNativeTopBinding
import com.numify.callerid.lookup.databinding.AdmobMidNativeTwoBinding
import com.numify.callerid.lookup.databinding.AdmobMidNativeBinding
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
            return
        }
        // Firebase "NativeBannerPresenter" master switch — disable native ad loading
        if (!adsPreference.getBoolean("NativeAd")) {
            observer?.onNativeAdFailed()
            return
        }
        // Screen-wise aware: resolves to ScreenAds.default native id, or the
        // global googleNative when screen_wise_ad is off.
        val adUnit = ScreenPlacementPlan.nativeAdUnitId(context)
        if (adUnit.isEmpty()) {
            return
        }
        val adLoader =
            AdLoader.Builder(context, adUnit)
                .forNativeAd { nativeAds ->
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

                    if (context.isActivityDestroyedCompat()) {
                        // Originating activity is gone — cache only, skip
                        // observer/log calls that need a live context.
                        return@forNativeAd
                    }

                    observer?.onNativeAdLoaded()
                    try {
                        context.logKeyEvent("NativeAds_load")
                    } catch (e: Exception) {
                    }


                    Log.e("NativeAds", "Google Load: nativeAd")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        super.onAdFailedToLoad(loadAdError)
                        if (context.isActivityDestroyedCompat()) return
                        observer?.onNativeAdFailed()
                        Log.e(
                            "NativeAds",
                            "Google onAdFailedToLoad:nativeAd ${loadAdError.message}"
                        )
                        try {
                            context.logKeyEvent("NativeAds_Fail")
                        } catch (e: Exception) {
                        }
                        nativeAd = null
                    }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()

        adLoader.loadAd(AdRequest.Builder().build())
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
                layout.post {  // 🔥 UI thread safe
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post

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

                            context.logKeyEvent("NativeAds_showBigNative_Google")

                            if (BuildConfig.DEBUG) {
                                RevenueMonitor.logDebugRevenue(context)
                            }

                            nativeAd?.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context)

                            return@post
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
                }
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
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
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
                    context.logKeyEvent("NativeAds_showBigNative_FB_Load")
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
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
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
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
                            context.logKeyEvent("NativeAds_showMidNative_Google")

                            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context) // preload next Google ad
                            return@post
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
                }


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
                        context.logKeyEvent("NativeAds_showMid_FB")
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
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
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
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
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
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
                            context.logKeyEvent("NativeAds_showMidNative2_Google")

                            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                RevenueMonitor.reportPaidEvent(context, it)
                            }

                            nativeAd = null
                            loadNativeAds(context) // preload next Google ad
                            return@post
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
                }


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
