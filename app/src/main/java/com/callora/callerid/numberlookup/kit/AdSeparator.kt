package com.callora.callerid.numberlookup.kit

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible

/**
 * Keeps the 1dp hairline that sits above an ad slot in step with that slot.
 *
 * The line exists only to separate app content from an advert, so it must not
 * survive the advert. Every "no ad" path in the ad module ends the same way —
 * `removeAllViews()` on the container plus GONE — which means one check covers
 * all of them:
 *
 *  - ads switched off globally in Remote Config (`IsAdsON`)
 *  - the screen's `ScreenAds` entry carrying `show: false`
 *  - the `NativeCounter` skip
 *  - a banner failing with the native-banner fallback also failing
 *
 * Because the last case resolves asynchronously, this watches layout passes
 * rather than sampling once: the divider turns on when the container starts
 * occupying space (shimmer or a real ad) and back off the moment it collapses.
 *
 * Call once, right after kicking off the ad load:
 * ```
 * binding.adNativeDividerVw.followAdContainer(binding.adNativeFrameVw)
 * ```
 */
fun View.followAdContainer(container: ViewGroup) {
    // Start hidden so an ads-off screen never flashes a stray line before the
    // first layout pass runs.
    isVisible = false

    container.viewTreeObserver.addOnGlobalLayoutListener(
        object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!container.isAttachedToWindow) {
                    container.viewTreeObserver
                        .takeIf { it.isAlive }
                        ?.removeOnGlobalLayoutListener(this)
                    return
                }
                isVisible = container.isVisible &&
                        container.childCount > 0 &&
                        container.height > 0
            }
        }
    )
}
