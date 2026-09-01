package com.numify.callerid.monetize.delivery

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import com.android.installreferrer.api.ReferrerDetails
import com.facebook.FacebookSdk
import com.facebook.LoggingBehavior
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.facebook.appevents.AppEventsLogger
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.FormError
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import io.lighthouse.push.LightHouse
import com.numify.callerid.monetize.model.AdPlacementType
import com.numify.callerid.monetize.model.ResultCallback
import com.numify.callerid.monetize.model.lookupRegionByIp
import com.numify.callerid.monetize.strategy.RevenueMonitor
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.PrivacyConsentGate
import com.numify.callerid.monetize.strategy.recordEvent
import com.numify.callerid.monetize.delivery.fullpage.ExitInterstitialAd
import com.numify.callerid.monetize.delivery.fullpage.TransitionInterstitialAd
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.permission.PermissionCoordinator
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.common.PreferenceStore
import com.numify.callerid.lookup.common.PreferenceStore.THEME_DARK
import com.numify.callerid.lookup.common.PreferenceStore.THEME_LIGHT
import com.numify.callerid.lookup.common.PreferenceStore.THEME_SYSTEM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

open class AdAwareActivity : AppCompatActivity() {
    private var customTabsSession: CustomTabsSession? = null
    private var customTabsClient: CustomTabsClient? = null
    var isCustomTabOpened = false
    var isCloseHandled = false
    var onCustomTabClosed: (() -> Unit)? = null
    private var activity: Activity? = null
    private var onGetData: ResultCallback? = null
    private var isSplash: Boolean? = false
    private val isMobileAdsInitialized = AtomicBoolean(false)
    private val isMobileAdsInitializeCalled = AtomicBoolean(false)
    private var isGoogleAdsEnabled = true
    private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor()

    private companion object {
        /** One grep-able tag for the whole splash AppOpen/interstitial load+show path. */
        const val APPOPEN_TAG = "AppOpenAd"

        /** One grep-able tag for the getData Remote Config → prefs ingestion path. */
        const val CONFIG_TAG = "GetDataConfig"
    }

    open fun getData(
        act: Activity, isSplsh: Boolean? = false, onData: ResultCallback
    ) {
        activity = act
        onGetData = onData
        isSplash = isSplsh

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // We defer MobileAds.initialize() until after consent is obtained.

                if (!isInternetReachable(this@AdAwareActivity)) {
                    withContext(Dispatchers.Main) {
                        onGetData?.onError()
                    }
                    return@launch
                }

                // Consent and SDK initialization
                withContext(Dispatchers.Main) {
                    try {
                        val googleMobileAdsConsentManager =
                            PrivacyConsentGate.getInstance(
                                applicationContext
                            )

                        googleMobileAdsConsentManager.requestConsent(this@AdAwareActivity) { consentError: FormError? ->
                            // Always drive the flow forward once consent gathering
                            // completes. initializeMobileAdsSdk() is what kicks off
                            // remote config → prefs → permissions → navigation, and
                            // it's idempotent. If we only called it when canRequestAds
                            // is true, a consent failure (e.g. "Error making request"
                            // on a slow network) would silently dead-end the splash —
                            // no permission prompt, no navigation, hangs forever.
                            if (consentError != null) {
                                Log.w("AdAwareActivity", "Consent error: ${consentError.message} — proceeding without ads consent")
                            }
                            initializeMobileAdsSdk()
                            if (googleMobileAdsConsentManager.isPrivacyOptionsRequired) {
                                invalidateOptionsMenu()
                            }
                        }

                        // Fast path: if consent is already available, start the SDK +
                        // downstream flow immediately instead of waiting on the callback.
                        if (googleMobileAdsConsentManager.canRequestAds()) {
                            initializeMobileAdsSdk()
                        }
                    } catch (e: Exception) {
                        Log.e("AdAwareActivity", "Consent manager error", e)
                        initializeMobileAdsSdk() // Fallback
                        withContext(Dispatchers.Main) {
                            onGetData?.onError()
                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    onGetData?.onError()
                }
            }
        }
    }

    fun isInternetReachable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        // For Android 10 (API level 29) and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            // For older Android versions
            val activeNetworkInfo = connectivityManager.activeNetworkInfo
            return activeNetworkInfo != null && activeNetworkInfo.isConnected
        }
    }

    private fun initializeMobileAdsSdk() {
        if (isMobileAdsInitializeCalled.getAndSet(true)) {
            return
        }

        // Initialize MobileAds on a background thread after consent
        if (!isMobileAdsInitialized.getAndSet(true)) {
            backgroundExecutor.execute {
                try {
                    activity?.let {
                        isGoogleAdsEnabled = true
                        MobileAds.initialize(it) {}
                        if (BuildConfig.DEBUG) Log.d("AdAwareActivity", "MobileAds initialized")
                    }
                } catch (e: Exception) {
                    isGoogleAdsEnabled = false
                    Log.e("AdAwareActivity", "Failed to initialize MobileAds", e)
                    isMobileAdsInitialized.set(false)
                }
            }
        }

        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(1) // Fetch interval
            // Cap the fetch so a slow network can't park the splash on this
            // call (was defaulting to 60s; observed 37s stalls). On timeout the
            // fetch fails fast → onError()/cached values → flow continues.
            .setFetchTimeoutInSeconds(10)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)
        activity?.let {
            remoteConfig.fetchAndActivate().addOnCompleteListener(it) { task ->
                if (task.isSuccessful) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        setResponceInPref(remoteConfig)
                    }
                } else {
                    onGetData?.onError()
                }
            }
        }
    }

    private fun setResponceInPref(remoteConfig: FirebaseRemoteConfig) {
        // Run everything in a background thread to prevent cold-start stutters and ANR
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val blobKey = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"
                val configString = remoteConfig.getString(blobKey)

                if (configString.isNullOrEmpty()) {
                    Log.w(CONFIG_TAG, "$blobKey is empty → nothing ingested (using cached/default prefs)")
                    return@launch
                }

                val response = JSONObject(configString)
                val adsPref = AdPreferenceStore.getInstance(this@AdAwareActivity)

                val isSplit = response.has("marketing") || response.has("organic")
                val onMarketing = adsPref.getBoolean("OnMaketing")
                if (BuildConfig.DEBUG) Log.d(
                    CONFIG_TAG,
                    "fetched $blobKey (${configString.length} chars) → audienceSplit=$isSplit, " +
                        "OnMaketing=$onMarketing "
                )

                // Persist the raw blob + whether it uses a top-level audience split,
                // so funOnAdsLoad can re-apply the correct audience once the install
                // referrer has resolved OnMaketing (not known on the first launch).
                adsPref.putString("GET_DATA_RAW", configString)
                adsPref.putBoolean("__cfg_audience_split", isSplit)

                // Top-level audience split: every key is read from
                // response.marketing / response.organic (chosen by OnMaketing). A
                // flat response (no wrapper) is used verbatim → legacy config is
                // unchanged.
                ingestConfig(this@AdAwareActivity, audienceRoot(response, onMarketing))

                // Resolve the install referrer, then hand off to funOnAdsLoad. Must
                // run AFTER ingestConfig so funOnAdsLoad reads the freshly-persisted
                // config (GET_DATA_RAW / __cfg_audience_split / ad gates), not stale
                // or half-written values.
                checkInstallerRefere()

            } catch (e: Exception) {
                Log.e("AdAwareActivity", "Failed to update ad preferences: ${e.message}")
            }
        }
    }

    /**
     * Reads every getData key from [root] into AdPreferenceStore (batched). [root] is
     * either the flat response or one of its `marketing` / `organic` sub-objects
     * (see [audienceRoot]). Safe to call again (funOnAdsLoad re-applies the correct
     * audience once the referrer settles OnMaketing).
     */
    private fun ingestConfig(context: Context, root: JSONObject) {
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
                "screen_wise_ad", "screen_wise_default"
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
                "screen", "exit", "ScreenAds", "api_config", "rate_us"
            ).forEach { key -> if (root.has(key)) putString(key, root.optString(key, "")) }

            // screen_order is a JSON array, stored as text like the objects above
            // (read back via JSONArray in OnboardingStepConfig).
            root.optJSONArray("screen_order")?.let { putString("screen_order", it.toString()) }

            // --- Integers ---
            listOf(
                "InterCounter", "InterBackCounter", "MarketInterCounter", "MarketBackCounter",
                "NativeCounter", "MarketNativeCounter", "MidNativeCounter", "BannerCounter",
                "MarketBannerCounter", "MarketAppopenCounter", "AppopenCounter",
                "HD_VBC_Hrs"
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
            setApplication(fbAppId, fbClientToken)
        }

        if (BuildConfig.DEBUG) Log.d(
            CONFIG_TAG,
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
     * The audience-specific sub-object of a getData response — `marketing` or
     * `organic` per [isMarketing], falling back to the other audience, then to the
     * flat [response] itself (legacy, un-split config → unchanged behaviour).
     */
    private fun audienceRoot(response: JSONObject, isMarketing: Boolean): JSONObject {
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        response.optJSONObject(preferred)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → using '$preferred' segment")
            return it
        }
        response.optJSONObject(fallback)?.let {
            if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → '$preferred' missing, fell back to '$fallback' segment")
            return it
        }
        if (BuildConfig.DEBUG) Log.d(CONFIG_TAG, "audienceRoot → no marketing/organic wrapper, using flat config")
        return response
    }

    fun selectNativeTheme(context: Context): String {

        return when (PreferenceStore.selectedTheme(this)) {
            THEME_DARK -> {
                "NativeDark"
            }

            THEME_LIGHT -> {
                "NativeLight"
            }

            THEME_SYSTEM -> {
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

    private fun checkInstallerRefere() {
        if (activity!!.getPreferences(MODE_PRIVATE).getBoolean("isReferrerDone", false)) {
            funOnAdsLoad()
            return
        }

        val referrerClient = InstallReferrerClient.newBuilder(activity).build()
        backgroundExecutor.execute(Runnable { fetchInstallReferrer(referrerClient) })
    }

    private fun funOnAdsLoad() {
        activity?.let { activity ->
            val adsPreference = AdPreferenceStore.getInstance(activity)
            lifecycleScope.launch(Dispatchers.IO) {

                // Fetch IP geo once. Use the ISO country code to set the app's
                // country (Home search chip + Lookup country picker) unless the
                // user already picked one, then feed the ads country-counter logic.
                val location = lookupRegionByIp()
                location?.countryCode?.takeIf { it.isNotBlank() }?.let { iso ->
                    val prefs = SettingsRepository(activity)
                    if (prefs.homeCountryIso.isBlank()) {
                        prefs.homeCountryIso = iso
                        if (BuildConfig.DEBUG) Log.d("LocationCheck", "CountryItem code set from IP: $iso")
                    }
                }

                // Marketing vs organic is decided once, then it selects WHICH
                // country-counter flag + allow-list to apply. Marketing installs use
                // the *_Marketing_* keys; organic installs the plain ones.
                //
                // The audience is sourced from LightHouse attribution rather than raw
                // install-referrer string matching. The await matters: this app binds
                // its OWN InstallReferrerClient, and that callback firing says nothing
                // about whether LightHouse's independent bind has resolved yet — so we
                // wait on LightHouse's own verdict instead of assuming. We are on an IO
                // coroutine here, so this suspends rather than blocking the main thread.
                // The result is persisted so every other reader of OnMaketing (FSI /
                // intro / permission audience split) sees the same value.
                val isMarketingOn = !LightHouse.isOrganicUser(awaitReferrerMs = 5_000L)
                adsPreference.putBoolean("OnMaketing", isMarketingOn)

                // Top-level audience split only: OnMaketing is now final (referrer
                // resolved), so re-apply the correct audience's keys — the first
                // ingest ran before the referrer and may have used the wrong side.
                // Flat config skips this entirely (behaviour unchanged).
                val isSplitConfig = adsPreference.getBoolean("__cfg_audience_split")
                if (isSplitConfig) {
                    val raw = adsPreference.getString("GET_DATA_RAW", "")
                    if (!raw.isNullOrBlank()) runCatching {
                        ingestConfig(activity, audienceRoot(JSONObject(raw), isMarketingOn))
                    }
                }

                // Split config: the resolved audience block already carries the final
                // Iscountry_Counter/CountryList_Counter_NShow (re-applied by ingestConfig
                // just above) — no "Marketing"-suffixed twin to pick between. Only a
                // legacy flat (un-split) config still needs the isMarketingOn branch.
                val countryEnableKey =
                    if (!isSplitConfig && isMarketingOn) "Iscountry_Marketing_Counter" else "Iscountry_Counter"
                val countryListKey =
                    if (!isSplitConfig && isMarketingOn) "CountryList_Marketing_Counter_NShow" else "CountryList_Counter_NShow"
                if (BuildConfig.DEBUG) Log.d(
                    "LocationCheck",
                    "install=${if (isMarketingOn) "MARKETING" else "ORGANIC"} (split=$isSplitConfig) → using $countryEnableKey / $countryListKey"
                )

                if (adsPreference.getBoolean(countryEnableKey)) {
                    location?.let { loc ->
                        if (BuildConfig.DEBUG) {
                            Log.d("LocationCheck", "=== Location Info ===")
                            Log.d("LocationCheck", "CountryItem: ${loc.country}")
                            Log.d("LocationCheck", "Region: ${loc.regionName}")
                            Log.d("LocationCheck", "City: ${loc.city}")
                        }

                        // Save country
                        AdPreferenceStore.getInstance(activity).userCountry = loc.country!!
                        AdPreferenceStore.getInstance(activity).userRegion = loc.regionName!!
                        AdPreferenceStore.getInstance(activity).userCity = loc.city!!
                        // Get stored list from preferences (marketing or organic list).
                        val storedListStr =
                            adsPreference.getString(countryListKey, "") ?: ""

                        val allowedLocations =
                            storedListStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

                        // Check if current country, region, or city is in the list
                        val isAllowed = allowedLocations.any { allowed ->
                            val match = allowed.equals(
                                loc.country, ignoreCase = true
                            ) || allowed.equals(
                                loc.regionName, ignoreCase = true
                            ) || allowed.equals(loc.city, ignoreCase = true)
                            match
                        }

                        if (isAllowed) {
                            if (BuildConfig.DEBUG) Log.d(
                                "LocationCheck", "✅ Location IN list ($countryListKey) → HD_VBC_Show=false (real ads)"
                            )
                            // Do not show CB
                            adsPreference.putBoolean("HD_VBC_Show", false)

                        } else {
                            if (BuildConfig.DEBUG) Log.d(
                                "LocationCheck", "❌ Location NOT in list ($countryListKey) → HD_VBC_Show unchanged"
                            )
                        }
                    } ?: run {
                        if (BuildConfig.DEBUG) Log.w("LocationCheck", "⚠️ Location not available")
                    }
                } else {
                    if (BuildConfig.DEBUG) Log.d("LocationCheck", "CountryItem check is disabled in preferences")
                }

                // (marketing state already decided above as `isMarketingOn`)

                // --------------------
                // 1️⃣ Apply marketing counters (ONLY if marketing ON).
                //     Skipped for a top-level split config — its `marketing` block
                //     already carries the final counters/links, so copying the
                //     *Market* keys (absent there) would zero them out.
                // --------------------
                if (isMarketingOn && !isSplitConfig) {
                    with(adsPreference) {
                        putInt("InterCounter", getInt("MarketInterCounter"))
                        putInt("InterBackCounter", getInt("MarketBackCounter"))
                        putInt("MidNativeCounter", getInt("MarketNativeCounter"))
                        putInt("NativeCounter", getInt("MarketNativeCounter"))
                        putInt("BannerCounter", getInt("MarketBannerCounter"))
                        putInt("AppopenCounter", getInt("MarketAppopenCounter"))
                        putBoolean("IsBack", true)
                        // Only override when the flat config actually carries a
                        // MarketLink — an absent/blank key must not wipe DirectLink.
                        getString("MarketLink")?.takeIf { it.isNotBlank() }
                            ?.let { putString("DirectLink", it) }
                    }
                }
                // --------------------
                // 2️⃣ Apply NativeTheme (Marketing or Default)
                // --------------------
                val savedMarketingStr = adsPreference.getString("NativeTheme_marketing", "{}")
                val savedDefaultStr = adsPreference.getString("NativeTheme_default", "{}")

                val marketingObj = JSONObject(savedMarketingStr)
                val defaultObj = JSONObject(savedDefaultStr)

                // Decide theme source
                val themeSource = if (isMarketingOn) marketingObj else defaultObj
                val modeKey = selectNativeTheme(activity)
                // Get the correct modeKey (e.g., "NativeDark" or "NativeLight")
                val themeJson = themeSource.optJSONObject(modeKey)

                themeJson?.let { theme ->
                    adsPreference.putString("NativebtnColor", theme.optString("btnColor"))
                    adsPreference.putString("NativebtntxtColor", theme.optString("btnText"))
                    adsPreference.putString("NativeBgColor", theme.optString("bgColor"))
                    adsPreference.putString("NativetxtColor", theme.optString("textColor"))

                    if (BuildConfig.DEBUG) Log.d(
                        "NativeTheme",
                        "Applied ${if (isMarketingOn) "MARKETING" else "DEFAULT"} $modeKey theme"
                    )
                }

                launch {

                    val isAdsOn = adsPreference.getBoolean("IsAdsON")


                    withContext(Dispatchers.Main) {

                        // 🔹 Native ads (never block navigation)
                        if (isAdsOn && isSplash == false) {
                            Log.d(APPOPEN_TAG, "isSplash=false → BS Native path (splash AppOpen is NOT attempted here)")
                            launch(Dispatchers.Main) {
                                BottomSheetNativeAds().loadSheetNativeAds(activity)
                                onGetData?.onSuccess()
                            }
                            return@withContext
                        }

                        // Splash owns its permission priming explicitly (notification
                        // → phone_state) via PermissionCoordinator.request() — the targeted,
                        // activity-independent path — rather than check()'s Activity-name
                        // matching. This guarantees the OS Allow/Deny dialogs fire HERE,
                        // on the splash, BEFORE the splash ad, even when the Remote Config
                        // permission_engine rules don't list SplashActivity. Resolving
                        // notification now also stops LightHouse's later
                        // subscribeAsync()/data-disclosure from re-prompting for it on the
                        // next screen. Order is: permission(s) → ad → dismiss/fail → next.
                        primeSplashPermissions(activity) {
                            if (!isAdsOn) {
                                // Ads OFF → continue
                                Log.w(APPOPEN_TAG, "IsAdsON=false → ads disabled, no AppOpen, continuing to app")
                                onGetData?.onSuccess()
                            } else {
                                if (isGoogleAdsEnabled) {
                                    NativeAdPresenter().loadNativeAds(activity)
                                    NativeBannerPresenter().loadNativeBannerAds(activity)
                                    TransitionInterstitialAd().loadInterstitials(activity)
                                    ExitInterstitialAd().loadExitInterstitials(activity)
                                }

                                // Splash ad gated by `screen.splash.ad_type` ("app_open" |
                                // "inter" | "none") — replaces the old two-boolean
                                // (is_splash_ads + is_splash_inter_show) combination.
                                val splashAdType = OnboardingStepConfig.splashConfig(activity).adType
                                Log.d(
                                    APPOPEN_TAG,
                                    "gate → IsAdsON=$isAdsOn, isSplash=$isSplash, screen.splash.ad_type=$splashAdType, " +
                                        "IsAdType=${adsPreference.getString("IsAdType")}, appopenId=${adsPreference.getString("googleAppopen")}"
                                )
                                if (splashAdType.equals("none", ignoreCase = true)) {
                                    // Splash ad disabled from Remote Config → skip entirely
                                    Log.w(APPOPEN_TAG, "screen.splash.ad_type=none → skipping splash ad entirely, continuing to app")
                                    onGetData?.onSuccess()
                                } else {
                                    // Ads ON + splash enabled + gate passed → preload → show → continue
                                    prefetchAds(adsPreference, activity) {
                                        showPreloadedAd(activity, adsPreference) {
                                            onGetData?.onSuccess()
                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            }
        }
    }

    /**
     * Splash no longer requests any runtime permission directly. Notification
     * and READ_PHONE_STATE are now owned entirely by the global
     * [com.numify.callerid.lookup.permission.PermissionCoordinator]
     * (Remote Config-driven, per-Activity, with the HD_VBC_Show gate preserved
     * for phone state). Kept as a thin pass-through so the splash navigation
     * flow is unchanged. [hdVbcShow] is intentionally unused now.
     */
    private fun requestUserPermissions(
        @Suppress("UNUSED_PARAMETER") hdVbcShow: Boolean, onContinue: () -> Unit
    ) {
        onContinue()
    }

    /**
     * Primes the splash's runtime permissions from `screen.splash.permissions[]`
     * (the Onboarding Dynamic Flow — see [OnboardingStepConfig]) through
     * [PermissionCoordinator.checkScreenPermissions], then runs [onDone].
     *
     * Sequencing, SDK applicability, the `HD_VBC_Show` pref gate (phone_state),
     * already-granted, and each permission entry's own country gate are all
     * handled by [PermissionCoordinator.checkScreenPermissions]; [onDone] fires exactly
     * once after the whole queue resolves (or immediately when nothing is
     * pending).
     */
    private fun primeSplashPermissions(activity: Activity, onDone: () -> Unit) {
        PermissionCoordinator.checkScreenPermissions(activity, OnboardingStepConfig.SPLASH_KEY) { onDone() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ------------------------
    // AppOpenAd
    // ------------------------
    // Preload all ads in sequence or parallel
    fun prefetchAds(adsPreference: AdPreferenceStore, activity: Activity, onComplete: () -> Unit) {
        bindCustomTabs(activity)
        val adType = AdPlacementType.fromString(adsPreference.getString("IsAdType"))
        Log.d(APPOPEN_TAG, "preload() → IsAdType=$adType, googleAdsEnabled=$isGoogleAdsEnabled")
        when (adType) {
            AdPlacementType.GOOGLE -> {
                if (isGoogleAdsEnabled) {
                    val showInterstitialOnSplash =
                        OnboardingStepConfig.splashConfig(activity).adType.equals("inter", ignoreCase = true)
                    if (showInterstitialOnSplash) {
                        Log.d(APPOPEN_TAG, "preload → screen.splash.ad_type=inter → loading splash INTERSTITIAL instead of AppOpen")
                        loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
                    } else {
                        Log.d(APPOPEN_TAG, "preload → screen.splash.ad_type=app_open → loading APPOPEN")
                        loadAppOpenAdWithFallback(activity, adsPreference, onComplete)
                    }
                } else {
                    Log.w(APPOPEN_TAG, "preload → Google ads disabled (init failed) → Facebook fallback")
                    loadFacebookFallback(activity, adsPreference, onComplete)
                }
            }

            AdPlacementType.FACEBOOK -> {
                if (adsPreference.getBoolean("IsFail_FB")) {
                    val fbId = adsPreference.getString("faceB_InterAds")!!
                    loadMetaInterstitial(
                        activity,
                        fbId,
                        onLoaded = { onComplete() },
                        onFailed = { onComplete() },
                        onDismissed = {
                            onComplete()
                        }// fallback
                    )
                } else {
                    onComplete()
                }
            }

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> onComplete() // nothing to preload
        }
    }

    fun showPreloadedAd(activity: Activity, adsPreference: AdPreferenceStore, onDismissed: () -> Unit) {
        when (AdPlacementType.fromString(adsPreference.getString("IsAdType"))) {
            AdPlacementType.GOOGLE -> {
                Log.d(APPOPEN_TAG, "showPreloaded() → appOpenReady=${appOpenAd != null}, interstitialReady=${interstitialAd != null}")
                if (isGoogleAdsEnabled && (appOpenAd != null || interstitialAd != null)) {
                    if (appOpenAd != null) {
                        showAppOpenAd(activity) { onDismissed() }
                    } else if (interstitialAd != null) {
                        showAdMobInterstitial(activity) {
                            onDismissed()
                        }
                    }
                } else {
                    if (adsPreference.getBoolean("IsFail_FB")) {
                        val fbId = adsPreference.getString("faceB_InterAds")
                        if (!fbId.isNullOrEmpty() && fbInterstitial != null) {
                            showMetaInterstitial { onDismissed() }
                        } else if (adsPreference.getBoolean("IsCustomADS")) {
                            // All failed + custom ads ON → fallback to custom link
                            launchCustomAdLink(
                                activity, adsPreference.getString("DirectLink")!!
                            ) {
                                onDismissed()
                            }
                        } else {
                            onDismissed()
                        }
                    } else if (adsPreference.getBoolean("IsCustomADS")) {
                        // All failed + custom ads ON → fallback to custom link
                        launchCustomAdLink(
                            activity, adsPreference.getString("DirectLink")!!
                        ) {
                            onDismissed()
                        }
                    } else {
                        onDismissed()
                    }
                }
            }

            AdPlacementType.FACEBOOK -> {
                if (fbInterstitial != null) showMetaInterstitial { onDismissed() }
                else onDismissed()
            }

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> {
                if (adsPreference.getBoolean("IsCustomADS")) {
                    launchCustomAdLink(
                        activity, adsPreference.getString("DirectLink")!!
                    ) {
                        onDismissed()
                    }
                } else {
                    onDismissed()
                }
            }
        }
    }

    private var appOpenAd: AppOpenAd? = null
    private fun loadGoogleInterstitialWithFallback(
        activity: Activity, adsPreference: AdPreferenceStore, onComplete: () -> Unit
    ) {
        val googleId = adsPreference.getString("googleS_Inter") ?: run { onComplete(); return }
        loadAdMobInterstitial(activity, googleId, onLoaded = { onComplete() }, onFailed = {
            loadFacebookFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadAppOpenAdWithFallback(
        activity: Activity, adsPreference: AdPreferenceStore, onComplete: () -> Unit
    ) {
        val appOpenId = adsPreference.getString("googleAppopen")
        if (appOpenId.isNullOrBlank()) {
            Log.w(APPOPEN_TAG, "no/blank 'googleAppopen' unit id in Remote Config → skipping AppOpen, continuing")
            onComplete(); return
        }
        loadAppOpenAd(activity, appOpenId, onLoaded = { onComplete() }, onFailed = {
            Log.w(APPOPEN_TAG, "AppOpen load failed → falling back to Google interstitial")
            loadGoogleInterstitialWithFallback(activity, adsPreference, onComplete)
        })
    }

    private fun loadFacebookFallback(
        activity: Activity, adsPreference: AdPreferenceStore, onComplete: () -> Unit
    ) {
        if (adsPreference.getBoolean("IsFail_FB")) {
            val fbId = adsPreference.getString("faceB_InterAds")
            if (!fbId.isNullOrEmpty()) {
                loadMetaInterstitial(
                    activity,
                    fbId,
                    onLoaded = { onComplete() },
                    onFailed = { onComplete() },
                    onDismissed = { onComplete() })
            } else onComplete()
        } else onComplete()
    }

    fun loadAppOpenAd(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        Log.d(APPOPEN_TAG, "load() → requesting AppOpen, id=$adUnitId")
        AppOpenAd.load(
            activity,
            adUnitId,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    // The single most useful line for "why didn't it load".
                    Log.e(
                        APPOPEN_TAG,
                        "load FAILED → code=${loadAdError.code}, domain=${loadAdError.domain}, " +
                            "message=${loadAdError.message}"
                    )
                    appOpenAd = null
                    AppOpenAdManager.isShowingAd = false
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: AppOpenAd) {
                    Log.d(APPOPEN_TAG, "load SUCCESS → AppOpen ad is ready to show")
                    appOpenAd = ad
                    onLoaded?.invoke()
                }
            })
    }

    fun showAppOpenAd(activity: Activity, onDismissed: (() -> Unit)? = null) {
        appOpenAd?.let { ad ->
            Log.d(APPOPEN_TAG, "show() → displaying AppOpen ad")
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                    AppOpenAdManager.isShowingAd = false
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                    AppOpenAdManager.isShowingAd = true
                }
            }
            // Log load
            activity.recordEvent("AppOpen_Loaded")

            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)

            appOpenAd?.setOnPaidEventListener {
                RevenueMonitor.reportPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run {
            // Ad not loaded yet
            Log.w(APPOPEN_TAG, "show() → AppOpen ad is null (not loaded in time) → skipping show")
            onDismissed?.invoke()
        }
    }

    // ------------------------
// Google Interstitial
// ------------------------
    private var interstitialAd: InterstitialAd? = null

    fun loadAdMobInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null
    ) {
        InterstitialAd.load(
            activity, adUnitId, AdRequest.Builder().build(), object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    onLoaded?.invoke()
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    interstitialAd = null
                    onFailed?.invoke()
                }
            })
    }

    fun showAdMobInterstitial(activity: Activity, onDismissed: (() -> Unit)? = null) {
        interstitialAd?.let { ad ->
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    onDismissed?.invoke()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    onDismissed?.invoke()
                }

                override fun onAdShowedFullScreenContent() {
                }
            }

            // Log load
            activity.recordEvent("Interstitial_Splash_Loaded")

            if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)

            interstitialAd?.setOnPaidEventListener {
                RevenueMonitor.reportPaidEvent(activity, it)
            }
            ad.show(activity)
        } ?: run { onDismissed?.invoke() }
    }

    // ------------------------
// Facebook Interstitial
// ------------------------
    private var fbInterstitial: com.facebook.ads.InterstitialAd? = null

    fun loadMetaInterstitial(
        activity: Activity,
        adUnitId: String,
        onLoaded: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
        onDismissed: (() -> Unit)? = null // Added parameter
    ) {
        fbInterstitial = com.facebook.ads.InterstitialAd(activity, adUnitId)
        fbInterstitial?.loadAd(
            fbInterstitial!!.buildLoadAdConfig().withAdListener(object : InterstitialAdListener {
                override fun onInterstitialDisplayed(ad: Ad?) {}
                override fun onInterstitialDismissed(ad: Ad?) {
                    onDismissed?.invoke()
                }

                override fun onError(ad: Ad?, adError: com.facebook.ads.AdError?) {
                    onFailed?.invoke()
                }

                override fun onAdLoaded(ad: Ad?) {
                    onLoaded?.invoke()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {}
            }).build()
        )
    }

    fun showMetaInterstitial(onDismissed: (() -> Unit)? = null) {
        val ad = fbInterstitial

        if (ad != null && ad.isAdLoaded) {
            ad.show()
        } else {
            onDismissed?.invoke()
        }
    }

    // 1️⃣ Bind Custom Tabs
    private fun bindCustomTabs(activity: Activity) {
        if (customTabsClient != null) return

        CustomTabsClient.bindCustomTabsService(
            activity, "com.android.chrome", object : CustomTabsServiceConnection() {
                override fun onCustomTabsServiceConnected(
                    name: ComponentName, client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(object : CustomTabsCallback() {
                        override fun onNavigationEvent(event: Int, extras: Bundle?) {
                            if ((event == CustomTabsCallback.TAB_HIDDEN || event == CustomTabsCallback.NAVIGATION_ABORTED) && isCustomTabOpened) {
                                handleCustomTabClose()
                            }
                        }
                    })
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    customTabsClient = null
                    customTabsSession = null
                }
            })
    }

    // 2️⃣ Handle close in one function
    private fun handleCustomTabClose() {
        if (isFinishing || isDestroyed) return
        if (!isCloseHandled) {
            isCloseHandled = true
            isCustomTabOpened = false
            onCustomTabClosed?.invoke()
        }
    }

    override fun onDestroy() {
        try {
            customTabsClient = null
            customTabsSession = null
            onCustomTabClosed = null
            activity = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    // 3️⃣ Launch Custom Tab
    private fun launchCustomAdLink(activity: Activity, url: String, onClosed: () -> Unit) {
        bindCustomTabs(activity)

        isCustomTabOpened = true
        isCloseHandled = false
        onCustomTabClosed = { onClosed() }

        val customTabsIntent = CustomTabsIntent.Builder(customTabsSession).setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black)).build()

        try {
            customTabsIntent.launchUrl(activity, Uri.parse(url))
        } catch (e: Exception) {
            // fallback to browser
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            handleCustomTabClose()
        }
    }

    override fun onResume() {
        super.onResume()
        handleCustomTabClose()
    }

    fun fetchInstallReferrer(referrerClient: InstallReferrerClient) {
        // Every exit below routes through this one handoff. It used to be called from
        // some branches only: DEVELOPER_ERROR / PERMISSION_ERROR fell through an
        // unlisted `when`, and a disconnect before setup finished went nowhere at all —
        // in both cases funOnAdsLoad() never ran, so the audience config was never
        // re-ingested and the install silently stayed on whatever the first pass used.
        // The guard makes it fire exactly once no matter how many exits are reached.
        val handedOff = AtomicBoolean(false)
        fun handoff() {
            if (handedOff.compareAndSet(false, true)) funOnAdsLoad()
        }

        referrerClient.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                when (responseCode) {
                    InstallReferrerClient.InstallReferrerResponse.OK -> {
                        var response: ReferrerDetails? = null
                        try {
                            response = referrerClient.installReferrer

                            val ref = response.installReferrer
                            activity?.let {
                                AdPreferenceStore.getInstance(it).putString("FinalString", ref)
                            }
                            // Audience (OnMaketing) is no longer derived from the
                            // referrer string — funOnAdsLoad() now resolves it from
                            // LightHouse attribution. We still store the raw referrer
                            // (FinalString) and drive the flow forward.
                            handoff()

                            activity?.getPreferences(MODE_PRIVATE)?.edit()?.apply {
                                putBoolean("isReferrerDone", true)
                                apply() // Use apply() for efficiency
                            }
                        } catch (e: RemoteException) {
                            handoff()
                        } finally {
                            referrerClient.endConnection()
                        }

                    }

                    else -> handoff()
                }
            }

            override fun onInstallReferrerServiceDisconnected() {
                handoff()
            }
        })
    }

    private fun setApplication(fbAppId: String, fbClientToken: String) {
        FacebookSdk.setApplicationId(fbAppId)
        FacebookSdk.setClientToken(fbClientToken)
        activity?.let { FacebookSdk.sdkInitialize(it) }

        FacebookSdk.setAutoInitEnabled(true)
        FacebookSdk.fullyInitialize()
        FacebookSdk.setAutoLogAppEventsEnabled(true)
        FacebookSdk.addLoggingBehavior(LoggingBehavior.APP_EVENTS)
        val logger = activity?.let { AppEventsLogger.newLogger(it) }
        logger?.applicationId
    }
}
