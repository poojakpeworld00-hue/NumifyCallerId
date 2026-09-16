package com.callerid.numberlookup.home.monetize.strategy

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig
import com.callerid.numberlookup.home.common.PreferenceStore
import com.callerid.numberlookup.home.resolver.EndpointConfig
import com.callerid.numberlookup.home.monetize.delivery.PromoAdManager
import org.json.JSONObject

/**
 * Parses a getData Remote Config blob into [AdPreferenceStore].
 *
 * Lifted out of AdAwareActivity so the same ingestion can run with only an
 * application Context. That is what lets an rc_sync push refresh the cached
 * config in place instead of the change waiting for the next cold start, and it
 * takes ~150 lines of config parsing out of a screen class that was not the
 * right home for it.
 *
 * Facebook SDK initialisation is deliberately not done here — it needs a live
 * Activity — so [ingestConfig] hands the two keys back through [onFacebookKeys]
 * and the caller decides whether it can act on them.
 */
object AdConfigIngest {

    fun ingestConfig(
        context: Context,
        root: JSONObject,
        onFacebookKeys: ((String, String) -> Unit)? = null
    ) {
        val adsPref = AdPreferenceStore.getInstance(context)
        adsPref.update {
            // --- Booleans ---
            listOf(
                "IsAdsON", "IsFail_FB", "isLoaderForFB", "IsCustomADS", "IsBack",
                "NativeBannerPresenter", "BannerAdPresenter", "In_App_Update_Show", "In_App_Update_Force_Show",
                "Iscountry_Counter", "HD_VBC_Show",
                "HD_VBC_Native", "is_preload_ads",
                "InterAds", "AppopenAds",
                "NativeAd", "is_rateus",
                "screen_wise_ad", "screen_wise_default",
                // Ask AI. Absent from the config means "leave the compiled
                // default alone" — the root.has() guard below sees to that — so
                // an older config never force-disables the assistant.
                "ai_assistant_enabled", "ai_assistant_home_tooltip",
                // Send a tool tap through the overlay Settings page first
                // (ToolOverlayGate). Absent means off — a config that fails to
                // fetch must not start pushing people into system Settings.
                "tools_overlay_ask",
                // App-wide off switch for ASKING about the overlay permission
                // (OverlayAskPolicy). Defaults to TRUE when absent, unlike every
                // other flag here: it exists to take something away, so an
                // unpublished or failed config must leave the app as it was.
                "overlay_ask_enabled"
            ).forEach { key -> if (root.has(key)) putBoolean(key, root.optBoolean(key, false)) }

            // --- Strings ---
            listOf(
                "IsAdType", "In_App_Update_Link", "CountryList_Counter_NShow",
                "PrivacyPolicy", "TermLink",
                // MarketLink is only present in a legacy *flat* config, where
                // funOnAdsLoad() copies it over DirectLink for the marketing
                // audience. It must stay ingested or that copy blanks DirectLink
                // (which drives the house-ad click-through). A split config simply
                // omits the key, so the `root.has(key)` guard skips it.
                "DirectLink", "MarketLink", "HD_VBC_Native_ID", "HD_VBC_Banner_ID",
                "googleS_Inter", "googleBackInter", "googleInter", "googleAppopen",
                "googleNative", "googleBanner", "googleRewarded", "faceB_InterAds",
                "faceB_NativeAds", "faceB_NativeBannerAds", "faceB_BannerAds",
                "NativeTheme", "HD_VBC_Type", "NativeBgColor", "NativebtnColor",
                "NativetxtColor", "NativebtntxtColor",
                // Nested JSON objects stored as text (read back via JSONObject) —
                // the Onboarding Dynamic Flow (replaces intro_display + permission_engine),
                // and the lookup API endpoints (see EndpointConfig).
                "screen", "exit", "ScreenAds", "api_config", "rate_us",
                // Wording of the data-deletion dialog (see DataDeletionConfig).
                // Published rather than compiled because it is the copy a store
                // review reads, and it has to change without a release.
                "data_deletion",
                // Base URL of the AI proxy. Blank keeps the assistant on-device.
                "ai_assistant_endpoint"
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            // screen_order is a JSON array, stored as text like the objects above
            // (read back via JSONArray in OnboardingStepConfig).
            root.optJSONArray("screen_order")?.let { putString("screen_order", it.toString()) }

            // --- Integers ---
            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "HD_VBC_Hrs",
                "ai_assistant_free_queries",
                // How many numbers may be blocked before the rewarded-ad gate
                // starts (BlockReward). Negative switches the gate off.
                "blocklist_free_quota"
            ).forEach { key -> if (root.has(key)) putInt(key, root.optInt(key, 0)) }

            applyNativeTheme(context, root) // DEFAULT theme

            // --- Custom Ads ---
            val customAdsArray = root.optJSONArray("custom_ads")
            if (customAdsArray != null) {
                putString("CUSTOM_ADS", customAdsArray.toString())
                PromoAdManager.clearCache()
            }
        }

        // Facebook Ad initialization parameters
        val fbAppId = root.optString("FbAppId", "")
        val fbClientToken = root.optString("FbClientToken", "")
        if (fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()) {
            onFacebookKeys?.invoke(fbAppId, fbClientToken)
        }

        if (BuildConfig.DEBUG) Log.d(
            "GetDataConfig",
            "ingested → IsAdsON=${adsPref.getBoolean("IsAdsON")}, IsAdType=${adsPref.getString("IsAdType")}, " +
                "InterAds=${adsPref.getBoolean("InterAds")}, AppopenAds=${adsPref.getBoolean("AppopenAds")}, " +
                "NativeAd=${adsPref.getBoolean("NativeAd")}, BannerAdPresenter=${adsPref.getBoolean("BannerAdPresenter")}, " +
                "HD_VBC_Show=${adsPref.getBoolean("HD_VBC_Show")}, HD_VBC_Hrs=${adsPref.getInt("HD_VBC_Hrs")}, " +
                "screen_wise_ad=${adsPref.getBoolean("screen_wise_ad")}, " +
                "customAds=${root.optJSONArray("custom_ads")?.length() ?: 0}, " +
                "fbInit=${fbAppId.isNotEmpty() && fbClientToken.isNotEmpty()}, " +
                "appOpenId=${adsPref.getString("googleAppopen")}"
        )
    }

    /**
     * The audience-specific sub-object of a getData response: `marketing` or
     * `organic` according to [isMarketing], falling back to the other audience and
     * then to the flat [response] itself, which is the legacy un-split config and
     * behaves exactly as before.
     */
    fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        response.optJSONObject(preferred)?.let {
            if (BuildConfig.DEBUG) Log.d("GetDataConfig", "audienceRoot → using '$preferred' segment")
            return it
        }
        response.optJSONObject(fallback)?.let {
            if (BuildConfig.DEBUG) Log.d("GetDataConfig", "audienceRoot → '$preferred' missing, fell back to '$fallback' segment")
            return it
        }
        if (BuildConfig.DEBUG) Log.d("GetDataConfig", "audienceRoot → no marketing/organic wrapper, using flat config")
        return response
    }

    fun selectNativeTheme(context: Context): String {

        return when (PreferenceStore.selectedTheme(context)) {
            PreferenceStore.THEME_DARK -> {
                "NativeDark"
            }

            PreferenceStore.THEME_LIGHT -> {
                "NativeLight"
            }

            PreferenceStore.THEME_SYSTEM -> {
                val isSystemDark =
                    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                if (isSystemDark) "NativeDark" else "NativeLight"
            }

            else -> {
                "NativeLight"
            }
        }
    }

    private fun applyNativeTheme(
        context: Context, response: JSONObject
    ) {
        val adsPreference = AdPreferenceStore.getInstance(context)

        val nativeThemeRoot = response.optJSONObject("NativeTheme") ?: return

        val modeKey = selectNativeTheme(context)

        nativeThemeRoot?.let {
            val defaultObj = it.optJSONObject("default")
            // A top-level split config (marketing{}/organic{}) has no nested
            // "marketing" sub-key inside NativeTheme — the outer split already
            // resolved the audience, so "default" already holds the right colors.
            // Only a legacy flat config carries an explicit "marketing" override.
            val marketingObj = it.optJSONObject("marketing") ?: defaultObj

            // Convert JSONObjects to strings before storing in AdPreferenceStore
            val marketingStr = marketingObj?.toString() ?: "{}"
            val defaultStr = defaultObj?.toString() ?: "{}"

            adsPreference.putString("NativeTheme_marketing", marketingStr)
            adsPreference.putString("NativeTheme_default", defaultStr)

            val themeJson = defaultObj?.optJSONObject(modeKey)

            if (themeJson != null) {
                adsPreference.putString("NativebtnColor", themeJson.optString("btnColor"))
                adsPreference.putString("NativebtntxtColor", themeJson.optString("btnText"))
                adsPreference.putString("NativeBgColor", themeJson.optString("bgColor"))
                adsPreference.putString("NativetxtColor", themeJson.optString("textColor"))
            }
        }
    }
}
