package com.callora.callerid.numberlookup.screen.gate

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.runtime.NativeAdLoader
import com.callora.callerid.adkit.runtime.SheetNativeAds
import com.callora.callerid.numberlookup.kit.followAdContainer
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Renders the footer ad on an onboarding-flow screen (language / onboarding /
 * fsi_permission) from that screen's `isBottomAds` + `isBottomAdsType`.
 *
 * These two fields were in the Remote Config schema from the start but nothing
 * read them — each screen hard-coded its own renderer (`renderBigNative` on
 * Language, `renderMidNativeAlt` on Onboarding, `renderMidNative` on FSI), so
 * the format could not be changed without shipping a build, and the ad could not
 * be switched off at all.
 *
 * Ad **colours** are not handled here: they come from the per-audience
 * `NativeTheme` block, which `AdHostActivity.applyNativeTheme()` already resolves
 * into `NativebtnColor` / `NativeBgColor` / … before any of these renderers run.
 * That is what lets organic and paid ship different palettes off the same code.
 */
object FlowFooterAd {

    /**
     * [screenKey] is the `screen.<key>` entry — see [OnboardingFlowConfig].
     *
     * When the screen has **no** config entry at all (Remote Config not fetched
     * yet on a first cold start) the ad still renders with the screen's historical
     * default, so a missing config never silently strips ads. An entry that exists
     * and says `isBottomAds:false` is honoured.
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        activity: Activity,
        screenKey: String,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        divider: View? = null,
        fallbackType: String = "MediumNative",
    ) {
        val step = OnboardingFlowConfig.stepConfig(activity, screenKey)
        val adsOn = AdsPrefStore.getInstance(activity).getBoolean("IsAdsON")
        val wanted = step?.isBottomAds ?: true   // no entry → historical default

        if (!adsOn || !wanted) {
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            divider?.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        when ((step?.isBottomAdsType ?: fallbackType).lowercase()) {
            "bignative" -> NativeAdLoader().renderBigNative(activity, container, shimmer)
            "mediumnativealt" -> NativeAdLoader().renderMidNativeAlt(activity, container, shimmer)
            "banner" -> SheetNativeAds().renderBannerAd(activity, container)
            else -> NativeAdLoader().renderMidNative(activity, container, shimmer)
        }
        divider?.followAdContainer(container)
    }
}
