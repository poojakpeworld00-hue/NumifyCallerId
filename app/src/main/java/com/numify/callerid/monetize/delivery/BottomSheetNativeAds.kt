package com.numify.callerid.monetize.delivery
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowMetrics
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdOptionsView
import com.facebook.ads.NativeAdListener
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.numify.callerid.monetize.model.AdPlacementType
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.logAdRevenue
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.lookup.databinding.AudienceNativeBinding
import com.numify.callerid.lookup.databinding.AdmobBigNativeBinding

class BottomSheetNativeAds {

    private fun Activity.isActivityDestroyedCompat(): Boolean {
        if (isFinishing) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed
    }

    //    =====================================================================================BAnner
    // ------------------------------------------------------------------------------------------
    // SHOW BANNER
    // ------------------------------------------------------------------------------------------
    fun renderBannerAd(
        activity: Activity,
        adContainer: FrameLayout,
        isCollapsible: Boolean = false
    ) {
        val adsPref = AdPreferenceStore.getInstance(activity)
        if (!adsPref.getBoolean("IsAdsON")) {
            removeAd(adContainer)
            return
        }

        val isFbFallback = adsPref.getBoolean("IsFail_FB")
        activity.setLast_Result_HD_VBC_Type()
        when (AdPlacementType.fromString(adsPref.getString("IsAdType"))) {

            AdPlacementType.GOOGLE -> {
                val BanneradUnitId = adsPref.getString("HD_VBC_Banner_ID").orEmpty()
                if (BanneradUnitId.isEmpty()) {
                    if (isFbFallback) showFbBanner(activity, adContainer)
                    else PromoAdManager().fetchHouseAd(
                        activity,
                        adContainer,
                        PromoAdManager.CustomAdType.BIG_NATIVE
                    )
                    return
                }

                loadGoogleBanner(activity, adContainer, BanneradUnitId, isCollapsible) { success ->
                    if (!success) {
                        if (isFbFallback) showFbBanner(activity, adContainer)
                        else PromoAdManager().fetchHouseAd(
                            activity,
                            adContainer,
                            PromoAdManager.CustomAdType.BIG_NATIVE
                        )
                    }
                }
            }

            AdPlacementType.FACEBOOK -> showFbBanner(activity, adContainer)

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> PromoAdManager().fetchHouseAd(
                activity,
                adContainer,
                PromoAdManager.CustomAdType.BIG_NATIVE
            )
        }
    }

    fun Context.adsWidth(): Int {
        val displayMetrics = resources.displayMetrics
        val adWidthPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val activity = this as? Activity // Safe cast
            val windowMetrics: WindowMetrics? = activity?.windowManager?.currentWindowMetrics
            windowMetrics?.bounds?.width() ?: displayMetrics.widthPixels // Fallback if null
        } else {
            displayMetrics.widthPixels
        }
        val density = displayMetrics.density
        Log.e("123456", "${(adWidthPixels / density).toInt()}")
        return (adWidthPixels / density).toInt()
    }

    // ------------------------------------------------------------------------------------------
    // GOOGLE BANNER
    // ------------------------------------------------------------------------------------------
    private fun loadGoogleBanner(
        activity: Activity,
        adContainer: FrameLayout,
        adUnitId: String,
        isCollapsible: Boolean = false,
        onResult: (success: Boolean) -> Unit
    ) {
        val bannerAdView = AdView(activity).apply {
            this.adUnitId = adUnitId
            setAdSize(
                AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(
                    context, context.adsWidth()
                )
            )
            adListener = object : AdListener() {

                override fun onAdLoaded() {
                    if (activity.isActivityDestroyedCompat()) return
                    adContainer.post {
                        if (activity.isActivityDestroyedCompat()) return@post

                        adContainer.removeAllViews()

                        try {
                            setOnPaidEventListener { adValue ->
                                val revenue = adValue.valueMicros / 1_000_000.0
                                val currency = adValue.currencyCode
                                context.logAdRevenue(revenue, currency)
                            }
                        } catch (_: Exception) {
                        }

                        try {
                            context.logKeyEvent("BS_ads_load")
                        } catch (_: Exception) {
                        }

                        adContainer.findFocus()?.clearFocus()
                        adContainer.addView(this@apply)
                        adContainer.visibility = View.VISIBLE
                        onResult(true)
                        Log.d("GoogleBanner", "Loaded")
                    }
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    if (activity.isActivityDestroyedCompat()) return
                    adContainer.post {
                        if (activity.isActivityDestroyedCompat()) return@post
                        adContainer.removeAllViews()
                        adContainer.visibility = View.GONE
                        Log.e("GoogleBanner", "Failed:${error.message}")
                        onResult(false)
                    }
                }

                override fun onAdClicked() {
                    try {
                        activity.logKeyEvent("BS_banner_clicked")
                    } catch (_: Exception) {
                    }
                }

                override fun onAdOpened() {
                    try {
                        activity.logKeyEvent("BS_banner_opened")
                    } catch (_: Exception) {
                    }
                }
            }
        }

        val adRequest = if (isCollapsible) {
            val extras = Bundle().apply { putString("collapsible", "bottom") }
            AdRequest.Builder()
                .addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
                .build()
        } else {
            AdRequest.Builder().build()
        }

        bannerAdView.loadAd(adRequest)
    }

    // ------------------------------------------------------------------------------------------
    // FACEBOOK BANNER
    // ------------------------------------------------------------------------------------------
    private fun showFbBanner(activity: Activity, layout: FrameLayout) {

        val fbId = AdPreferenceStore.getInstance(activity)
            .getString("faceB_BannerAds") ?: run {

            PromoAdManager().fetchHouseAd(
                activity,
                layout,
                PromoAdManager.CustomAdType.BIG_NATIVE
            )
            return
        }

        val fbBanner =
            com.facebook.ads.AdView(activity, fbId, com.facebook.ads.AdSize.BANNER_HEIGHT_50)

        fbBanner.loadAd(
            fbBanner.buildLoadAdConfig()
                .withAdListener(object : com.facebook.ads.AdListener {

                    override fun onError(ad: Ad?, err: AdError?) {
                        Log.e("FBBanner", "Fail:${err?.errorMessage}")
                        PromoAdManager().fetchHouseAd(
                            activity,
                            layout,
                            PromoAdManager.CustomAdType.BIG_NATIVE
                        )
                    }

                    override fun onAdLoaded(ad: Ad?) {
                        if (activity.isActivityDestroyedCompat()) return
                        layout.post {
                            if (activity.isActivityDestroyedCompat()) return@post
                            layout.findFocus()?.clearFocus()
                            layout.removeAllViews()
                            layout.addView(fbBanner)
                            layout.visibility = View.VISIBLE
                        }
                    }

                    override fun onAdClicked(ad: Ad?) {}
                    override fun onLoggingImpression(ad: Ad?) {}
                }).build()
        )
    }

    private fun removeAd(adContainer: FrameLayout) {
        adContainer.removeAllViews()
        adContainer.visibility = View.GONE
    }


    //    =====================================================================================BAnner
    companion object {
        private var BCnativeAd: NativeAd? = null
    }

    fun BS_loadNativeADs(context: Activity) {
        val adsPreference = AdPreferenceStore.getInstance(context)
        if (!adsPreference.getBoolean("IsAdsON")) {
            return
        }

        if (!adsPreference.getBoolean("HD_VBC_Show")) {
            return
        }

        if (!adsPreference.getBoolean("HD_VBC_Native")) {
            return
        }

        val adLoader = adsPreference.getString("HD_VBC_Native_ID")?.let {
            AdLoader.Builder(context, it)
                .forNativeAd { nativeAds ->
                    if (context.isActivityDestroyedCompat()) {
                        nativeAds.destroy()
                        return@forNativeAd
                    }
                    BCnativeAd?.destroy()
                    BCnativeAd = nativeAds
                    try {
                        context.logKeyEvent("NativeAds_BS_load")
                    } catch (e: Exception) {
                    }
                    Log.e("NativeAds", "Google Load: nativeAd")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        super.onAdFailedToLoad(loadAdError)
                        Log.e(
                            "NativeAds",
                            "Google onAdFailedToLoad:nativeAd ${loadAdError.message}"
                        )
                        try {
                            context.logKeyEvent("NativeAds_BS_Fail")
                        } catch (e: Exception) {
                        }
                        BCnativeAd = null
                    }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()
        }

        adLoader?.loadAd(AdRequest.Builder().build())
    }

    fun BS_showBigNative(
        context: Activity,
        layout: FrameLayout,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {

        Log.e("NativeAds", "Google Show: nativeAd")
        val adsPreference = AdPreferenceStore.getInstance(context)

        // --- No Internet ---
        if (!hasNetworkAccess(context)) {
            Log.w("987654321", "Native No Internet")
            layout.removeAllViews()
            layout.invisible()
            imageView?.visible()
            ln?.visible()
            return
        }

        // --- Ads OFF ---
        if (!adsPreference.getBoolean("IsAdsON")) {
            Log.w("987654321", "Native Ads Off")
            layout.removeAllViews()
            layout.invisible()
            imageView?.visible()
            ln?.visible()
            return
        }

        // --- Prepare UI ---

        imageView?.gone()
        ln?.gone()
        layout.visible()

        // --- Based on AdPlacementType ---
        Log.w(
            "987654321",
            "Native Ads Type: ${AdPlacementType.fromString(adsPreference.getString("IsAdType"))}"
        )
        context.setLast_Result_HD_VBC_Type()
        when (AdPlacementType.fromString(adsPreference.getString("IsAdType"))) {

            AdPlacementType.GOOGLE -> {
                val adLoader = adsPreference.getString("HD_VBC_Native_ID")?.let {
                    AdLoader.Builder(context, it)
                        .forNativeAd { nativeAds ->
                            if (context.isActivityDestroyedCompat()) {
                                nativeAds.destroy()
                                return@forNativeAd
                            }
//                            BCnativeAd?.destroy()
//                            BCnativeAd = nativeAds
                            try {
                                val binding = AdmobBigNativeBinding.inflate(context.layoutInflater)
                                bigNativeTemplate(nativeAds, binding, context)
                                layout.post {
                                    if (context.isActivityDestroyedCompat()) return@post
                                    layout.findFocus()?.clearFocus()
                                    layout.removeAllViews()
                                    layout.addView(binding.root)
                                }

                                // Revenue callback
                                try {
                                    nativeAds.setOnPaidEventListener { adValue ->
                                        val revenue = adValue.valueMicros / 1_000_000.0
                                        context.logAdRevenue(revenue, adValue.currencyCode)
                                    }
                                } catch (_: Exception) {
                                }

                                context.logKeyEvent("NativeAds_BS_showBigNative_Google")

                            } catch (e: Exception) {
                                Log.e("987654321", "Google ad failed: ${e.message}")
//                                 Google failed → fallback
                                if (adsPreference.getBoolean("IsFail_FB")) {
                                    Log.w("987654321", "Native Ads Null")
                                    layout.post {
                                        if (context.isActivityDestroyedCompat()) return@post
                                        presentMetaNativeFallback(context, layout, imageView)
                                    }
                                } else {
                                    layout.post {
                                        if (context.isActivityDestroyedCompat()) return@post
                                        PromoAdManager().fetchHouseAd(
                                            context, layout,
                                            PromoAdManager.CustomAdType.BIG_NATIVE,
                                            imageView
                                        )
                                    }
                                }
                            }
                            try {
                                context.logKeyEvent("NativeAds_BS_load")
                            } catch (e: Exception) {
                            }
                            Log.e("987654321", "Google Load: nativeAd")


                        }
                        .withAdListener(object : AdListener() {
                            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                                super.onAdFailedToLoad(loadAdError)
                                if (context.isActivityDestroyedCompat()) return
                                Log.e(
                                    "987654321",
                                    "Google onAdFailedToLoad:nativeAd ${loadAdError.message}"
                                )
                                try {
                                    context.logKeyEvent("NativeAds_BS_Fail")
                                    // Google failed → fallback
                                    if (adsPreference.getBoolean("IsFail_FB")) {
                                        Log.w("987654321", "Native Ads Null")
                                        layout.post {
                                            if (context.isActivityDestroyedCompat()) return@post
                                            presentMetaNativeFallback(context, layout, imageView)
                                        }
                                    } else {
                                        layout.post {
                                            if (context.isActivityDestroyedCompat()) return@post
                                            PromoAdManager().fetchHouseAd(
                                                context, layout,
                                                PromoAdManager.CustomAdType.BIG_NATIVE,
                                                imageView
                                            )
                                        }
                                    }
                                } catch (e: Exception) {
                                }
                            }
                        })
                        .withNativeAdOptions(NativeAdOptions.Builder().build())
                        .build()
                }

                adLoader?.loadAd(AdRequest.Builder().build())
            }

            AdPlacementType.FACEBOOK -> {
                presentMetaNativeFallback(context, layout, imageView)
            }

            AdPlacementType.UNKNOWN,
            AdPlacementType.CUSTOM -> {
                PromoAdManager().fetchHouseAd(
                    context, layout,
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
            binding.mainNativeadView.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(
                    AdPreferenceStore.getInstance(context).getString("NativeBgColor")
                )
            )
            binding.mainNativeadView.callToActionView?.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(
                    AdPreferenceStore.getInstance(context).getString("NativebtnColor")
                )
            )
            val txtColor =
                AdPreferenceStore.getInstance(context).getString("NativetxtColor") ?: "#000000"
            val btntxtColor =
                AdPreferenceStore.getInstance(context).getString("NativebtntxtColor") ?: "#000000"

            (binding.mainNativeadView.headlineView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            (binding.mainNativeadView.bodyView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            (adCallToAction as TextView).apply {
                setTextColor(Color.parseColor(btntxtColor))
            }
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
    fun presentMetaNativeFallback(
        context: Activity,
        layout: FrameLayout,
        imageView: ImageView? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeAds")

        if (fbId.isNullOrEmpty()) {
            // FB not configured → show custom
            PromoAdManager().fetchHouseAd(
                context, layout,
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
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        bindMetaNative(fbNative, layout, context)
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    if (context.isActivityDestroyedCompat()) return
                    Log.e("NativeAds", "FB ad failed: ${adError?.errorMessage}")
                    // fallback to Custom
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        PromoAdManager().fetchHouseAd(
                            context, layout,
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
            ColorStateList.valueOf(parseColorOrFallback(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(parseColorOrFallback(btnColor, "#000000"))
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

    fun parseColorOrFallback(colorString: String?, defaultColor: String): Int {
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
}
