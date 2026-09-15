package com.contacts.callerid.number.lookup.common

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.isVisible

/**
 * Keeps the 1dp hairline above an ad slot in step with the slot itself.
 *
 * The line exists purely to separate app content from an advert, so it must not
 * outlive the advert. Every "no ad" path in the ad module finishes identically -
 * `removeAllViews()` on the container plus GONE - so a single check covers all
 * of them:
 *
 *  - ads disabled globally via Remote Config (`IsAdsON`)
 *  - a `ScreenAds` entry for the screen carrying `show: false`
 *  - the `NativeCounter` skip
 *  - a banner failure whose native-banner fallback also fails
 *
 * That last case settles asynchronously, so this observes layout passes instead
 * of sampling once: the divider appears as the container starts taking up space,
 * whether shimmer or a real ad, and disappears the instant it collapses again.
 *
 * Call it once, immediately after starting the ad load:
 * ```
 * binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
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
