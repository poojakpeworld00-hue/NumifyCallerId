package com.callerid.numberlookup.home

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.multidex.MultiDex
import com.google.firebase.FirebaseApp
import com.callerid.numberlookup.home.monetize.billing.BillingRepository
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
import com.callerid.numberlookup.home.monetize.model.AdPlacementType
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager.isAdAvailable
import com.callerid.numberlookup.home.monetize.delivery.engagement.EngagementHubActivity
import com.callerid.numberlookup.home.permission.PermissionCoordinator
import com.callerid.numberlookup.home.feature.splash.SplashActivity
import com.callerid.numberlookup.home.common.WindowInsetsHelper
import com.callerid.numberlookup.home.monetize.strategy.RemoteConfigSync
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ContactsApplication : Application() , Application.ActivityLifecycleCallbacks,
    LifecycleObserver{
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed
         *  (e.g. building the OkHttp client's Chucker interceptor). */
        lateinit var appContext: Context
            private set

        /**
         * TEST ONLY — pins the install source LightHouse reports, skipping the Play
         * referrer entirely. `"paid"` or `"organic"`; null means detect normally.
         *
         * Attribution is only resolvable on a real install from a real referrer, so
         * without this there is no way to see the marketing side of a split config
         * from a debug build. Set it, run, and every audience gate in the app —
         * `OnMaketing`, the `marketing`/`organic` config blocks, the native-ad theme,
         * the country counters — behaves as that audience.
         *
         * **It is applied under [BuildConfig.DEBUG] only**, which is a compile-time
         * constant, so R8 removes the call from release entirely and a value left in
         * here by accident cannot reach users. That guard is the point: left live on
         * a shipped build this would force *every* install to one audience, and the
         * app would behave plausibly enough that nobody would notice.
         *
         * Same shape as [com.callerid.numberlookup.home.repository.RegionDetector]'s
         * country pin, and set back to null the same way when you are done.
         */
        private val DEBUG_FORCE_INSTALL_SOURCE: String? = null

        /**
         * TEST ONLY — pins LightHouse's bot verdict. A flagged install is reported
         * organic no matter what the referrer says, so this is how the bot-signature
         * branch gets exercised without one. Null means classify normally.
         *
         * Release-stripped exactly as above.
         */
        private val DEBUG_FORCE_FLAGGED: Boolean? = null
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        MultiDex.install(this)

        // Premium first, and before AdPreferenceStore: every ad gate in the app
        // reads `IsAdsON` through that store, and the store answers "no ads" for
        // a Premium install. Warming the cached entitlement here means a paying
        // user is never shown ads for the first moments of a launch while
        // billing connects.
        PremiumStore.init(this)
        AdPreferenceStore.getInstance(this)

        // Opens the Play connection and, on connect, re-reads what this account
        // owns. That restore is what makes a reinstall, a second device or a new
        // phone come back Premium without the user doing anything — and what
        // takes the entitlement away again after a refund or a lapsed
        // subscription. It runs every launch for exactly that reason.
        BillingRepository.getInstance(this).start()

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see SplashActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = SplashActivity::class.java,
            richPushActivity = EngagementHubActivity::class.java,
        )
        // Before initialize, so the forced verdict is in place for the very first
        // classification rather than overwriting one already taken.
        if (BuildConfig.DEBUG) {
            DEBUG_FORCE_INSTALL_SOURCE?.let { LightHouse.debugForceInstallSource(it) }
            DEBUG_FORCE_FLAGGED?.let { LightHouse.debugForceFlagged(it) }
        }
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = SecretDecoder.decode(BuildConfig.LH_API_KEY),
                baseUrl = SecretDecoder.decode(BuildConfig.LH_BASE_URL),
                richPushActivity = EngagementHubActivity::class.java,
                onRemoteConfigSync = { RemoteConfigSync.apply(this) },
            ),
        )
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@ContactsApplication)
                // Global permission engine — fetches the latest `permission_engine`
                // Remote Config so every screen can be gated dynamically. Requires
                // FirebaseApp to be initialised first (above).
                PermissionCoordinator.init(this@ContactsApplication)
            } catch (e: Exception) {
                WindowInsetsHelper.log("ContactsApplication", "LightHouse init failed: ${e.message}")
            }
        }

        registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    handleAppForeground()
                }
            }
        )

    }

    // ---------------- APP FOREGROUND ----------------
    // --------------------------------------------------
    // APP FOREGROUND HANDLER (APP OPEN AD)
    // --------------------------------------------------
    private fun handleAppForeground() {

        WindowInsetsHelper.log("AppOpen", "handleAppForeground() called")

        val activity = currentActivity
        if (activity == null) {
            WindowInsetsHelper.log("AppOpen", "❌ No RESUMED activity")
            return
        }

        WindowInsetsHelper.log("AppOpen", "Activity = ${activity::class.java.simpleName}")

        if (activity.isFinishing || activity.isDestroyed) {
            WindowInsetsHelper.log("AppOpen", "❌ Activity invalid")
            return
        }

        // Excluded screens
        if (
            activity is SplashActivity ||
            activity is EngagementHubActivity
        ) {
            WindowInsetsHelper.log("AppOpen", "⛔ Excluded screen")
            return
        }

        // One-shot skip for app-initiated returns (e.g. the overlay-permission
        // flow opens system Settings itself — that return must not be monetised).
        if (AppOpenAdManager.skipNextAppOpenAd) {
            AppOpenAdManager.skipNextAppOpenAd = false
            WindowInsetsHelper.log("AppOpen", "⛔ Skipped (app-initiated settings return)")
            return
        }

        val adType = AdPlacementType.fromString(
            AdPreferenceStore.getInstance(activity).getString("IsAdType")
        )

        WindowInsetsHelper.log(
            "AppOpen",
            "AdPlacementType=$adType | available=${isAdAvailable} | showing=${AppOpenAdManager.isShowingAd}"
        )

        if (
            adType == AdPlacementType.GOOGLE &&
            isAdAvailable &&
            !AppOpenAdManager.isShowingAd
        ) {

            activity.runWhenWindowFocused {

                if (activity.isFinishing || activity.isDestroyed) return@runWhenWindowFocused

                WindowInsetsHelper.log("AppOpen", "🚀 Showing App Open Ad")

                AppOpenAdManager.showAdIfReady(
                    activity,
                    object : AppOpenAdManager.OnShowAdCompleteListener {
                        override fun onShowAdComplete() {
                            WindowInsetsHelper.log("AppOpen", "✅ App Open Ad closed safely")
                        }
                    }
                )
            }

        } else {
            WindowInsetsHelper.log("AppOpen", "❌ Ad NOT shown (conditions failed)")
        }
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {

    }

    // --------------------------------------------------
    // ACTIVITY LIFECYCLE
    // --------------------------------------------------
    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        // NOTE: the PermissionCoordinator is no longer auto-triggered here. Trigger it
        // where you want it (e.g. a button click) with `PermissionCoordinator.check(this)`.
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    override fun onActivityPaused(p0: Activity) {

    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {

    }

    override fun onActivityStarted(p0: Activity) {

    }

    override fun onActivityStopped(p0: Activity) {

    }

    // --------------------------------------------------
    // WINDOW FOCUS SAFE EXECUTION
    // --------------------------------------------------
    private fun Activity.runWhenWindowFocused(action: () -> Unit) {
        if (hasWindowFocus()) {
            action()
        } else {
            val decorView = window.decorView
            val listener =
                object : ViewTreeObserver.OnWindowFocusChangeListener {
                    override fun onWindowFocusChanged(hasFocus: Boolean) {
                        if (hasFocus) {
                            decorView.viewTreeObserver
                                .removeOnWindowFocusChangeListener(this)
                            action()
                        }
                    }
                }
            decorView.viewTreeObserver.addOnWindowFocusChangeListener(listener)
        }
    }

}
