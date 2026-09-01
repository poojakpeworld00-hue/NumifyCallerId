package com.numify.callerid.adkit.runtime.interstitial

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.numify.callerid.adkit.contract.AdPlacementType
import com.numify.callerid.adkit.policy.DisplayCadenceManager.interBackCounter
import com.numify.callerid.adkit.policy.AdPreferenceStore
import com.numify.callerid.adkit.policy.logKeyEvent
import com.numify.callerid.adkit.runtime.hasNetworkAccess

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
    fun fetchBackInterstitials(activity: Activity) {
        val pref = AdPreferenceStore.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            activity.safeLog("BackLoad:AdsOFF")
            return
        }

        // Firebase "InterAds" master switch — back ads are interstitials too
        if (!pref.getBoolean("InterAds")) {
            activity.safeLog("BackLoad:InterAdsDisabled")
            return
        }

        if (!pref.getBoolean("IsBack")) {
            activity.safeLog("BackLoad:BackAdsOFF")
            return
        }

        val id = pref.getString("googleBackInter") ?: return
        val req = AdRequest.Builder().build()

        if (AdPlacementType.fromString(pref.getString("IsAdType")) == AdPlacementType.GOOGLE) {
            InterstitialAd.load(
                activity, id, req,
                object : InterstitialAdLoadCallback() {

                    override fun onAdLoaded(ad: InterstitialAd) {
                        googleInterBack = ad
                        Log.d("ExitInterstitialAd", "Back Inter Loaded")
                        activity.safeLog("Back_Inter_Loaded")
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        googleInterBack = null
                        Log.e("ExitInterstitialAd", "Back Inter Load Fail: ${err.message}")
                        activity.safeLog("Back_Inter_Load_FAILED:${err.message}")
                    }
                })
        }
    }

    // ----------------------------------------------------------------------
    // PUBLIC: SHOW BACK INTER AD
    // ----------------------------------------------------------------------
    fun presentBackInterstitial(activity: Activity?, adsClose: () -> Unit) {
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
        if (!hasNetworkAccess(act)) return closeQuietly("no_network")
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
                    TransitionInterstitialAd.launchDirectLink(act) { closeQuietly("custom_open") }
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

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterBAckShow = true
            }

            override fun onAdDismissedFullScreenContent() {
                googleInterBack = null
                isInterBAckShow = false
                closeQuietly("Google_Dismiss")
                fetchBackInterstitials(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterBack = null
                handleGoogleFail(activity, pref, closeQuietly)
                fetchBackInterstitials(activity)
            }
        }

        try {
            ad.show(activity)
        } catch (e: Exception) {
            googleInterBack = null
            handleGoogleFail(activity, pref, closeQuietly)
            fetchBackInterstitials(activity)
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
                TransitionInterstitialAd.launchDirectLink(activity) { closeQuietly("google_fail_custom") }
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

        // ⬅ FULLSCREEN LOADER (only if Activity)
        if (context is Activity) InterstitialAdCache.show(context, isLoader)

        fb.loadAd(
            fb.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {

                    override fun onAdLoaded(ad: Ad?) {
                        if (context is Activity) InterstitialAdCache.hide()
                        try {
                            fb.show()
                        } catch (e: Exception) {
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, err: com.facebook.ads.AdError?) {
                        if (context is Activity) InterstitialAdCache.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        if (context is Activity) InterstitialAdCache.hide()
                        onDismiss()
                    }

                    override fun onLoggingImpression(ad: Ad?) {}
                    override fun onInterstitialDisplayed(ad: Ad?) {
                        if (context is Activity) InterstitialAdCache.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {}

                }).build()
        )
    }

    private fun showCustomAfterFBFail(
        context: Activity,
        pref: AdPreferenceStore,
        closeQuietly: (String) -> Unit
    ) {
        if (pref.getBoolean("IsCustomADS"))
            TransitionInterstitialAd.launchDirectLink(context) { closeQuietly("fb_fail_custom") }
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
//    private fun launchDirectLink(context: Activity, onClosed: () -> Unit) {
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

    /*private fun getSession(context: Context): CustomTabsSession? {
        var tabSession: CustomTabsSession? = null
        CustomTabsClient.bindCustomTabsService(
            context, "com.android.chrome",
            object : CustomTabsServiceConnection() {
                override fun onServiceDisconnected(name: android.content.ComponentName?) {}
                override fun onCustomTabsServiceConnected(
                    name: ComponentName,
                    client: CustomTabsClient
                ) {
                    tabSession = client?.newSession(object : CustomTabsCallback() {
                        override fun onNavigationEvent(
                            navigationEvent: Int,
                            extras: android.os.Bundle?
                        ) {
                            if (navigationEvent == CustomTabsCallback.NAVIGATION_ABORTED ||
                                navigationEvent == CustomTabsCallback.TAB_HIDDEN
                            ) {
                                if (isOpened) {
                                    isOpened = false
                                    onTabClosed?.invoke()
                                }
                            }
                        }
                    })
                }
            }
        )
        return tabSession
    }
*/
    private fun Context.safeLog(event: String) {
        try {
            this.logKeyEvent(event)
        } catch (_: Exception) {
        }
    }

}
