package com.numify.callerid.lookup.permission.lockscreen

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.facebook.shimmer.ShimmerFrameLayout
import com.numify.callerid.monetize.strategy.recordEvent
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.permission.PermissionCoordinator
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.onboarding.OnboardingFooterAd
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.common.WindowInsetsHelper
import com.numify.callerid.lookup.common.followAdContainer

/**
 * The Full-Screen-Intent permission Screen, reached wherever `screen_order`
 * positions `"fsi_permission"`, by way of [LockScreenPermission.shouldShowScreen].
 * It primes the FSI grant and then continues to the next eligible `screen_order`
 * entry (see [OnboardingStepConfig.nextEligibleAfter]), or to [MainShellActivity]
 * once nothing is left.
 *
 * "Enable Now" opens the system FSI page and arms [LockScreenWatchService]; when
 * the toggle flips, the broadcast reaches [LockScreenReturnWatcher], which brings
 * this Activity back, and `onResume` spots the grant and moves on. "Not now", or
 * a back press, simply continues without it.
 */
class LockScreenAlertActivity : AppCompatActivity() {

    private val config by lazy { LockScreenConfig.load(this) }
    private val returnWatcher by lazy { LockScreenReturnWatcher(this) }

    /** Launches the FSI Settings page in-task (no separate lingering task). */
    private val fsiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Returned from Settings (back press or in-task auto-return). Continue
        // here too so it never depends solely on onResume timing.
        continueIfBackFromSettings("settingsResult")
    }

    private var navigated = false

    private val autonextHandler = Handler(Looper.getMainLooper())

    // In-activity poll for the FSI grant while we sit behind the Settings page.
    //
    // The FSI Settings page is opened IN-TASK (no NEW_TASK), so this app keeps a
    // FOREGROUND TASK the whole time it is shown. A main-thread Handler keeps
    // ticking while this Activity is merely stopped (the process stays alive), and
    // the instant the grant flips we navigate on with a plain startActivity — this
    // is NOT a background-activity-start (the app owns the foreground task), so it
    // is permitted without any BAL privilege, broadcast, service, or full-screen
    // intent. This mirrors the proven house pattern (SpecialPermissionWatcher +
    // finishAfterSettings).
    //
    // (This replaces relying on LockScreenWatchService: a background Service can't even be
    // started on the way to Settings on Android 12+/16 — it throws
    // BackgroundServiceStartNotAllowedException — so its poll never ran.)
    private val grantPollHandler = Handler(Looper.getMainLooper())
    private var grantPolling = false
    private val grantPoll = object : Runnable {
        override fun run() {
            if (navigated || isDestroyed) { grantPolling = false; return }
            if (LockScreenPermission.isGranted(this@LockScreenAlertActivity)) {
                WindowInsetsHelper.log("FSI", "grant poll: GRANTED → continue to next")
                grantPolling = false
                recordEvent("FSI_Screen_Granted")
                continueToNext()
            } else {
                grantPollHandler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    /** Begin polling for the grant (idempotent). Called when we open FSI settings. */
    private fun startGrantPoll() {
        if (grantPolling) return
        grantPolling = true
        grantPollHandler.removeCallbacks(grantPoll)
        grantPollHandler.postDelayed(grantPoll, POLL_INTERVAL_MS)
    }

    private fun stopGrantPoll() {
        grantPolling = false
        grantPollHandler.removeCallbacks(grantPoll)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep any transient window on the FSI hero background, not the theme's white.
        window.setBackgroundDrawableResource(R.color.fsi_bg_edge)

        // A return from the FSI Settings page — whether this Activity is reused OR
        // freshly recreated (depends on the return intent flags) — must go straight
        // to the next screen and never render the Screen again. This is what kills
        // the "FSI screen blinks after auto-back" flash.
        if (returningFromSettings || LockScreenPermission.isGranted(this)) {
            WindowInsetsHelper.log("FSI", "Screen onCreate: returning/granted → continue (no render)")
            continueToNext()
            return
        }

        setContentView(R.layout.activity_fsi_permission)
        setupSystemBars()

        findViewById<TextView>(R.id.fsiScreenTitle).text = config.screen.title
        findViewById<TextView>(R.id.fsiScreenDesc).text = config.screen.desc
        findViewById<TextView>(R.id.fsiScreenButton).text = config.screen.button

        LockScreenPermission.markScreenShown(this)
        recordEvent("FSI_Screen_Show")
        returnWatcher.register()

        findViewById<TextView>(R.id.fsiScreenButton).setOnClickListener {
            // The user has engaged — autonext must not fire out from under them
            // while they're away on the system Settings page.
            autonextHandler.removeCallbacksAndMessages(null)
            // Ask this screen's configured permission(s) (`screen.fsi_permission.permissions`
            // — notification) FIRST, THEN open FSI.
            PermissionCoordinator.checkScreenPermissions(this, OnboardingStepConfig.FSI_PERMISSION_KEY) {
                recordEvent("FSI_Screen_Enable")
                // Hide the content NOW, so when we come back (auto-back or the user
                // pressing back) no FSI content is ever drawn — just the plain
                // background for an instant — then we continue.
                //
                // We do NOT set returningFromSettings here: this callback runs while
                // returning from the notification permission dialog, and the very
                // next onResume would then mistake that for a Settings return and
                // skip straight to the next screen. Instead we flag the pending
                // Settings launch and let onPause arm returningFromSettings once we
                // have actually left for the FSI Settings page.
                pendingFsiSettings = true
                findViewById<View>(R.id.fsiScreenRoot).visibility = View.INVISIBLE
                LockScreenPermission.openSettings(this, fsiSettingsLauncher)
                // Reliable grant detection from the Activity itself (the background
                // service can't start on the way to Settings on Android 12+/16).
                startGrantPoll()
            }
        }
        findViewById<TextView>(R.id.fsiScreenSkip).setOnClickListener {
            recordEvent("FSI_Screen_Skip")
            continueToNext()
        }

        // `screen.fsi_permission.autonext` (seconds, 0 = disabled): auto-advance
        // exactly like Skip if the user hasn't interacted by then. continueToNext
        // is guarded by `navigated`, so this is always safe to fire.
        val autonextSec = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.FSI_PERMISSION_KEY)
            ?.autonextSec ?: 0
        if (autonextSec > 0) {
            autonextHandler.postDelayed({
                recordEvent("FSI_Screen_Skip")
                continueToNext()
            }, autonextSec * 1000L)
        }

        // Onboarding rule: system back must not exit the app — skip forward to the
        // next screen (same as "Not now"). continueToNext is guarded by `navigated`,
        // so the always-enabled callback is safe to re-fire.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                recordEvent("FSI_Screen_Skip")
                continueToNext()
            }
        })

        playIntroAnimation()

        // Footer ad above the CTA — format from `screen.fsi_permission.isBottomAds*`
        // (self-gates on IsAdsON/NativeAd/network/counter inside the renderer).
        OnboardingFooterAd.render(
            activity = this,
            screenKey = OnboardingStepConfig.FSI_PERMISSION_KEY,
            container = findViewById(R.id.adNativeFrame),
            shimmer = findViewById<ShimmerFrameLayout>(R.id.adShimmer),
            divider = findViewById<View>(R.id.adNativeDivider),
        )
    }

    /**
     * Paints the system bars to match the (light or dark) background and gives them
     * the correct icon contrast, and insets the content below the status bar / above
     * the nav bar so nothing is clipped or drawn under the bars.
     */
    private fun setupSystemBars() {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val barColor = ContextCompat.getColor(this, R.color.fsi_bg_edge)
        @Suppress("DEPRECATION")
        window.statusBarColor = barColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = barColor
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        val root = findViewById<View>(R.id.fsiScreenRoot)
        val basePaddingBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, basePaddingBottom + bars.bottom)
            insets
        }
    }

    /**
     * The design's motion set: the card pops in, then floats; a glow breathes and
     * two orbit rings + the avatar rings pulse outward on loops; the copy and
     * benefits cascade up; the CTA has a gentle idle pulse.
     */
    private fun playIntroAnimation() {
        val hero = findViewById<View>(R.id.fsiHero)
        val glow = findViewById<View>(R.id.fsiGlow)
        val preview = findViewById<View>(R.id.fsiCallPreview)
        val cta = findViewById<View>(R.id.fsiScreenButton)

        // Incoming-call card: pop in (fade + rise + overshoot scale).
        preview.alpha = 0f
        preview.scaleX = 0.9f
        preview.scaleY = 0.9f
        preview.translationY = dp(18f)
        preview.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(60)
            .setInterpolator(OvershootInterpolator(1.3f))
            .setDuration(620)
            .start()

        // Continuous loops (all self-cancel via the isFinishing/isDestroyed guard).
        loopFloat(hero)
        loopGlow(glow)
        loopRing(findViewById(R.id.fsiOrbit1), 0L, 0.85f, 1.75f, 0.6f, 2600L)
        loopRing(findViewById(R.id.fsiOrbit2), 900L, 0.85f, 1.75f, 0.6f, 2600L)
        loopRing(findViewById(R.id.fsiAvatarRing1), 300L, 0.9f, 1.4f, 0.7f, 2200L)
        loopRing(findViewById(R.id.fsiAvatarRing2), 1000L, 0.9f, 1.4f, 0.7f, 2200L)
        loopCta(cta)

        // Copy + benefits: staggered cascade up.
        listOf(
            findViewById<View>(R.id.fsiScreenTitle),
            findViewById<View>(R.id.fsiScreenDesc),
            findViewById<View>(R.id.fsiScreenFeatures),
        ).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = dp(20f)
            v.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(360L + i * 90L)
                .setDuration(440)
                .start()
        }
    }

    /** Gentle up/down float (~4s cycle). */
    private fun loopFloat(v: View) {
        if (isFinishing || isDestroyed) return
        v.animate().translationY(-dp(8f))
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .setDuration(2000)
            .withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                v.animate().translationY(0f)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                    .setDuration(2000)
                    .withEndAction { loopFloat(v) }
                    .start()
            }.start()
    }

    /** Ambient glow alpha breathe. */
    private fun loopGlow(v: View) {
        if (isFinishing || isDestroyed) return
        v.alpha = 0.55f
        v.animate().alpha(0.9f).setDuration(1300)
            .withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                v.animate().alpha(0.55f).setDuration(1300)
                    .withEndAction { loopGlow(v) }.start()
            }.start()
    }

    /** Expanding ring pulse (scale up + fade out), repeating; [delay] staggers pairs. */
    private fun loopRing(v: View?, delay: Long, from: Float, to: Float, alpha: Float, dur: Long) {
        v ?: return
        v.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            fun cycle() {
                if (isFinishing || isDestroyed) return
                v.scaleX = from; v.scaleY = from; v.alpha = alpha
                v.animate().scaleX(to).scaleY(to).alpha(0f)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .setDuration(dur)
                    .withEndAction { cycle() }
                    .start()
            }
            cycle()
        }, delay)
    }

    /** Subtle idle pulse on the CTA to pull the tap. */
    private fun loopCta(v: View) {
        if (isFinishing || isDestroyed) return
        v.animate().scaleX(1.015f).scaleY(1.03f).setDuration(1300)
            .withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                v.animate().scaleX(1f).scaleY(1f).setDuration(1300)
                    .withEndAction { loopCta(v) }.start()
            }.start()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    override fun onResume() {
        super.onResume()
        LockScreenPermission.stopWatch(this)
        continueIfBackFromSettings("onResume")
    }

    /**
     * The return-watcher may re-front THIS existing instance (SINGLE_TOP) instead
     * of recreating it — that path lands in onNewIntent, not onCreate. Handle the
     * continue here too so a reused instance never gets stuck on the (hidden)
     * screen after the grant.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        continueIfBackFromSettings("onNewIntent")
    }

    /**
     * Moves on to the next screen once we have genuinely returned from the FSI
     * Settings page, whether because the user granted it
     * ([LockScreenPermission.isGranted]) or came back without granting
     * ([returningFromSettings], armed in onPause). Every re-entry path calls it -
     * onResume, onNewIntent and the settings launcher result - so the outcome
     * never hinges on which one the OS chooses to deliver. The auto-return is
     * best-effort, since an FSI grant confers no background-activity-start
     * privilege, and this redundancy is what makes it dependable.
     *
     * It pointedly does not fire on the earlier notification-permission dialog
     * return: at that point FSI is still ungranted and returningFromSettings has
     * not been armed, because onPause only arms it once we truly leave for Settings.
     */
    private fun continueIfBackFromSettings(where: String) {
        if (navigated) return
        val granted = LockScreenPermission.isGranted(this)
        if (returningFromSettings || granted) {
            WindowInsetsHelper.log("FSI", "Screen $where: back from settings, granted=$granted → continue")
            if (granted) recordEvent("FSI_Screen_Granted")
            continueToNext()
        }
    }

    override fun onPause() {
        super.onPause()
        // Arm the Settings round-trip only now — i.e. once we are genuinely
        // leaving the screen for the FSI Settings page (opened just before). This
        // is what keeps the earlier notification-permission-dialog return from
        // being mistaken for a Settings return in onResume.
        if (pendingFsiSettings) {
            pendingFsiSettings = false
            returningFromSettings = true
        }
    }

    override fun onDestroy() {
        stopGrantPoll()
        autonextHandler.removeCallbacksAndMessages(null)
        returnWatcher.unregister()
        LockScreenPermission.stopWatch(this)
        super.onDestroy()
    }

    private fun continueToNext() {
        if (navigated) return
        navigated = true
        stopGrantPoll()
        returningFromSettings = false
        // Recomputed fresh from screen_order every time (works the same whether
        // this instance was reused or recreated across the Settings round-trip —
        // no cross-recreation state needed, unlike an intent-carried "next" class).
        val nextKey = OnboardingStepConfig.nextEligibleAfter(this, OnboardingStepConfig.FSI_PERMISSION_KEY)
        val nextClass = nextKey?.let { OnboardingStepConfig.classFor(it) } ?: MainShellActivity::class.java
        WindowInsetsHelper.log("FSI", "Screen continueToNext → ${nextClass.simpleName}")
        // NEW_TASK | CLEAR_TASK: a terminal hop that clears the onboarding task
        // (including the in-task Settings page still on top when the grant is
        // detected mid-poll), so nothing stale is left behind on Back. Matches the
        // house finishAfterSettings/goToHome pattern.
        startActivity(
            Intent(this, nextClass).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        finish()
    }

    companion object {
        /** Grant-poll cadence while the user is on the FSI Settings page. */
        private const val POLL_INTERVAL_MS = 350L

        // Process-level state: survives Activity recreation during the Settings
        // round-trip (the return may reuse OR recreate this Activity).
        @Volatile
        private var returningFromSettings = false

        // Set when the FSI Settings launch is requested (after the notification
        // prompt) and cleared in onPause, where it promotes to
        // [returningFromSettings]. Keeps the notification-dialog return from
        // prematurely triggering the "returned from Settings" continue.
        @Volatile
        private var pendingFsiSettings = false
    }
}
