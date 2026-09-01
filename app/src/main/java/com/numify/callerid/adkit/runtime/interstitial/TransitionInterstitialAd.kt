package com.numify.callerid.adkit.runtime.interstitial

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.core.content.ContextCompat
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.numify.callerid.adkit.contract.AdPlacementType
import com.numify.callerid.adkit.policy.DisplayCadenceManager.interCounter
import com.numify.callerid.adkit.policy.RevenueMonitor
import com.numify.callerid.adkit.policy.AdPreferenceStore
import com.numify.callerid.adkit.policy.logKeyEvent
import com.numify.callerid.adkit.runtime.hasNetworkAccess
import com.numify.callerid.numberlookup.BuildConfig
import com.numify.callerid.numberlookup.R
class TransitionInterstitialAd {

    companion object {

        private var _isInterShow: Boolean = false
        var isInterShow: Boolean
            get() = _isInterShow
            set(value) {
                _isInterShow = value
            }
        private var googleInterAd: InterstitialAd? = null
        private var preloadedFbAd: com.facebook.ads.InterstitialAd? = null
        private var isFbPreloading = false
        // Forwarding callbacks — set just before ad.show(), fired by the preload listener
        private var fbOnDismissed: (() -> Unit)? = null
        private var fbOnFail: (() -> Unit)? = null
        var isOpened = false
        var onTabClosed: (() -> Unit)? = null
        fun resumeAfterSettings(context: Context) {
            if (!isOpened) return

            isOpened = false
            onTabClosed?.invoke()
            onTabClosed = null

            releaseSession(context) // 🔥 ADD THIS
        }
        private var customTabsClient: CustomTabsClient? = null
        private var customTabsSession: CustomTabsSession? = null
        private var serviceConnection: CustomTabsServiceConnection? = null

        fun releaseSession(context: Context) {
            serviceConnection?.let {
                try {
                    context.unbindService(it)
                } catch (_: Exception) {}
            }
            serviceConnection = null
            customTabsClient = null
            customTabsSession = null
        }

        fun warmUpMetaAd(context: Context) {
            val pref = AdPreferenceStore.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (isFbPreloading || preloadedFbAd != null) return
            val fbId = pref.getString("faceB_InterAds") ?: return
            isFbPreloading = true
            val inter = com.facebook.ads.InterstitialAd(context, fbId)
            inter.loadAd(
                inter.buildLoadAdConfig()
                    .withAdListener(object : com.facebook.ads.InterstitialAdListener {
                        override fun onAdLoaded(ad: com.facebook.ads.Ad?) {
                            preloadedFbAd = inter
                            isFbPreloading = false
                        }
                        override fun onError(ad: com.facebook.ads.Ad?, e: com.facebook.ads.AdError?) {
                            isFbPreloading = false
                            fbOnFail?.invoke()
                            fbOnFail = null
                            fbOnDismissed = null
                        }
                        override fun onInterstitialDismissed(ad: com.facebook.ads.Ad?) {
                            warmUpMetaAd(context)   // replenish
                            fbOnDismissed?.invoke()
                            fbOnDismissed = null
                            fbOnFail = null
                        }
                        override fun onInterstitialDisplayed(ad: com.facebook.ads.Ad?) {}
                        override fun onAdClicked(ad: com.facebook.ads.Ad?) {}
                        override fun onLoggingImpression(ad: com.facebook.ads.Ad?) {}
                    }).build()
            )
        }

        fun launchDirectLink(context: Activity, onClosed: () -> Unit) {
            val url = AdPreferenceStore.getInstance(context).getString("DirectLink")

            if (url.isNullOrEmpty()) {
                onClosed()
                return
            }

            val uri = Uri.parse(url)
            isOpened = true
            onTabClosed = onClosed

            getSession(context) { session ->

                val customTab = CustomTabsIntent.Builder(session)
                    .setShowTitle(true)
                    .setToolbarColor(ContextCompat.getColor(context, R.color.black))
                    .build()

                try {
                    customTab.launchUrl(context, uri)
                } catch (e: Exception) {
                    openInBrowser(context, uri, onClosed)
                }
            }
        }

        // Use CustomTabsSession to track tab close
        private fun getSession(
            context: Context,
            onReady: (CustomTabsSession?) -> Unit
        ) {
            if (customTabsSession != null) {
                onReady(customTabsSession)
                return
            }

            serviceConnection = object : CustomTabsServiceConnection() {

                override fun onCustomTabsServiceConnected(
                    name: ComponentName,
                    client: CustomTabsClient
                ) {
                    customTabsClient = client
                    customTabsSession = client.newSession(null)
                    onReady(customTabsSession)
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    customTabsClient = null
                    customTabsSession = null
                    onReady(null)
                }
            }

            CustomTabsClient.bindCustomTabsService(
                context,
                "com.android.chrome",
                serviceConnection as CustomTabsServiceConnection
            )
        }

        private fun openInBrowser(
            context: Activity,
            uri: Uri,
            onClosed: () -> Unit
        ) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
                context.startActivity(intent)
//                safeLog("browser_opened")
            } catch (e: Exception) {
//                context.safeLog("browser_open_failed")
            } finally {
                // Ensure callback is always called
                isOpened = false
                onTabClosed?.invoke()
                onTabClosed = null
                onClosed()
            }
        }


    }


    // ----------------------------------------------------------------------
    // LOAD INTER AD (Google)
    // ----------------------------------------------------------------------
    fun fetchInterstitials(activity: Activity) {
        val pref = AdPreferenceStore.getInstance(activity)
        if (!pref.getBoolean("IsAdsON")) return
        // Firebase "InterAds" master switch — disable interstitial loading entirely
        if (!pref.getBoolean("InterAds")) return
        // Only preload when is_preload_ads = true; on-demand path loads at show time
        if (!pref.getBoolean("is_preload_ads")) return

        val adType = AdPlacementType.fromString(pref.getString("IsAdType"))

        if (adType == AdPlacementType.GOOGLE) {
            activity.safeLog("google_inter_load_start")
            val id = pref.getString("googleInter") ?: return
            InterstitialAd.load(
                activity, id, AdRequest.Builder().build(),
                object : InterstitialAdLoadCallback() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        googleInterAd = null
                        activity.safeLog("google_inter_load_failed_${error.code}")
                        Log.e("TransitionInterstitialAd", "Load Failed: ${error.message}")
                    }
                    override fun onAdLoaded(ad: InterstitialAd) {
                        googleInterAd = ad
                        activity.safeLog("google_inter_loaded")
                        Log.d("TransitionInterstitialAd", "Google inter loaded")
                    }
                })
        }

        if (adType == AdPlacementType.FACEBOOK || pref.getBoolean("IsFail_FB")) {
            warmUpMetaAd(activity)
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC SHOW METHOD
    // ----------------------------------------------------------------------
    fun presentInterstitial(activity: Activity?, adsClose: () -> Unit) {
        showAdInternal(activity, adsClose)
    }

    // ----------------------------------------------------------------------
    // MAIN INTER AD SHOW
    // ----------------------------------------------------------------------
    private fun showAdInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        act.safeLog("inter_request_start")

        val pref = AdPreferenceStore.getInstance(act)
        var hasClosed = false

        fun closeQuietly(reason: String) {
            if (hasClosed) return
            hasClosed = true
            act.safeLog("inter_closed_$reason")
            adsClose()   // OPEN NEXT ACTIVITY INSTANTLY
        }

        // Network check
        if (!hasNetworkAccess(act)) return closeQuietly("no_network")
        if (!pref.getBoolean("IsAdsON")) return closeQuietly("ads_off")
        // Firebase "InterAds" master switch — skip showing interstitials entirely
        if (!pref.getBoolean("InterAds")) return closeQuietly("inter_ads_disabled")

        // Counter logic
        val target = pref.getInt("InterCounter")
        if (interCounter != target) {
            interCounter++
            act.safeLog("inter_counter_skip")
            return closeQuietly("counter_skip")
        }
        interCounter = 0
        act.safeLog("inter_counter_triggered")
        val isPreload = pref.getBoolean("is_preload_ads")

        when (AdPlacementType.fromString(pref.getString("IsAdType"))) {

            AdPlacementType.GOOGLE -> {
                act.safeLog("inter_type_google")
                if (isPreload) {
                    presentAdMobInterstitial(act, pref, ::closeQuietly)
                } else {
                    loadAndShowGoogleOnDemand(act, pref, ::closeQuietly)
                }
            }

            AdPlacementType.FACEBOOK -> {
                act.safeLog("inter_type_facebook")
                if (isPreload && preloadedFbAd != null) {
                    showPreloadedFbAd(
                        act,
                        onDismissed = { closeQuietly("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_preload_fail_fallback")
                            runMetaInterstitial(
                                act,
                                onDismissed = { closeQuietly("fb_dismiss") },
                                onFail = { showCustomAfterFacebookFail(act, pref) { closeQuietly("fb_fail_custom") } }
                            )
                        }
                    )
                } else {
                    runMetaInterstitial(
                        act,
                        onDismissed = { closeQuietly("fb_dismiss") },
                        onFail = {
                            act.safeLog("fb_load_fail")
                            showCustomAfterFacebookFail(act, pref) { closeQuietly("fb_fail_custom") }
                        }
                    )
                }
            }

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> {
                act.safeLog("inter_type_custom")
                if (pref.getBoolean("IsCustomADS"))
                    launchDirectLink(act) { closeQuietly("custom_opened") }
                else closeQuietly("custom_disabled")
            }
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE INTERSTITIAL
    // ----------------------------------------------------------------------
    private fun presentAdMobInterstitial(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {

        val inter = googleInterAd
        if (inter == null) {
            activity.safeLog("google_inter_null")
            return handleGoogleFail(activity, pref, closeQuietly)
        }

        // Log load
        activity.logKeyEvent("google_inter_show_attempt")

        if (BuildConfig.DEBUG) RevenueMonitor.emitDebugRevenue(activity)

        inter.setOnPaidEventListener {
            RevenueMonitor.trackPaidEvent(activity, it)
        }

        inter.fullScreenContentCallback = object : FullScreenContentCallback() {

            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterShow = true
            }

            override fun onAdDismissedFullScreenContent() {
                isInterShow = false
                googleInterAd = null
                activity.safeLog("google_inter_dismiss")
                closeQuietly("google_dismiss")
                if (pref.getBoolean("is_preload_ads")) fetchInterstitials(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterAd = null
                activity.safeLog("google_inter_failed_show_${error.code}")
                handleGoogleFail(activity, pref, closeQuietly)
                if (pref.getBoolean("is_preload_ads")) fetchInterstitials(activity)
            }
        }

        try {
            inter.show(activity)
        } catch (e: Exception) {
            googleInterAd = null
            activity.safeLog("google_inter_exception")
            handleGoogleFail(activity, pref, closeQuietly)
            fetchInterstitials(activity)
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE ON-DEMAND (is_preload_ads = false)
    // ----------------------------------------------------------------------
    private fun loadAndShowGoogleOnDemand(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        val id = pref.getString("googleInter") ?: return handleGoogleFail(activity, pref, closeQuietly)
        val isLoader = pref.getBoolean("isLoaderForFB")

        activity.safeLog("google_inter_ondemand_load_start")
        InterstitialAdCache.show(activity, isLoader)

        InterstitialAd.load(
            activity, id, AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    InterstitialAdCache.hide()
                    googleInterAd = ad
                    activity.safeLog("google_inter_ondemand_loaded")
                    presentAdMobInterstitial(activity, pref, closeQuietly)
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    InterstitialAdCache.hide()
                    googleInterAd = null
                    activity.safeLog("google_inter_ondemand_fail_${error.code}")
                    handleGoogleFail(activity, pref, closeQuietly)
                }
            }
        )
    }

    // ----------------------------------------------------------------------
    // FACEBOOK PRELOADED SHOW (is_preload_ads = true)
    // ----------------------------------------------------------------------
    private fun showPreloadedFbAd(
        activity: Activity,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val ad = preloadedFbAd
        if (ad == null || !ad.isAdLoaded) {
            preloadedFbAd = null
            onFail()
            return
        }
        preloadedFbAd = null
        // Wire forwarding callbacks (listener was set at load time in warmUpMetaAd)
        fbOnDismissed = onDismissed
        fbOnFail = onFail
        try {
            ad.show()
            activity.safeLog("fb_preloaded_show")
        } catch (e: Exception) {
            activity.safeLog("fb_preloaded_show_exception")
            fbOnDismissed = null
            fbOnFail = null
            onFail()
        }
    }

    private fun handleGoogleFail(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        activity.safeLog("google_fail_start")

        val fbEnabled = pref.getBoolean("IsFail_FB")
        val customEnabled = pref.getBoolean("IsCustomADS")

        if (fbEnabled) {

            activity.safeLog("google_fail_try_facebook")

            runMetaInterstitial(
                activity,
                onDismissed = { closeQuietly("fb_dismiss") },
                onFail = {
                    activity.safeLog("fb_fail_after_google_fail")
                    showCustomAfterFacebookFail(activity, pref, closeQuietly)
                }
            )
        } else {
            if (customEnabled) {
                activity.safeLog("google_fail_open_custom")
                launchDirectLink(activity) { closeQuietly("google_fail_custom") }
            } else closeQuietly("google_fail_no_fb_no_custom")
        }
    }

    // ----------------------------------------------------------------------
    // FACEBOOK INTERSTITIAL
    // ----------------------------------------------------------------------
    fun runMetaInterstitial(
        context: Context,
        onDismissed: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = AdPreferenceStore.getInstance(context)
        val isLoader = pref.getBoolean("isLoaderForFB")
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        context.safeLog("facebook_inter_load_start")

        val inter = com.facebook.ads.InterstitialAd(context, fbId)

        // ⬅ FULLSCREEN LOADER (only if Activity)
        // Show loader only if isLoader == true
        if (context is Activity) InterstitialAdCache.show(context, isLoader)

        inter.loadAd(
            inter.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        context.safeLog("facebook_inter_loaded")
                        if (context is Activity) InterstitialAdCache.hide()
                        try {
                            inter.show()
                            context.safeLog("facebook_inter_show")
                        } catch (e: Exception) {
                            context.safeLog("facebook_inter_show_exception")
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, error: com.facebook.ads.AdError?) {
                        context.safeLog("facebook_inter_error_${error?.errorCode}")
                        if (context is Activity) InterstitialAdCache.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        context.safeLog("facebook_inter_dismiss")
                        if (context is Activity) InterstitialAdCache.hide()
                        onDismissed()
                    }

                    override fun onInterstitialDisplayed(ad: Ad?) {
                        context.safeLog("facebook_inter_displayed")

                        if (context is Activity) InterstitialAdCache.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {
                        context.safeLog("facebook_inter_clicked")
                    }

                    override fun onLoggingImpression(ad: Ad?) {
                        context.safeLog("facebook_inter_impression")
                    }

                }).build()
        )
    }

    private fun showCustomAfterFacebookFail(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        activity.safeLog("custom_after_fb_fail")

        if (pref.getBoolean("IsCustomADS"))
            launchDirectLink(activity) { closeQuietly("fb_fail_custom") }
        else closeQuietly("fb_fail_no_custom")
    }

    // ----------------------------------------------------------------------
    // SAFE LOG WRAPPER
    // ----------------------------------------------------------------------
    private fun Context.safeLog(event: String) {
        try {
            logKeyEvent(event)
            Log.d("InterADsLog", event)
        } catch (_: Exception) {
        }
    }
}
