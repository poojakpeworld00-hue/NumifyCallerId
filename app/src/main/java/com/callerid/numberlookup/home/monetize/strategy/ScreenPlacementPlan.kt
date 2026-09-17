package com.callerid.numberlookup.home.monetize.strategy

import com.google.android.gms.ads.AdSize
import android.util.DisplayMetrics
import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.callerid.numberlookup.home.monetize.delivery.BannerLifecycleObserver
import com.callerid.numberlookup.home.monetize.delivery.BannerAdPresenter
import com.callerid.numberlookup.home.monetize.delivery.BannerDimension
import com.callerid.numberlookup.home.monetize.delivery.BannerVariant
import com.callerid.numberlookup.home.monetize.delivery.NativeBannerPresenter
import com.callerid.numberlookup.home.BuildConfig
import org.json.JSONObject

/**
 * Per-screen on-load ad configuration, sourced from Remote Config.
 *
 * Resolved in this order:
 *  - `screen_wise_ad = false` gives the global `googleBanner` / `googleNative`.
 *  - `screen_wise_ad = true` with `screen_wise_default = true` gives
 *    `ScreenAds.default`.
 *  - `screen_wise_ad = true` with `screen_wise_default = false` gives
 *    `ScreenAds.<Screen>`, falling back to `default`. An empty id on any entry
 *    inherits the global one.
 *
 * DEBUG builds log every resolution under the `ScreenPlacementPlan` tag; filter
 * logcat on it to confirm which id and type each screen ends up with.
 */
object ScreenPlacementPlan {

    private const val TAG = "ScreenPlacementPlan"

    /** Resolved on-load ad config for a single screen. */
    data class ScreenAd(
        val show: Boolean,
        val bannerId: String,
        val bannerType: String,
        val nativeId: String,
        val nativeType: String
    )

    /** Resolves the on-load ad config for [screenName] (logs the decision in DEBUG). */
    fun resolve(context: Context, screenName: String): ScreenAd {
        val pref = AdPreferenceStore.getInstance(context)
        val globalBanner = pref.getString("googleBanner").orEmpty()
        val globalNative = pref.getString("googleNative").orEmpty()
        val screenWise = pref.getBoolean("screen_wise_ad")
        val useDefault = pref.getBoolean("screen_wise_default")

        // ScreenAds is parsed whenever present — even with screen_wise_ad=false —
        // because the per-screen `show` flag is honored in GLOBAL id mode too, so a
        // screen like MainShellActivity (show:false) stays hidden while still using the
        // global banner/native ids.
        val root = runCatching { JSONObject(pref.getString("ScreenAds", "{}") ?: "{}") }.getOrNull()

        // Entry that supplies the ad-unit IDs — only when screen-wise ids are on.
        val entry = when {
            !screenWise -> null
            root == null -> null
            useDefault -> root.optJSONObject("default")
            else -> root.optJSONObject(screenName) ?: root.optJSONObject("default")
        }

        // `show` is resolved per-screen regardless of screen_wise_ad: the screen's
        // own ScreenAds entry (else `default`) wins; absent → true (visible).
        val showEntry = root?.let { it.optJSONObject(screenName) ?: it.optJSONObject("default") }
        val globalShow = showEntry?.optBoolean("show", true) ?: true

        val result: ScreenAd
        val source: String
        var bannerFromEntry = false
        var nativeFromEntry = false

        if (entry == null) {
            result = ScreenAd(globalShow, globalBanner, "adaptive", globalNative, "mid")
            source = when {
                !screenWise -> "GLOBAL ids (screen_wise_ad=false), show=$globalShow from ScreenAds"
                root == null -> "GLOBAL (ScreenAds JSON missing/invalid)"
                else -> "GLOBAL (no 'default' entry)"
            }
        } else {
            val bannerRaw = entry.optString("banner")
            val nativeRaw = entry.optString("native")
            bannerFromEntry = bannerRaw.isNotBlank()
            nativeFromEntry = nativeRaw.isNotBlank()
            result = ScreenAd(
                show = entry.optBoolean("show", true),
                bannerId = bannerRaw.ifBlank { globalBanner },
                bannerType = entry.optString("bannerType").ifBlank { "adaptive" },
                nativeId = nativeRaw.ifBlank { globalNative },
                nativeType = entry.optString("nativeType").ifBlank { "mid" }
            )
            source = when {
                useDefault -> "ScreenAds.default"
                root!!.has(screenName) -> "ScreenAds[$screenName]"
                else -> "ScreenAds.default (no entry for $screenName)"
            }
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "resolve($screenName)  screen_wise_ad=$screenWise  screen_wise_default=$useDefault")
            Log.d(TAG, "   source = $source   |   show = ${result.show}")
            Log.d(
                TAG,
                "   banner = ${result.bannerId}  type=${result.bannerType}  " +
                        if (bannerFromEntry) "(from entry)" else "(inherited googleBanner)"
            )
            Log.d(
                TAG,
                "   native = ${result.nativeId}  type=${result.nativeType}  " +
                        if (nativeFromEntry) "(from entry)" else "(inherited googleNative)"
            )
        }
        return result
    }

    /**
     * The global native ad-unit id, aware of screen-wise mode. The native ad pool
     * is preloaded once with no screen context, so this resolves to the `default`
     * entry's native id, or to the global `googleNative` when screen-wise is off.
     */
    fun nativeAdUnitId(context: Context): String {
        val pref = AdPreferenceStore.getInstance(context)
        val globalNative = pref.getString("googleNative").orEmpty()
        val screenWise = pref.getBoolean("screen_wise_ad")

        val result: String
        val source: String
        if (!screenWise) {
            result = globalNative
            source = "googleNative (screen_wise_ad=false)"
        } else {
            val root = runCatching {
                JSONObject(pref.getString("ScreenAds", "{}") ?: "{}")
            }.getOrNull()
            val entryNative = root?.optJSONObject("default")?.optString("native").orEmpty()
            if (entryNative.isNotBlank()) {
                result = entryNative
                source = "ScreenAds.default.native"
            } else {
                result = globalNative
                source = "googleNative (default.native blank/missing)"
            }
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "nativeAdUnitId() = $result   <- $source")
        return result
    }

    /**
     * Loads the bottom on-load banner into [container] for [screenName]. Banner
     * first, and if that fails a native banner takes its place in the same
     * container. It stays hidden when ads are off globally or the screen's `show`
     * flag is false.
     */
    fun showAd(
        screenName: String,
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null
    ) {
        val pref = AdPreferenceStore.getInstance(activity)
        val resolved = resolve(activity, screenName)
        val adsOn = pref.getBoolean("IsAdsON")

        if (!adsOn || !resolved.show) {
            container.minimumHeight = 0
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "showAd($screenName) -> HIDDEN  IsAdsON=$adsOn  show=${resolved.show}")
            }
            return
        }

        // The slot is held open at the height the banner will take, before the load
        // starts.
        //
        // Without it the bottom of the screen moves twice: the slot collapses while
        // the request is in flight and springs back when it fills. On the shell that
        // drags the nav bar - and everything above it - up by the height of an ad,
        // a second or so after the screen looks settled and ready to touch. The
        // dialer is the worst of them: its Call button lands where the nav bar was
        // sitting when the finger started moving.
        //
        // Anchored adaptive banners have one height for a given width, and it is
        // known before the ad is, so there is nothing to guess at.
        container.minimumHeight = anchoredBannerHeightPx(activity, container)

        // bannerType → BannerDimension + collapsible flag.
        val (size, collapsible) = when (resolved.bannerType.lowercase()) {
            "inline" -> BannerDimension.INLINE to false
            "normal" -> BannerDimension.NORMAL to false
            "collapsible" -> BannerDimension.ADAPTIVE to true
            else -> BannerDimension.ADAPTIVE to false
        }

        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "showAd($screenName) -> LOAD banner  id=${resolved.bannerId}  " +
                        "bannerType=${resolved.bannerType}  size=$size  collapsible=$collapsible"
            )
        }

        // disableInternalFallback=true → BannerAdPresenter reports a single onAdFailed()
        // so the native-banner fallback owns the failure path (no double-load).
        BannerAdPresenter().displayBanner(
            activity = activity,
            container = container,
            type = BannerVariant.AUTO,
            size = size,
            isCollapsable = collapsible,
            shimmer = shimmer,
            customAdUnitId = resolved.bannerId.takeIf { it.isNotBlank() },
            disableInternalFallback = true,
            observer = object : BannerLifecycleObserver {
                override fun onAdFailed() {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "showAd($screenName) -> banner failed, fallback to native banner")
                    }
                    NativeBannerPresenter().displayNativeBanner(activity, container, shimmer)
                }
            }
        )
    }

    /**
     * The height an anchored adaptive banner will take at this container's width.
     *
     * Measured the same way [BannerAdPresenter] measures it when it asks for the
     * ad, off the container if it has been laid out and the display if it has not,
     * so the space reserved is the space the banner then occupies.
     */
    private fun anchoredBannerHeightPx(activity: Activity, container: FrameLayout): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay.getMetrics(metrics)

        val widthPx =
            if (container.width == 0) metrics.widthPixels.toFloat()
            else container.width.toFloat()
        val widthDp = (widthPx / metrics.density).toInt()

        return AdSize
            .getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, widthDp)
            .getHeightInPixels(activity)
    }
}
