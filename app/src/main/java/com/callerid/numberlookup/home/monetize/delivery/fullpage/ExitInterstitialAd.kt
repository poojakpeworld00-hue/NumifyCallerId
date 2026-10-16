package com.callerid.numberlookup.home.monetize.delivery.fullpage

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.callerid.numberlookup.home.monetize.model.AdPlacementType
import com.callerid.numberlookup.home.monetize.strategy.DisplayCadenceManager.interBackCounter
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.monetize.strategy.AdAnalytics
import com.callerid.numberlookup.home.monetize.strategy.RevenueMonitor
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.monetize.delivery.isNetworkAvailable

class ExitInterstitialAd {

    companion object {
        private var _isInterBAckShow: Boolean = false
        var isInterBAckShow: Boolean
            get() = _isInterBAckShow
            set(value) {
                _isInterBAckShow = value
            }
        private var googleInterBack: InterstitialAd? = null
    }

    // ----------------------------------------------------------------------
    // LOAD GOOGLE INTERSTITIAL (Back Ads)
    // ----------------------------------------------------------------------
    fun loadExitInterstitials(activity: Activity) {
        val pref = AdPreferenceStore.getInstance(activity)

        // These were "BackLoad:AdsOFF" and friends - names with a colon, which
        // Firebase rejects, so not one of them was ever recorded.
        if (!pref.getBoolean("IsAdsON")) {
            activity.logSkip("ads_off")
            return
        }

        // Firebase "InterAds" master switch — back ads are interstitials too
        if (!pref.getBoolean("InterAds")) {
            activity.logSkip("inter_ads_off")
            return
        }

        if (!pref.getBoolean("IsBack")) {
            activity.logSkip("back_ads_off")
            return
        }

        val id = pref.getString("googleBackInter") ?: return
        val req = AdRequest.Builder().build()

        if (AdPlacementType.fromString(pref.getString("IsAdType")) == AdPlacementType.GOOGLE) {
            AdAnalytics.req(activity, AdAnalytics.BACK_INTERSTITIAL)
            InterstitialAd.load(
                activity, id, req,
                object : InterstitialAdLoadCallback() {

                    override fun onAdLoaded(ad: InterstitialAd) {
                        googleInterBack = ad
                        Log.d("ExitInterstitialAd", "Back Inter Loaded")
                        activity.safeLog("Back_Inter_Loaded")
                        AdAnalytics.loaded(activity, AdAnalytics.BACK_INTERSTITIAL)
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        googleInterBack = null
                        Log.e("ExitInterstitialAd", "Back Inter Load Fail: ${err.message}")
                        // Was "Back_Inter_Load_FAILED:<message>": invalid (colon,
                        // spaces, often over 40 chars) and so dropped by Firebase.
                        // no_fill / failed with the error code instead.
                        AdAnalytics.loadFailed(activity, AdAnalytics.BACK_INTERSTITIAL, err.code)
                    }
                })
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC: SHOW BACK INTER AD
    // ----------------------------------------------------------------------
    fun showExitInterstitial(activity: Activity?, adsClose: () -> Unit) {
        showBackInternal(activity, adsClose)
    }

    // ----------------------------------------------------------------------
    // INTERNAL SHOW LOGIC (BACK ADS ONLY)
    // ----------------------------------------------------------------------
    private fun showBackInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        val pref = AdPreferenceStore.getInstance(act)
        var closedOnce = false

        fun closeQuietly(reason: String) {
            if (closedOnce) return
            closedOnce = true
            act.safeLog("Closed_$reason")
            Log.e("ExitInterstitialAd", "Closed: $reason")
            try {
                adsClose()
            } catch (_: Exception) {
            }
        }
        // Basic checks
        if (!isNetworkAvailable(act)) return closeQuietly("no_network")
        if (!pref.getBoolean("IsAdsON")) return closeQuietly("ads_off")
        // Firebase "InterAds" master switch — back ads are interstitials too
        if (!pref.getBoolean("InterAds")) return closeQuietly("inter_ads_disabled")
        if (!pref.getBoolean("IsBack")) return closeQuietly("back_ads_disabled")

        // ------------------------
        // COUNTER CHECK
        // ------------------------
        val target = pref.getInt("InterBackCounter")

        if (interBackCounter != target) {
            interBackCounter++
            return closeQuietly("counter_skip")
        }
        interBackCounter = 0

        // ------------------------
        // SELECT AD TYPE
        // ------------------------
        when (AdPlacementType.fromString(pref.getString("IsAdType"))) {

            AdPlacementType.GOOGLE -> {
                showGoogleBackInter(act, pref, ::closeQuietly)
            }

            AdPlacementType.FACEBOOK -> {
                showFacebookBackInter(
                    act,
                    onDismiss = { closeQuietly("fb_back_dismiss") },
                    onFail = {
                        showCustomAfterFBFail(act, pref) {
                            closeQuietly("fb_back_fail")
                        }
                    }
                )
            }

            AdPlacementType.CUSTOM, AdPlacementType.UNKNOWN -> {
                if (pref.getBoolean("IsCustomADS"))
                    TransitionInterstitialAd.openDirectLink(act) { closeQuietly("custom_open") }
                else closeQuietly("custom_disabled")
            }

            else -> closeQuietly("invalid_type")
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE BACK INTERSTITIAL
    // ----------------------------------------------------------------------

    private fun showGoogleBackInter(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        val ad = googleInterBack
        if (ad == null) {
            return handleGoogleFail(activity, pref, closeQuietly)
        }
        activity.safeLog("google_back_inter_show_attempt")

        // Revenue: back-ad earnings were never reported before.
        if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)
        ad.setOnPaidEventListener { RevenueMonitor.reportPaidEvent(activity, it) }

        // `screen` on these is the screen Back was pressed on.
        val b = AdAnalytics.BACK_INTERSTITIAL
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterBAckShow = true
                AdAnalytics.show(activity, b)
            }

            override fun onAdImpression() {
                AdAnalytics.impression(activity, b)
            }

            override fun onAdClicked() {
                AdAnalytics.clicked(activity, b)
            }

            override fun onAdDismissedFullScreenContent() {
                googleInterBack = null
                isInterBAckShow = false
                AdAnalytics.close(activity, b)
                closeQuietly("Google_Dismiss")
                loadExitInterstitials(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterBack = null
                AdAnalytics.showFailed(activity, b, error.code)
                handleGoogleFail(activity, pref, closeQuietly)
                loadExitInterstitials(activity)
            }
        }

        try {
            ad.show(activity)
        } catch (e: Exception) {
            googleInterBack = null
            handleGoogleFail(activity, pref, closeQuietly)
            loadExitInterstitials(activity)
        }
    }

    // ----------------------------------------------------------------------
    // GOOGLE FAIL → FB or CUSTOM
    // ----------------------------------------------------------------------
    private fun handleGoogleFail(
        activity: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        if (pref.getBoolean("IsFail_FB")) {
            showFacebookBackInter(
                activity,
                onDismiss = { closeQuietly("fb_dismiss") },
                onFail = { showCustomAfterFBFail(activity, pref, closeQuietly) }
            )

        } else {
            if (pref.getBoolean("IsCustomADS")) {
                TransitionInterstitialAd.openDirectLink(activity) { closeQuietly("google_fail_custom") }
            } else closeQuietly("google_fail_no_fb_no_custom")
        }
    }

    // ----------------------------------------------------------------------
    // FACEBOOK — BACK ADS
    // ----------------------------------------------------------------------
    private fun showFacebookBackInter(
        context: Context,
        onDismiss: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = AdPreferenceStore.getInstance(context)
        val isLoader = pref.getBoolean("isLoaderForFB")
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        val fb = com.facebook.ads.InterstitialAd(context, fbId)
        val b = AdAnalytics.BACK_INTERSTITIAL
        val meta = AdAnalytics.FACEBOOK

        // ⬅ FULLSCREEN LOADER (only if Activity)
        if (context is Activity) InterstitialAdCache.show(context, isLoader)

        AdAnalytics.req(context, b, network = meta)
        fb.loadAd(
            fb.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        AdAnalytics.loaded(context, b, network = meta)
                        if (context is Activity) InterstitialAdCache.hide()
                        try {
                            fb.show()
                        } catch (e: Exception) {
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, err: com.facebook.ads.AdError?) {
                        AdAnalytics.loadFailed(context, b, err?.errorCode ?: -1, network = meta)
                        if (context is Activity) InterstitialAdCache.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        AdAnalytics.close(context, b, network = meta)
                        if (context is Activity) InterstitialAdCache.hide()
                        onDismiss()
                    }

                    override fun onLoggingImpression(ad: Ad?) {
                        AdAnalytics.impression(context, b, network = meta)
                    }
                    override fun onInterstitialDisplayed(ad: Ad?) {
                        AdAnalytics.show(context, b, network = meta)
                        if (context is Activity) InterstitialAdCache.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {
                        AdAnalytics.clicked(context, b, network = meta)
                    }

                }).build()
        )
    }

    private fun showCustomAfterFBFail(
        context: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        if (pref.getBoolean("IsCustomADS"))
            TransitionInterstitialAd.openDirectLink(context) { closeQuietly("fb_fail_custom") }
        else closeQuietly("fb_fail_no_custom")
    }

//    private var customTabsClient: CustomTabsClient? = null
//    private var customTabsSession: CustomTabsSession? = null
//    private var serviceConnection: CustomTabsServiceConnection? = null
//    // ----------------------------------------------------------------------
//    // OPEN CUSTOM URL
//    // ----------------------------------------------------------------------
//    // ----------------------------------------------------------------------
//    // OPEN CUSTOM DIRECT LINK
//    // ----------------------------------------------------------------------
//    private fun openDirectLink(context: Activity, onClosed: () -> Unit) {
//        val url = AdPreferenceStore.getInstance(context).getString("DirectLink")
//
//        if (url.isNullOrEmpty()) {
//            onClosed()
//            return
//        }
//
//        val uri = Uri.parse(url)
//        isOpened = true
//        onTabClosed = onClosed
//
//        getSession(context) { session ->
//
//            val customTab = CustomTabsIntent.Builder(session)
//                .setShowTitle(true)
//                .setToolbarColor(ContextCompat.getColor(context, R.color.black))
//                .build()
//
//            try {
//                customTab.launchUrl(context, uri)
//            } catch (e: Exception) {
//                openInBrowser(context, uri, onClosed)
//            }
//        }
//    }
//
//    // Use CustomTabsSession to track tab close
//    private fun getSession(
//        context: Context,
//        onReady: (CustomTabsSession?) -> Unit
//    ) {
//        if (customTabsSession != null) {
//            onReady(customTabsSession)
//            return
//        }
//
//        serviceConnection = object : CustomTabsServiceConnection() {
//
//            override fun onCustomTabsServiceConnected(
//                name: ComponentName,
//                client: CustomTabsClient
//            ) {
//                customTabsClient = client
//                customTabsSession = client.newSession(null)
//                onReady(customTabsSession)
//            }
//
//            override fun onServiceDisconnected(name: ComponentName) {
//                customTabsClient = null
//                customTabsSession = null
//                onReady(null)
//            }
//        }
//
//        CustomTabsClient.bindCustomTabsService(
//            context,
//            "com.android.chrome",
//            serviceConnection as CustomTabsServiceConnection
//        )
//    }
//
//    private fun openInBrowser(
//        context: Activity,
//        uri: Uri,
//        onClosed: () -> Unit
//    ) {
//        try {
//            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
//                addCategory(Intent.CATEGORY_BROWSABLE)
//            }
//            context.startActivity(intent)
//            context.safeLog("browser_opened")
//        } catch (e: Exception) {
//            context.safeLog("browser_open_failed")
//        } finally {
//            // Ensure callback is always called
//            isOpened = false
//            onTabClosed?.invoke()
//            onTabClosed = null
//            onClosed()
//        }
//    }

    
    private fun Context.safeLog(event: String) {
        try {
            this.recordEvent(event)
        } catch (_: Exception) {
        }
    }

    /** `back_load_skip` with why the back interstitial was not loaded. */
    private fun Context.logSkip(reason: String) {
        runCatching { recordEvent("back_load_skip", mapOf("reason" to reason)) }
    }

}
