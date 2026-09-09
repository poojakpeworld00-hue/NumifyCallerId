package com.numify.callerid.lookup.feature.splash

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import androidx.core.animation.doOnEnd
import androidx.core.view.updateLayoutParams
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.lighthouse.push.LightHouse
import io.lighthouse.push.extended.LightHouseRichPush
import com.numify.callerid.monetize.model.ResultCallback
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.showAppRedirectPopup
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.databinding.ActivitySplashBinding
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.common.DesignEasing
import com.numify.callerid.lookup.common.openActivity
import java.security.MessageDigest

/**
 * Entry point. Shows branding briefly, then routes to the correct screen
 * based on first-run / onboarding state.
 */
class SplashActivity : BaseActivity<ActivitySplashBinding>() {

    override val layoutId: Int = R.layout.activity_splash
    private val handler = Handler(Looper.getMainLooper())

    /** Running splash animators, cancelled in onDestroy so nothing leaks. */
    private val splashAnimators = mutableListOf<Animator>()

    private val prefs by lazy { SettingsRepository(this) }

    // Ensures we navigate exactly once, whether the win comes from getData's
    // callback or the watchdog below.
    private val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)

    // Navigation waits on BOTH gates: the getData chain must be ready AND the
    // intro animation must have played long enough. maybeProceed() fires only
    // when both are set, so a fast getData never cuts the animation short.
    private val dataReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val animMinElapsed = java.util.concurrent.atomic.AtomicBoolean(false)

    private companion object {
        // Shared with AdAwareActivity's tracing. Filter: `adb logcat -s SplashFlow`
        const val SPLASH_FLOW_TAG = "SplashFlow"

        // Minimum time the intro animation is allowed to play before we navigate,
        // even when getData is ready sooner.
        //
        // This is the frame the tagline finishes rising — the last thing the
        // composition does. Nothing is added after it: the footer and its loader
        // are on screen from the very first frame, so no part of the splash is
        // waiting on them, and once the wordmark and tagline have landed there is
        // nothing left to see. Everything before it is the design's own timeline
        // playing out: cards at 0.1s, the flip at 2.4s, the wordmark at 3.2s.
        const val ANIM_MIN_MS = SplashTimeline.TAGLINE_START +
                SplashTimeline.TAGLINE_RISE_DURATION

        // Shorter floor when the user has animations disabled — the scene is shown
        // as a static final frame, so there's nothing to wait for.
        const val ANIM_MIN_REDUCED_MS = 700L

        // Hard upper bound on how long the splash may wait on the getData chain
        // (consent → remote config → geo → permissions). If no callback fires by
        // then, force navigation so the splash can never hang forever. Tune as
        // needed: lower = snappier worst case, but a slow network may skip the
        // runtime permission prompt for that session (requested next launch).
        const val WATCHDOG_TIMEOUT_MS = 20_000L

        // The design frame's content box is 396 units wide. Distances authored
        // against it are converted through this, so a "16 unit" rise is the same
        // fraction of the screen here as it is in the design.
        const val DESIGN_CONTENT_WIDTH = 396f

        // translateY start values for the two reveal lines, in design units.
        const val WORDMARK_RISE_UNITS = 16f
        const val TAGLINE_RISE_UNITS = 12f

    }

    override fun initView() {

        // Rich-push cold start: if this launch came from a push routed via the
        // launcher, hand off to the rich-push activity and skip the splash flow.
        if (LightHouseRichPush.handleFromSplash(this, binding.root)) return

        // One session = one cold start. Bump before nextScreen() so the intro
        // `app_launches` frequency counts this launch.
        prefs.appLaunchCount = prefs.appLaunchCount + 1

        // The splash is one fixed deep-blue surface in both themes, so the bars are
        // handed straight through to the gradient and their icons stay light
        // whatever the app theme is — light icons over #2E5FE8 is what the design's
        // own device frame draws.
        //
        // setDecorFitsSystemWindows(false) is what actually makes that work below
        // API 35, where edge-to-edge is not yet the default: without it the window
        // stops at the status bar, and a transparent bar colour just exposes the
        // theme's near-white window background above the gradient.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Otherwise the platform paints its own translucent scrim behind the
            // gesture bar, which reads as a band across the bottom of the gradient.
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Edge-to-edge (default on Android 15+). Only splashContent is inset, which
        // makes it the on-device stand-in for the design's 396x812 content box and
        // keeps the wordmark and footer clear of the status bar and the Android 16
        // gesture pill. The gradient still draws full-bleed underneath, and the
        // backdrop is handed the same insets so its two discs stay anchored to the
        // content box rather than riding up behind the status bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.splashContent.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            binding.splashBackdrop.setContentInsets(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Footer build stamp — name and code both come from the build, so what the
        // splash shows is always the APK the user is actually running.
        binding.textSecureVersion.text = getString(
            R.string.splash_secure_version,
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )

        // Play the redesigned splash animation, then let it finish before we move
        // on: the animation-min gate below (animMinElapsed) holds navigation until
        // the intro has played, and getData() supplies the second gate.
        setupSplashAnimation()
        val animMin = if (animationsDisabled()) ANIM_MIN_REDUCED_MS else ANIM_MIN_MS
        handler.postDelayed({
            Log.d(SPLASH_FLOW_TAG, "animation min elapsed (${animMin}ms) → maybeProceed")
            animMinElapsed.set(true)
            maybeProceed()
        }, animMin)

        printHashKey(this)
        Log.d(SPLASH_FLOW_TAG, "SplashActivity.initView → getData()")
        // Hand off to the ad module. It runs consent + SDK init + runtime
        // permission prompts + native/banner/interstitial preloads, then
        // fires one of the two callbacks below when it's time to move on.
        getData(this, true, object : ResultCallback {
            override fun onSuccess() {
                Log.d(SPLASH_FLOW_TAG, "getData onSuccess → dataReady")
                AppOpenAdManager.loadAd(this@SplashActivity)
                dataReady.set(true)
                maybeProceed()
            }

            override fun onError() {
                Log.d(SPLASH_FLOW_TAG, "getData onError → dataReady")
                dataReady.set(true)
                maybeProceed()
            }
        })

//        // Watchdog: if the getData chain never calls back (a hung native step),
//        // force the splash forward so it can't stall indefinitely.
//        handler.postDelayed({
//            if (!proceeded.get()) {
//                Log.w(SPLASH_FLOW_TAG, "watchdog fired after ${WATCHDOG_TIMEOUT_MS}ms — forcing navigation")
//                proceedNow()
//            }
//        }, WATCHDOG_TIMEOUT_MS)
    }

    /** Navigate only once BOTH gates are met: data ready AND the intro has played. */
    private fun maybeProceed() {
        if (dataReady.get() && animMinElapsed.get()) {
            Log.d(SPLASH_FLOW_TAG, "both gates met (data + animation) → proceedNow")
            proceedNow()
        }
    }

    private fun proceedNow() {
        // Win exactly once — callback or watchdog, whichever lands first.
        if (!proceeded.compareAndSet(false, true)) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow ignored (already proceeded)")
            return
        }
        handler.removeCallbacksAndMessages(null) // cancel the watchdog
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow skipped (finishing/destroyed)")
            return
        }
        val redirectLink = AdPreferenceStore.getInstance(this).getString("In_App_Update_Link")
        if (!redirectLink.isNullOrEmpty()) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → showAppRedirectPopup")
            // After the redirect popup is dismissed, fade out and launch the next
            // screen. The fade waits for the popup rather than running under it.
            showAppRedirectPopup { playExitTransition { launchNext() } }
        } else {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → launchNext")
            playExitTransition { launchNext() }
        }
    }

    private fun launchNext() {
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "launchNext skipped (finishing/destroyed)")
            return
        }
        Log.d(SPLASH_FLOW_TAG, "launchNext → ensureDataDisclosure")
        // The LightHouse data-disclosure screen briefly sends the app
        // background→foreground. That foreground event reaches
        // AppOpenAdManager (via NumifyApplication.handleAppForeground) and,
        // because we've navigated off the excluded SplashActivity by then, would
        // pop the returning-app App Open ad right after the disclosure — in the
        // middle of the first-launch flow. Suppress it for this one trip (the
        // splash's own splash-ad path already handles any intended splash ad).
        AppOpenAdManager.skipNextAppOpenAd = true
        LightHouse.ensureDataDisclosure(this) {
            if (isFinishing || isDestroyed) return@ensureDataDisclosure
            LightHouse.subscribeAsync()
            val next = nextScreen()
            Log.d(SPLASH_FLOW_TAG, "ensureDataDisclosure done → launching ${next.simpleName}")
            // Splash → onboarding/main — no interstitial on the very first launch.
            openActivity(Intent(this, next), isShowAd = false)
            finish()
        }
    }

    // ─────────────────────────── Splash animation (UI only) ───────────────────────────
    // Design: Numify Splash Redesign, "Call card stack". Three call cards fly in
    // from off-screen on an overshoot and settle into a fanned stack; the top one
    // then turns over to a Verified face; the wordmark and tagline rise in beneath
    // it; and the ad disclosure, loader pill and build stamp fade up at the foot of
    // the screen while the loader fills.
    //
    // Every delay, duration and curve below is read from SplashTimeline and
    // DesignEasing, which are transcriptions of the design's own scene list and
    // easing table rather than lookalikes. Nothing here drives navigation — that
    // stays with getData() and the two gates above.

    private fun alive() = !isFinishing && !isDestroyed

    /** True when the user has turned system animations off ("Remove animations"). */
    private fun animationsDisabled() = Settings.Global.getFloat(
        contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
    ) == 0f

    private fun setupSplashAnimation() {
        if (animationsDisabled()) {
            // Accessibility / "remove animations": jump straight to the final frame.
            showSettledFrame()
            return
        }

        binding.splashBackdrop.alpha = 0f
        binding.textAppName.alpha = 0f
        binding.textAppName.translationY = designPx(WORDMARK_RISE_UNITS)
        binding.textTagline.alpha = 0f
        binding.textTagline.translationY = designPx(TAGLINE_RISE_UNITS)
        setLoaderWidth(0)

        // 0–500 · the gradient blooms in underneath everything else.
        start(ObjectAnimator.ofFloat(binding.splashBackdrop, View.ALPHA, 0f, 1f).apply {
            duration = SplashTimeline.BACKDROP_FADE_DURATION
            interpolator = DesignEasing.easeInOutCubic
        })

        // 100–1060 · the three cards fly in and land, then 2400–2950 · the top one
        // flips. The stack builds that itself: the cards are drawn rather than laid
        // out, so their stagger belongs with the geometry, not here.
        start(binding.cardStack.buildIntroAnimator())

        // 3200 · the wordmark rises 16 design units as it fades in…
        start(riseIn(
            view = binding.textAppName,
            delay = SplashTimeline.WORDMARK_START,
            riseDuration = SplashTimeline.WORDMARK_RISE_DURATION,
            fadeDuration = SplashTimeline.WORDMARK_FADE_DURATION,
            riseUnits = WORDMARK_RISE_UNITS,
        ))
        // …and 3350 · the tagline follows it up, 12 units and a beat behind.
        start(riseIn(
            view = binding.textTagline,
            delay = SplashTimeline.TAGLINE_START,
            riseDuration = SplashTimeline.TAGLINE_RISE_DURATION,
            fadeDuration = SplashTimeline.TAGLINE_FADE_DURATION,
            riseUnits = TAGLINE_RISE_UNITS,
        ))

        // The footer needs no entrance: the disclosure, loader and build stamp are
        // up from the first frame and stay up. See SplashTimeline.FOOTER_START.

        // …and the loader starts sweeping with them, and keeps sweeping.
        startLoaderFill()
    }

    /** The scene as it looks once every entrance has finished — used when the user
     *  has animations turned off, so they still get the composed screen. */
    private fun showSettledFrame() {
        binding.splashBackdrop.alpha = 1f
        binding.cardStack.showSettledFrame()
        binding.textAppName.alpha = 1f
        binding.textAppName.translationY = 0f
        binding.textTagline.alpha = 1f
        binding.textTagline.translationY = 0f
        binding.loaderTrack.post {
            if (alive()) setLoaderWidth(binding.loaderTrack.width)
        }
    }

    /**
     * The design's Exit scene, spent as the hand-off to the next screen rather than
     * as the seam of a loop: the composition fades over 500ms on easeInOutCubic
     * after a 50ms beat, then [onDone] runs.
     *
     * Only the backdrop and the content fade — the root keeps its solid brand fill,
     * so the last frame before the next Activity is deep blue rather than a flash
     * of the window background.
     */
    private fun playExitTransition(onDone: () -> Unit) {
        if (animationsDisabled()) {
            onDone()
            return
        }
        var handedOff = false
        val handOff = {
            if (!handedOff) {
                handedOff = true
                if (alive()) onDone()
            }
        }
        start(AnimatorSet().apply {
            startDelay = SplashTimeline.EXIT_DELAY
            duration = SplashTimeline.EXIT_DURATION
            interpolator = DesignEasing.easeInOutCubic
            playTogether(
                ObjectAnimator.ofFloat(binding.splashBackdrop, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(binding.splashContent, View.ALPHA, 1f, 0f),
            )
            // doOnEnd also fires on cancel, and onDestroy cancels this — the latch
            // plus the alive() check keep that from launching a dead Activity.
            doOnEnd { handOff() }
        })
    }

    /** Starts [anim] and tracks it so onDestroy can cancel it. */
    private fun start(anim: Animator) {
        splashAnimators += anim
        anim.start()
    }

    /**
     * Converts a distance authored against the design's 396-unit-wide content box
     * into pixels, the same way [CallCardStackView] scales its own geometry — so a
     * 16-unit rise stays the same fraction of the screen on every device instead of
     * being a fixed dp that reads differently on a 360dp phone and a tablet.
     */
    private fun designPx(units: Float): Float =
        units * (resources.displayMetrics.widthPixels / DESIGN_CONTENT_WIDTH)

    /** Text rises [riseUnits] design units into place as it fades in. The two legs
     *  have different lengths and different curves in the design, so they are built
     *  as siblings rather than as one animator with a shared duration. */
    private fun riseIn(
        view: View,
        delay: Long,
        riseDuration: Long,
        fadeDuration: Long,
        riseUnits: Float,
    ) = AnimatorSet().apply {
        startDelay = delay
        playTogether(
            ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, designPx(riseUnits), 0f).apply {
                duration = riseDuration
                interpolator = DesignEasing.easeOutCubic
            },
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                duration = fadeDuration
                interpolator = DesignEasing.easeInOutCubic
            },
        )
    }

    /**
     * Sweeps the loader pill across its track, from the first frame, on a loop.
     *
     * Width, not scaleX — scaling would stretch the drawable's rounded caps into a
     * lens. It repeats rather than filling once and stopping: the splash is up for
     * however long getData() takes, and a bar parked at 100% under a screen that
     * is still working claims something that has not happened.
     */
    private fun startLoaderFill() {
        binding.loaderTrack.post {
            if (!alive()) return@post
            val track = binding.loaderTrack.width
            if (track <= 0) return@post
            start(ValueAnimator.ofInt(0, track).apply {
                startDelay = SplashTimeline.PROGRESS_START
                duration = SplashTimeline.PROGRESS_DURATION
                interpolator = DesignEasing.easeInOutCubic
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                addUpdateListener { setLoaderWidth(it.animatedValue as Int) }
            })
        }
    }

    private fun setLoaderWidth(px: Int) {
        binding.loaderFill.updateLayoutParams { width = px }
    }

    override fun onDestroy() {
        super.onDestroy()
        splashAnimators.forEach { it.cancel() }
        splashAnimators.clear()
        handler.removeCallbacksAndMessages(null)
    }
    /**
     * Chooses the next screen in the launch flow: the first eligible entry in the
     * Remote Config `screen_order` (see [OnboardingStepConfig]), where each
     * screen's own `isEnable`, `session` and country gate decide whether it really
     * appears, or [MainShellActivity] once every entry is spent or ineligible.
     */
    private fun nextScreen(): Class<*> {
        val key = OnboardingStepConfig.firstEligible(this) ?: return MainShellActivity::class.java
        return OnboardingStepConfig.classFor(key)
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun printHashKey(context: Context) {
        try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
                info.signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            } ?: return

            signatures.forEach { sig ->
                val md = MessageDigest.getInstance("SHA")
                md.update(sig.toByteArray())
                Log.d("KeyHash", Base64.encodeToString(md.digest(), Base64.DEFAULT))
            }
        } catch (e: Exception) {
        }
    }
}
