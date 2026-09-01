package com.numify.callerid.numberlookup.screen.gate

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.numify.callerid.adkit.policy.AdPreferenceStore
import com.numify.callerid.adkit.runtime.NativeAdPresenter
import com.numify.callerid.adkit.runtime.BottomSheetNativeAds
import com.numify.callerid.numberlookup.kit.followAdContainer
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
 * `NativeTheme` block, which `AdAwareActivity.applyNativeTheme()` already resolves
 * into `NativebtnColor` / `NativeBgColor` / … before any of these renderers run.
 * That is what lets organic and paid ship different palettes off the same code.
 */
object OnboardingFooterAd {

    /**
     * [screenKey] is the `screen.<key>` entry — see [OnboardingStepConfig].
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
        val step = OnboardingStepConfig.stepConfig(activity, screenKey)
        val adsOn = AdPreferenceStore.getInstance(activity).getBoolean("IsAdsON")
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
            "bignative" -> NativeAdPresenter().renderBigNative(activity, container, shimmer)
            "mediumnativealt" -> NativeAdPresenter().renderMidNativeAlt(activity, container, shimmer)
            "banner" -> BottomSheetNativeAds().renderBannerAd(activity, container)
            else -> NativeAdPresenter().renderMidNative(activity, container, shimmer)
        }
        divider?.followAdContainer(container)
    }
}
