package com.numify.callerid.lookup.feature.onboarding

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.monetize.delivery.BottomSheetNativeAds
import com.numify.callerid.lookup.common.followAdContainer
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * Draws the footer ad on an onboarding-flow screen - language, onboarding or
 * fsi_permission - from that screen's own `isBottomAds` and `isBottomAdsType`.
 *
 * Both fields were in the Remote Config schema from the beginning yet nothing
 * consumed them: every screen hardcoded its renderer instead (`displayLargeNative`
 * on Language, `displayMediumNativeAlt` on Onboarding, `displayMediumNative` on
 * FSI), so the format could not change without a new build and the ad could not
 * be turned off at all.
 *
 * Ad **colours** are out of scope here. They arrive from the per-audience
 * `NativeTheme` block, which `AdAwareActivity.applyNativeTheme()` has already
 * resolved into `NativebtnColor`, `NativeBgColor` and friends before any of these
 * renderers run - which is how organic and paid ship different palettes from one
 * code path.
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
            "bignative" -> NativeAdPresenter().displayLargeNative(activity, container, shimmer)
            "mediumnativealt" -> NativeAdPresenter().displayMediumNativeAlt(activity, container, shimmer)
            "banner" -> BottomSheetNativeAds().displayBannerAd(activity, container)
            else -> NativeAdPresenter().displayMediumNative(activity, container, shimmer)
        }
        divider?.followAdContainer(container)
    }
}
