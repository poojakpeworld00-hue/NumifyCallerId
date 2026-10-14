package com.callerid.numberlookup.home.monetize.strategy

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * One consistent set of ad lifecycle events for every format, so fill rate,
 * show rate and impressions can be read the same way for all of them:
 *
 * `ad_{format}_{phase}` with params `screen`, `network` and, on failures, `error_code`.
 *
 * | phase        | when                                                            |
 * |--------------|-----------------------------------------------------------------|
 * | `req`        | the load is requested                                           |
 * | `loaded`     | the network returned an ad                                      |
 * | `no_fill`    | the request was valid but there was no ad (AdMob 3/9, Meta 1001)|
 * | `failed`     | any other load error                                            |
 * | `shown`      | a banner/native view was attached to its container              |
 * | `show`       | a full-screen ad went up                                        |
 * | `impression` | the SDK counted an impression                                   |
 * | `clicked`    | the user tapped the ad                                          |
 * | `close`      | a full-screen ad was dismissed                                  |
 * | `earned` / `not_earned` | rewarded: closed with / without the reward           |
 *
 * Formats: `app_open`, `interstitial`, `native`, `native_banner`, `banner`, `rewarded`.
 *
 * `screen` is where it happened - for a pooled ad, `req`/`loaded` carry the screen
 * that loaded it and `shown`/`impression`/`clicked` the screen that displayed it.
 * The older per-call-site events (`NativeAds_load`, `appopen_ad_shown`, …) are left
 * in place so existing reports keep working; these sit beside them.
 */
object AdAnalytics {

    const val APP_OPEN = "app_open"
    const val INTERSTITIAL = "interstitial"
    const val NATIVE = "native"
    const val NATIVE_BANNER = "native_banner"
    const val BANNER = "banner"
    const val REWARDED = "rewarded"

    const val GOOGLE = "google"
    const val FACEBOOK = "facebook"

    /** AdMob NO_FILL (3) and MEDIATION_NO_FILL (9). */
    private val GOOGLE_NO_FILL = setOf(3, 9)

    /** Meta Audience Network NO_FILL. */
    private const val FACEBOOK_NO_FILL = 1001

    fun req(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "req", screen, network)

    fun loaded(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "loaded", screen, network)

    /** Splits a load error into `no_fill` (healthy, no demand) and `failed` (anything else). */
    fun loadFailed(
        context: Context,
        format: String,
        errorCode: Int,
        screen: String = screenOf(context),
        network: String = GOOGLE,
    ) {
        val noFill = if (network == FACEBOOK) errorCode == FACEBOOK_NO_FILL else errorCode in GOOGLE_NO_FILL
        log(context, format, if (noFill) "no_fill" else "failed", screen, network, errorCode)
    }

    fun shown(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "shown", screen, network)

    fun show(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "show", screen, network)

    /** A full-screen ad that could not be shown. */
    fun showFailed(context: Context, format: String, errorCode: Int, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "show_failed", screen, network, errorCode)

    fun impression(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "impression", screen, network)

    fun clicked(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "clicked", screen, network)

    fun close(context: Context, format: String, screen: String = screenOf(context), network: String = GOOGLE) =
        log(context, format, "close", screen, network)

    fun rewardOutcome(context: Context, earned: Boolean, screen: String = screenOf(context)) =
        log(context, REWARDED, if (earned) "earned" else "not_earned", screen, GOOGLE)

    /**
     * Screen name for a context: the Activity class in snake_case without the
     * "Activity" suffix ("LanguagePickerActivity" → "language_picker"), "app" for
     * the application context.
     */
    fun screenOf(context: Context?): String {
        var c = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        val name = (c as? Activity)?.javaClass?.simpleName ?: return "app"
        return name.removeSuffix("Activity")
            .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
            .lowercase()
            .ifEmpty { "app" }
    }

    private fun log(
        context: Context,
        format: String,
        phase: String,
        screen: String,
        network: String,
        errorCode: Int? = null,
    ) {
        val params = buildMap {
            put("screen", screen)
            put("network", network)
            errorCode?.let { put("error_code", it.toString()) }
        }
        runCatching { context.recordEvent("ad_${format}_$phase", params) }
    }
}
