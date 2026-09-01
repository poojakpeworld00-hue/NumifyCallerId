package com.callora.callerid.adkit.runtime

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.callora.callerid.adkit.contract.AdSlotType
import com.callora.callerid.adkit.policy.AdFrequencyManager.nativeBannerCounter
import com.callora.callerid.adkit.policy.AdEarningsTracker
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.policy.TAG_EVENT
import com.callora.callerid.adkit.policy.logKeyEvent
import com.callora.callerid.numberlookup.BuildConfig
import com.callora.callerid.numberlookup.databinding.MetaNativeBannerBinding
import com.callora.callerid.numberlookup.databinding.GadsSmallNativeBinding
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

class NativeBannerAd {
    companion object {
        private var nativeAdBanner: NativeAd? = null
    }

    fun fetchNativeBannerAds(activity: Activity) {
        val adsPref = AdsPrefStore.getInstance(activity)
        if (!adsPref.getBoolean("IsAdsON")) return
        // Firebase "NativeBannerAd" master switch — disable native banner loading
        if (!adsPref.getBoolean("NativeBannerAd")) return


        when (AdSlotType.fromString(adsPref.getString("IsAdType"))) {
            AdSlotType.GOOGLE -> {
                val adUnitId = adsPref.getString("googleNative") ?: return

                val adLoader = AdLoader.Builder(activity, adUnitId).forNativeAd { ad ->
                    nativeAdBanner?.destroy()
                    nativeAdBanner = ad
                    try {
                        activity.logKeyEvent("NativeBanner_Load")
                    } catch (e: Exception) {
                    }

                    Log.d("NativeBannerAd", "Ad loaded successfully")
                }.withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e("NativeBannerAd", "Ad failed to load: ${error.message}")
                        nativeAdBanner = null
                        // No retry logic

                        try {
                            activity.logKeyEvent("NativeBanner_fail")
                        } catch (e: Exception) {
                        }
                    }
                }).withNativeAdOptions(NativeAdOptions.Builder().build()).build()

                adLoader.loadAd(AdRequest.Builder().build())
            }

            AdSlotType.FACEBOOK -> {
                // FB ad type → directly try FB
                Log.e(TAG_EVENT, "AdSlotType FaceBook NOt Pre load Google Native")
                return
            }

            AdSlotType.UNKNOWN, AdSlotType.CUSTOM -> {
                // Custom ad type
                Log.e(TAG_EVENT, "AdSlotType Custom NOt Pre load Google Native")
                return
            }
        }


    }

    fun renderNativeBanner(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        Log.e("NativeAds", "Google Show: nativeAd")
        val adsPref = AdsPrefStore.getInstance(context)

        // 🔥 CRASH FIX 1: Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return


        if (!hasNetworkAccess(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeBannerAd")
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

        when (AdSlotType.fromString(adsPref.getString("IsAdType"))) {
            AdSlotType.GOOGLE -> {

                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
                        if (nativeAdBanner != null) {
                            val binding = GadsSmallNativeBinding.inflate(context.layoutInflater)
                            bindGoogleNativeAd(nativeAdBanner!!, binding, context)

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            layout.addView(binding.root)
                            nativeAdBanner = null
                            fetchNativeBannerAds(context)
                            return@post
                        } else {
                            // Google failed → FB fallback or Custom
                            if (adsPref.getBoolean("IsFail_FB")) {
                                showFBNativeBannerFallback(context, layout)
                            } else {
                                layout.removeAllViews()
                                shimmer?.stopShimmer()
                                shimmer?.isVisible = false
                                HouseAdsManager().fetchHouseAd(
                                    context, layout, HouseAdsManager.CustomAdType.BANNER
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("NativeBannerAd", "Google NativeBannerAd failed: ${e.message}")
                    }
                }
            }

            AdSlotType.FACEBOOK -> {
                showFBNativeBannerFallback(context, layout)
            }

            AdSlotType.UNKNOWN, AdSlotType.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                HouseAdsManager().fetchHouseAd(
                    context, layout, HouseAdsManager.CustomAdType.BANNER
                )
            }
        }
    }

    private fun bindGoogleNativeAd(
        nativeAd: NativeAd, binding: GadsSmallNativeBinding, context: Activity
    ) {
        // Log load
        context.logKeyEvent("NativeBanner_Show_Google")

        if (BuildConfig.DEBUG) AdEarningsTracker.emitDebugRevenue(context)

        nativeAd.setOnPaidEventListener {
            AdEarningsTracker.trackPaidEvent(context, it)
        }

        binding.apply {
            mainNativeadView.headlineView = adHeadline
            mainNativeadView.bodyView = adBody
            mainNativeadView.callToActionView = adCallToAction
            mainNativeadView.iconView = adAppIcon

            (adHeadline as TextView).text = nativeAd.headline

            val bgColor = AdsPrefStore.getInstance(context).getString("NativeBgColor")
            val btnColor = AdsPrefStore.getInstance(context).getString("NativebtnColor")


            val txtColor =
                AdsPrefStore.getInstance(context).getString("NativetxtColor") ?: "#000000"

            (binding.mainNativeadView.headlineView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            (binding.mainNativeadView.bodyView as TextView).apply {
                setTextColor(Color.parseColor(txtColor))
            }

            val btntxtColor =
                AdsPrefStore.getInstance(context).getString("NativebtntxtColor") ?: "#000000"

            (adCallToAction as TextView).apply {
                setTextColor(Color.parseColor(btntxtColor))
            }

            mainNativeadView.backgroundTintList =
                ColorStateList.valueOf(parseColorOrFallback(bgColor, "#FFFFFF"))

            adCallToAction.backgroundTintList =
                ColorStateList.valueOf(parseColorOrFallback(btnColor, "#000000"))


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


    private fun showFBNativeBannerFallback(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = AdsPrefStore.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeBannerAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            HouseAdsManager().fetchHouseAd(
                context, layout, HouseAdsManager.CustomAdType.BANNER
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    layout.removeAllViews()
                    bindMetaNativeBanner(fbNative, layout, context)
                    context.logKeyEvent("NativeBAnner_FB")
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                    HouseAdsManager().fetchHouseAd(
                        context, layout, HouseAdsManager.CustomAdType.BANNER
                    )
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
            MetaNativeBannerBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

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

        val bgColor = AdsPrefStore.getInstance(activity).getString("NativeBgColor")
        val btnColor = AdsPrefStore.getInstance(activity).getString("NativebtnColor")
        val txtColor = AdsPrefStore.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            AdsPrefStore.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(parseColorOrFallback(bgColor, "#FFFFFF"))

        binding.nativeAdCallToAction.backgroundTintList =
            ColorStateList.valueOf(parseColorOrFallback(btnColor, "#000000"))
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
