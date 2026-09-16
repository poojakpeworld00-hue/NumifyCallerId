package com.callerid.numberlookup.home.feature.onboarding

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.delivery.NativeAdPresenter
import com.callerid.numberlookup.home.monetize.delivery.BottomSheetNativeAds
import com.callerid.numberlookup.home.common.followAdContainer
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
     * [screenKey] names the `screen.<key>` entry - see [OnboardingStepConfig].
     *
     * When a screen has **no** config entry at all, which is the case on a first
     * cold start before Remote Config has been fetched, the ad still renders using
     * that screen's historical default, so missing config never quietly strips
     * ads. An entry that does exist and says `isBottomAds:false` is respected.
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
