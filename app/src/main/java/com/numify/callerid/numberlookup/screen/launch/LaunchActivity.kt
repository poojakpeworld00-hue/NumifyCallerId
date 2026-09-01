package com.numify.callerid.numberlookup.screen.launch

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import io.lighthouse.push.LightHouse
import io.lighthouse.push.extended.LightHouseRichPush
import com.numify.callerid.adkit.contract.DataCallback
import com.numify.callerid.adkit.policy.AdsPrefStore
import com.numify.callerid.adkit.runtime.OpenAdManager
import com.numify.callerid.adkit.runtime.showAppRedirectPopup
import com.numify.callerid.numberlookup.screen.HomeShellActivity
import com.numify.callerid.numberlookup.BuildConfig
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.store.SettingsVault
import com.numify.callerid.numberlookup.databinding.ScreenSplashBinding
import com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig
import com.numify.callerid.numberlookup.kit.clipToRect
import com.numify.callerid.numberlookup.kit.openActivity
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Entry point. Shows branding briefly, then routes to the correct screen
 * based on first-run / onboarding state.
 */
class LaunchActivity : CoreActivity<ScreenSplashBinding>() {

    override val layoutId: Int = R.layout.screen_splash
    private val handler = Handler(Looper.getMainLooper())

    /** Running splash animators, cancelled in onDestroy so nothing leaks. */
    private val splashAnimators = mutableListOf<Animator>()

    private val prefs by lazy { SettingsVault(this) }

    // Ensures we navigate exactly once, whether the win comes from getData's
    // callback or the watchdog below.
    private val proceeded = java.util.concurrent.atomic.AtomicBoolean(false)

    // Navigation waits on BOTH gates: the getData chain must be ready AND the
    // intro animation must have played long enough. maybeProceed() fires only
    // when both are set, so a fast getData never cuts the animation short.
    private val dataReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val animMinElapsed = java.util.concurrent.atomic.AtomicBoolean(false)

    private companion object {
        // Shared with AdHostActivity's tracing. Filter: `adb logcat -s SplashFlow`
        const val SPLASH_FLOW_TAG = "SplashFlow"

        // Minimum time the intro animation is allowed to play before we navigate,
        // even when getData is ready sooner. Covers the reveal (icon → waveform →
        // badges → wordmark; the last badge lands ~1.26s) plus a short hold. The
        // design settles at ~1.1s, so there is nothing to gain by waiting longer —
        // only the ambient bubbles/sparkles move after that.
        const val ANIM_MIN_MS = 1800L

        // Shorter floor when the user has animations disabled — the scene is shown
        // as a static final frame, so there's nothing to wait for.
        const val ANIM_MIN_REDUCED_MS = 700L

        // Hard upper bound on how long the splash may wait on the getData chain
        // (consent → remote config → geo → permissions). If no callback fires by
        // then, force navigation so the splash can never hang forever. Tune as
        // needed: lower = snappier worst case, but a slow network may skip the
        // runtime permission prompt for that session (requested next launch).
        const val WATCHDOG_TIMEOUT_MS = 20_000L

        // How long the scan band takes to cross the waveform once. Matches the
        // 2.6s loop the design previews the screen at.
        const val SWEEP_CYCLE_MS = 2600L

        // Beat the scan waits before it starts crossing, so the icon tile has
        // landed and the bars have begun springing up first.
        const val SCAN_ENTRY_MS = 180L
    }

    override fun initView() {

        // Rich-push cold start: if this launch came from a push routed via the
        // launcher, hand off to the rich-push activity and skip the splash flow.
        if (LightHouseRichPush.handleFromSplash(this, binding.root)) return

        // One session = one cold start. Bump before nextScreen() so the intro
        // `app_launches` frequency counts this launch.
        prefs.appLaunchCount = prefs.appLaunchCount + 1

        // The Scanline splash is a near-white surface in light mode and a deep
        // navy one in dark, so the system bars take the splash's own colours and
        // the icons flip with the theme (dark icons on the light backdrop).
        window.statusBarColor = ContextCompat.getColor(this, R.color.splash_sl_bg_center)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.splash_sl_bg_edge)
        val lightBars = !isNightMode()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        // Edge-to-edge (default on Android 15+): pad the root by the system-bar
        // insets so the title/footer never sit under the status or navigation bar
        // (incl. the Android 16 gesture pill). The gradient still draws full-bleed
        // because a View's background fills its padding.
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Footer build stamp — name and code both come from the build, so what the
        // splash shows is always the APK the user is actually running.
        binding.lblSecureVersion.text = getString(
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
        Log.d(SPLASH_FLOW_TAG, "LaunchActivity.initView → getData()")
        // Hand off to the ad module. It runs consent + SDK init + runtime
        // permission prompts + native/banner/interstitial preloads, then
        // fires one of the two callbacks below when it's time to move on.
        getData(this, true, object : DataCallback {
            override fun onSuccess() {
                Log.d(SPLASH_FLOW_TAG, "getData onSuccess → dataReady")
                OpenAdManager.loadAd(this@LaunchActivity)
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
        val redirectLink = AdsPrefStore.getInstance(this).getString("In_App_Update_Link")
        if (!redirectLink.isNullOrEmpty()) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → showAppRedirectPopup")
            // After the redirect popup is dismissed, launch the next screen.
            showAppRedirectPopup { launchNext() }
        } else {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → launchNext")
            launchNext()
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
        // OpenAdManager (via NumifyApplication.handleAppForeground) and,
        // because we've navigated off the excluded LaunchActivity by then, would
        // pop the returning-app App Open ad right after the disclosure — in the
        // middle of the first-launch flow. Suppress it for this one trip (the
        // splash's own splash-ad path already handles any intended splash ad).
        OpenAdManager.skipNextAppOpenAd = true
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
    // Design: Splash Redesign · direction 1a, "Scanline". The icon tile scales up
    // on the waveform while a scan band crosses left→right, lighting each bar 40ms
    // after the last; the six feature badges then pop in 120ms apart as the
    // wordmark and tagline rise. Settled at ~1.1s — after that only the ambient
    // bubbles and sparkles move, which is what keeps the screen alive while the
    // getData() chain finishes. Purely decorative: it never drives navigation.

    /** The design's easing for every entrance: `cubic-bezier(0.22, 1, 0.36, 1)`. */
    private val entranceEasing = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private val argb = ArgbEvaluator()

    /** The twelve waveform bars, left to right — the order they light up in. */
    private val waveBars by lazy {
        listOf(
            binding.bar1Vw, binding.bar2Vw, binding.bar3Vw, binding.bar4Vw,
            binding.bar5Vw, binding.bar6Vw, binding.bar7Vw, binding.bar8Vw,
            binding.bar9Vw, binding.bar10Vw, binding.bar11Vw, binding.bar12Vw,
        )
    }

    /** Feature badges in the design's pop order (block → spam → lookup →
     *  protection → contact → location), which alternates across the icon. */
    private val featureBadges by lazy {
        listOf(
            binding.badgeBlockVw, binding.badgeSpamVw, binding.badgeLookupVw,
            binding.badgeProtectionVw, binding.badgeContactVw, binding.badgeLocationVw,
        )
    }

    private val barTrackColor by lazy { ContextCompat.getColor(this, R.color.splash_sl_bar_track) }
    private val barLitColor by lazy { ContextCompat.getColor(this, R.color.splash_sl_bar_lit) }

    private fun alive() = !isFinishing && !isDestroyed

    /** True when the app is currently showing its dark theme. */
    private fun isNightMode() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

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

        // 0–260 · the app-icon tile scales up out of the waveform.
        binding.iconTileVw.scaleX = 0.72f
        binding.iconTileVw.scaleY = 0.72f
        start(AnimatorSet().apply {
            duration = 260L
            interpolator = entranceEasing
            playTogether(
                ObjectAnimator.ofFloat(binding.iconTileVw, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(binding.iconTileVw, View.SCALE_X, 0.72f, 1f),
                ObjectAnimator.ofFloat(binding.iconTileVw, View.SCALE_Y, 0.72f, 1f),
            )
        })

        // 180–1060 · the scan band crosses, and 260–1020 the bars light under it,
        // one every 40ms, so the sweep reads as the thing doing the lighting.
        startScan()
        waveBars.forEachIndexed { i, bar -> lightBar(bar, delay = 260L + i * 40L) }

        // 360–1260 · the six feature badges pop in, 120ms apart.
        featureBadges.forEachIndexed { i, badge -> popIn(badge, delay = 360L + i * 120L) }

        // 420–1040 · wordmark, then tagline, then the ad disclosure rise in.
        start(riseIn(binding.lblAppName, delay = 420L, dur = 360L))
        start(riseIn(binding.lblTagline, delay = 560L, dur = 340L))
        start(riseIn(binding.lblAdDisclosure, delay = 700L, dur = 340L))

        // 820ms on · the boot progress bar and build stamp fade in, then the bar
        // fills while getData() works.
        start(fadeIn(binding.progTrack, delay = 820L, dur = 320L))
        start(fadeIn(binding.progFill, delay = 820L, dur = 320L))
        start(riseIn(binding.secureRowVw, delay = 900L, dur = 340L))
        startProgressFill(delay = 860L)

        // 1300ms on · the wave keeps breathing like a live level meter, each bar
        // offset from the last. Without this the waveform freezes the moment it
        // has lit and the screen looks stalled for however long getData() takes.
        waveBars.forEachIndexed { i, bar -> breatheBar(bar, delay = 1300L + i * 60L) }

        // Ambient and endless: four bubbles drift (7.5–11s round trips) and two
        // sparkles twinkle (3.2–4.1s), spread across the page.
        drift(binding.bubbleLgVw, dxDp = 10f, dyDp = -22f, halfCycle = 4500L)
        drift(binding.bubbleSmVw, dxDp = -14f, dyDp = 18f, halfCycle = 3750L)
        drift(binding.bubbleMdVw, dxDp = 8f, dyDp = 16f, halfCycle = 5500L)
        drift(binding.bubbleXsVw, dxDp = -9f, dyDp = -15f, halfCycle = 4100L)
        twinkle(binding.sparkleAVw, halfCycle = 1600L, delay = 0L)
        twinkle(binding.sparkleBVw, halfCycle = 2050L, delay = 600L)
    }

    /** The scene as it looks once every entrance has finished — used when the user
     *  has animations turned off, so they still get the composed screen. */
    private fun showSettledFrame() {
        (featureBadges + listOf(
            binding.iconTileVw, binding.lblAppName, binding.lblTagline,
            binding.lblAdDisclosure, binding.progTrack, binding.progFill,
            binding.secureRowVw,
        )).forEach { it.alpha = 1f }
        waveBars.forEach { it.backgroundTintList = ColorStateList.valueOf(barTrackColor) }
        binding.progTrack.post {
            if (alive()) setFillWidth((binding.progTrack.width * 0.94f).toInt())
        }
    }

    /** Starts [anim] and tracks it so onDestroy can cancel it. */
    private fun start(anim: Animator) {
        splashAnimators += anim
        anim.start()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** One waveform bar's entrance: springs up from 28% of its height in the unlit
     *  track grey. The colour is not animated here — [startScan] owns every bar's
     *  tint so the highlight always matches where the band actually is. */
    private fun lightBar(bar: View, delay: Long) {
        bar.scaleY = 0.28f
        bar.backgroundTintList = ColorStateList.valueOf(barTrackColor)
        start(ObjectAnimator.ofFloat(bar, View.SCALE_Y, 0.28f, 1f).apply {
            startDelay = delay
            duration = 320L
            interpolator = entranceEasing
        })
    }

    /** The scan: one driver positions the band and tints every bar from the band's
     *  current head, so the lit bars are literally the ones the band is over.
     *
     *  The bars REST in the unlit track grey and only light in the band's
     *  neighbourhood — the wave stays a calm grey level-meter with a single
     *  travelling highlight, instead of latching fully brand-coloured after the
     *  first pass. Loops on the same 2.6s cycle the design previews at, so the
     *  screen keeps reading as "scanning" for as long as it is up. */
    private fun startScan() {
        val sweep = binding.sweepVw
        binding.waveRowVw.post {
            if (!alive()) return@post
            val rowWidth = binding.waveRowVw.width
            if (rowWidth <= 0) return@post

            // The wrapper sits under an ancestor with clipChildren="false", so it
            // needs the outline clip for the band to enter and leave at the wave's
            // ends rather than float out over the feature badges.
            binding.waveWrapVw.clipToRect()

            val bandWidth = sweep.width.toFloat()
            // Bar centres in row space — what the band's head is measured against.
            val centres = waveBars.map { it.left + it.width / 2f }
            // How far either side of the head a bar still picks up light.
            val reach = bandWidth / 2f

            sweep.translationX = -bandWidth
            start(ObjectAnimator.ofFloat(sweep, View.ALPHA, 0f, 1f).apply {
                startDelay = SCAN_ENTRY_MS
                duration = 240L
            })
            start(ValueAnimator.ofFloat(-bandWidth, rowWidth.toFloat()).apply {
                startDelay = SCAN_ENTRY_MS
                duration = SWEEP_CYCLE_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val x = it.animatedValue as Float
                    sweep.translationX = x
                    val head = x + reach
                    waveBars.forEachIndexed { i, bar ->
                        val lit = (1f - abs(centres[i] - head) / reach).coerceIn(0f, 1f)
                        bar.backgroundTintList = ColorStateList.valueOf(
                            argb.evaluate(lit, barTrackColor, barLitColor) as Int
                        )
                    }
                }
            })
        }
    }

    /** Endless level-meter breathe for one already-lit waveform bar. */
    private fun breatheBar(bar: View, delay: Long) {
        start(pingPong(ObjectAnimator.ofFloat(bar, View.SCALE_Y, 1f, 0.72f), 900L, delay))
    }

    /** Straight fade for a view that is already in place. */
    private fun fadeIn(v: View, delay: Long, dur: Long) =
        ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f).apply {
            startDelay = delay
            duration = dur
        }

    /** Sets the progress fill's width in px. Width — not scaleX, which would
     *  stretch the drawable's rounded caps into a lens. */
    private fun setFillWidth(px: Int) {
        binding.progFill.updateLayoutParams { width = px }
    }

    /** Grows the boot bar from its seeded nub to ~94% of the track. It stops short
     *  of full on purpose: the splash leaves when getData() resolves, not when the
     *  bar ends, and a bar that sat at 100% while the screen was still up would be
     *  claiming something that has not happened. */
    private fun startProgressFill(delay: Long) {
        binding.progTrack.post {
            if (!alive()) return@post
            val track = binding.progTrack.width
            if (track <= 0) return@post
            val from = binding.progFill.width.coerceAtLeast(dp(8f).toInt())
            val to = (track * 0.94f).toInt()
            start(ValueAnimator.ofInt(from, to).apply {
                startDelay = delay
                duration = 4200L
                interpolator = DecelerateInterpolator()
                addUpdateListener { setFillWidth(it.animatedValue as Int) }
            })
        }
    }

    /** Pop-in (scale 0.7 → 1 + fade) for one feature badge. */
    private fun popIn(v: View, delay: Long) {
        v.scaleX = 0.7f
        v.scaleY = 0.7f
        start(AnimatorSet().apply {
            startDelay = delay
            duration = 300L
            interpolator = entranceEasing
            playTogether(
                ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_X, 0.7f, 1f),
                ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.7f, 1f),
            )
        })
    }

    /** Text rises 10dp into place as it fades in. */
    private fun riseIn(v: View, delay: Long, dur: Long) = AnimatorSet().apply {
        startDelay = delay
        duration = dur
        interpolator = entranceEasing
        playTogether(
            ObjectAnimator.ofFloat(v, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, dp(10f), 0f),
        )
    }

    /** Endless slow drift for one ambient bubble. */
    private fun drift(v: View, dxDp: Float, dyDp: Float, halfCycle: Long) {
        start(pingPong(ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dp(dxDp)), halfCycle, 0L))
        start(pingPong(ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, dp(dyDp)), halfCycle, 0L))
    }

    /** Endless fade + scale pulse for one sparkle. */
    private fun twinkle(v: View, halfCycle: Long, delay: Long) {
        v.alpha = 0.25f
        v.scaleX = 0.8f
        v.scaleY = 0.8f
        start(pingPong(ObjectAnimator.ofFloat(v, View.ALPHA, 0.25f, 1f), halfCycle, delay))
        start(pingPong(ObjectAnimator.ofFloat(v, View.SCALE_X, 0.8f, 1.15f), halfCycle, delay))
        start(pingPong(ObjectAnimator.ofFloat(v, View.SCALE_Y, 0.8f, 1.15f), halfCycle, delay))
    }

    /** Runs [anim] forever, easing back and forth — one leg per [halfCycle]. */
    private fun pingPong(anim: ObjectAnimator, halfCycle: Long, delay: Long) = anim.apply {
        startDelay = delay
        duration = halfCycle
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }

    override fun onDestroy() {
        super.onDestroy()
        splashAnimators.forEach { it.cancel() }
        splashAnimators.clear()
        handler.removeCallbacksAndMessages(null)
    }
    /**
     * Picks the next screen in the launch flow: the first eligible entry in the
     * Remote Config `screen_order` (see [OnboardingFlowConfig]) — each screen's
     * own `isEnable` / `session` / country gate decides whether it actually
     * shows — or [HomeShellActivity] once every entry is exhausted/ineligible.
     */
    private fun nextScreen(): Class<*> {
        val key = OnboardingFlowConfig.firstEligible(this) ?: return HomeShellActivity::class.java
        return OnboardingFlowConfig.classFor(key)
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
