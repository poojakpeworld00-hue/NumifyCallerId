package com.numify.callerid.lookup

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
import com.numify.callerid.monetize.model.AdPlacementType
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.AppOpenAdManager.isAdAvailable
import com.numify.callerid.monetize.delivery.engagement.EngagementHubActivity
import com.numify.callerid.lookup.permission.PermissionCoordinator
import com.numify.callerid.lookup.feature.splash.SplashActivity
import com.numify.callerid.lookup.common.WindowInsetsHelper
import io.lighthouse.push.LightHouse
import io.lighthouse.push.LightHouseConfig
import io.lighthouse.push.extended.LightHouseRichPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NumifyApplication : Application() , Application.ActivityLifecycleCallbacks,
    LifecycleObserver{
    private var currentActivity: Activity? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** Application context, set in [onCreate] — used where only a Context is needed
         *  (e.g. building the OkHttp client's Chucker interceptor). */
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        MultiDex.install(this)
        AdPreferenceStore.getInstance(this)

        // Register the splash + rich-push activities so the SDK can forward a
        // push-launched cold start from the splash (see SplashActivity.handleFromSplash).
        LightHouseRichPush.setActivities(
            splashActivity = SplashActivity::class.java,
            richPushActivity = EngagementHubActivity::class.java,
        )
        LightHouse.initialize(
            context = this,
            config = LightHouseConfig(
                apiKey = SecretDecoder.s(BuildConfig.LH_API_KEY),
                baseUrl = SecretDecoder.s(BuildConfig.LH_BASE_URL),
                richPushActivity = EngagementHubActivity::class.java,
            ),
        )
        CoroutineScope(Dispatchers.Main).launch {
            try {
                FirebaseApp.initializeApp(this@NumifyApplication)
                // Global permission engine — fetches the latest `permission_engine`
                // Remote Config so every screen can be gated dynamically. Requires
                // FirebaseApp to be initialised first (above).
                PermissionCoordinator.init(this@NumifyApplication)
            } catch (e: Exception) {
                WindowInsetsHelper.log("NumifyApplication", "LightHouse init failed: ${e.message}")
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
