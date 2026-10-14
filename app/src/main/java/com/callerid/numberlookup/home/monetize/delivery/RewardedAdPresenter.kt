package com.callerid.numberlookup.home.monetize.delivery

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.isVisible
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.DialogRewardRetryBinding
import com.callerid.numberlookup.home.databinding.DialogRewardWaitBinding
import com.callerid.numberlookup.home.monetize.delivery.fullpage.InterstitialAdCache
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.AdAnalytics
import com.callerid.numberlookup.home.monetize.strategy.RevenueMonitor
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.monetize.strategy.RewardedConfig
import com.callerid.numberlookup.home.monetize.strategy.RewardedCooldown
import com.callerid.numberlookup.home.monetize.strategy.recordEvent

/**
 * Preload-and-show for Google AdMob rewarded ads.
 *
 * Usage:
 *   RewardedAdPresenter.preload(activity)                   // early, when the screen opens
 *   RewardedAdPresenter().show(activity) { result -> ... }  // on the Watch Ad tap
 *
 * What a tap leads to - [Result] says whether the user may have what the ad was for:
 * 1. Ads off: EARNED straight away.
 * 2. A no-fill cooldown is running ([RewardedCooldown]): `rewarded.nofill_mode`
 *    "free" → EARNED; "wait" → the countdown ring, whose "Unlock now" asks for
 *    the ad again at zero ("Not now" → CANCELED).
 * 3. No internet and no ad loaded: EARNED - the content shows without an ad.
 * 4. Ad loaded: it plays. EARNED only if the reward was earned before it was
 *    closed, else NOT_EARNED. A failed show → DirectLink fallback (when custom
 *    ads are on), then EARNED.
 * 5. No ad: request it and wait up to [WAIT_FOR_LOAD_MS] behind a loader -
 *    - loads → it plays (step 4);
 *    - NO FILL (codes 3 / 9) → a cooldown starts → step 2;
 *    - any other error → "Ad not ready": Retry asks again, Cancel → CANCELED;
 *      after `rewarded.max_retries` failed Retries → EARNED.
 *    No rewarded unit configured → DirectLink fallback, then EARNED.
 */
class RewardedAdPresenter {

    enum class Result { EARNED, NOT_EARNED, CANCELED }

    companion object {
        private const val TAG = "RewardedAdPresenter"

        /** How long a tap waits for a load before deciding there is no ad. */
        private const val WAIT_FOR_LOAD_MS = 6_000L

        /** A load with no callback by now is treated as lost, so it cannot block loads forever. */
        private const val LOAD_STALE_MS = 30_000L

        /** Countdown ring refresh: smooth enough to drain, cheap enough to not matter. */
        private const val RING_TICK_MS = 100L

        /** How dark the screen behind the rewarded dialogs goes. */
        private const val DIM_AMOUNT = 0.6f

        /** AdMob's "no ad to serve" codes: NO_FILL and MEDIATION_NO_FILL. */
        private val NO_FILL_CODES =
            setOf(AdRequest.ERROR_CODE_NO_FILL, AdRequest.ERROR_CODE_MEDIATION_NO_FILL)

        private var loadedAd: RewardedAd? = null
        private var isLoading = false
        private var loadStartedAt = 0L

        /** Taps waiting on the in-flight load; each is told once when it settles. */
        private val waiting = mutableListOf<() -> Unit>()

        private val loadInFlight: Boolean
            get() = isLoading && System.currentTimeMillis() - loadStartedAt < LOAD_STALE_MS

        fun isReady(): Boolean = loadedAd != null

        /** Call this early (e.g. when the screen opens) to warm up the ad. */
        fun preload(context: Context) {
            val pref = AdPreferenceStore.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (loadedAd != null || loadInFlight) return
            // Asking again inside a no-fill cooldown only piles up empty requests.
            if (RewardedCooldown.isActive(context)) return

            val unitId = pref.getString("googleRewarded")
            if (unitId.isNullOrEmpty()) return

            // The application context: a load outlives the screen that asked for it.
            val app = context.applicationContext
            isLoading = true
            loadStartedAt = System.currentTimeMillis()
            Log.d(TAG, "Preloading…")
            val loadScreen = AdAnalytics.screenOf(context)
            AdAnalytics.req(app, AdAnalytics.REWARDED, loadScreen)

            RewardedAd.load(
                app,
                unitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadedAd = ad
                        isLoading = false
                        RewardedCooldown.reset(app)
                        AdAnalytics.loaded(app, AdAnalytics.REWARDED, loadScreen)
                        Log.d(TAG, "Preloaded OK")
                        flushWaiting()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadedAd = null
                        isLoading = false
                        Log.e(TAG, "Preload failed (${error.code}): ${error.message}")
                        AdAnalytics.loadFailed(app, AdAnalytics.REWARDED, error.code, loadScreen)
                        if (error.code in NO_FILL_CODES) {
                            app.recordEvent("reward_nofill")
                            RewardedCooldown.start(app)
                        } else {
                            app.recordEvent("reward_load_error_${error.code}")
                        }
                        flushWaiting()
                    }
                }
            )
        }

        private fun flushWaiting() {
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { it() }
        }

        /** Show DirectLink fallback, then invoke [onClosed] when it's done. */
        private fun showDirectLinkFallback(activity: Activity, onClosed: () -> Unit) {
            val pref = AdPreferenceStore.getInstance(activity)
            if (pref.getBoolean("IsCustomADS")) {
                Log.d(TAG, "Falling back to DirectLink")
                TransitionInterstitialAd.openDirectLink(activity) { onClosed() }
            } else {
                onClosed()
            }
        }
    }

    /** Shows the rewarded ad; [onDone] gets whether the reward counts (see the class doc). */
    fun show(activity: Activity, onDone: (Result) -> Unit) = show(activity, onDone, attempt = 0)

    /** [attempt] counts the "Ad not ready" Retries taken so far in this tap. */
    private fun show(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        val pref = AdPreferenceStore.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            onDone(Result.EARNED)
            return
        }
        if (RewardedCooldown.isActive(activity)) return onCooldown(activity, onDone)
        if (loadedAd == null && !isNetworkAvailable(activity)) {
            Log.d(TAG, "No ad, no internet — content shown without an ad")
            onDone(Result.EARNED)
            return
        }

        // Nothing loaded and nothing loading (the preload failed earlier, or a
        // cooldown just ended): ask now, then wait for it like for one in flight.
        if (loadedAd == null) preload(activity)

        // A load is running: give it a moment behind the loader instead of
        // skipping the ad the user just asked to watch.
        if (loadedAd == null && loadInFlight) {
            InterstitialAdCache.show(activity, true)
            val handler = Handler(Looper.getMainLooper())
            var settled = false
            lateinit var resume: () -> Unit
            resume = {
                if (!settled) {
                    settled = true
                    handler.removeCallbacksAndMessages(null)
                    waiting.remove(resume)
                    InterstitialAdCache.hide()
                    if (activity.isFinishing || activity.isDestroyed) onDone(Result.CANCELED)
                    else showLoaded(activity, onDone, attempt)
                }
            }
            waiting += resume
            handler.postDelayed({ resume() }, WAIT_FOR_LOAD_MS)
            return
        }
        showLoaded(activity, onDone, attempt)
    }

    private fun showLoaded(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        val ad = loadedAd
        if (ad == null) {
            val maxRetries = RewardedConfig.read(activity).maxRetries
            when {
                !isNetworkAvailable(activity) -> {
                    Log.d(TAG, "No ad, no internet — content shown without an ad")
                    onDone(Result.EARNED)
                }
                AdPreferenceStore.getInstance(activity).getString("googleRewarded").isNullOrEmpty() -> {
                    Log.d(TAG, "No rewarded unit — showing DirectLink fallback")
                    showDirectLinkFallback(activity) { onDone(Result.EARNED) }
                }
                // The load just came back empty and started a cooldown.
                RewardedCooldown.isActive(activity) -> onCooldown(activity, onDone)
                maxRetries >= 0 && attempt >= maxRetries -> {
                    Log.d(TAG, "Still no ad after $attempt Retries — content shown without an ad")
                    activity.recordEvent("reward_retry_exhausted")
                    onDone(Result.EARNED)
                }
                else -> {
                    Log.d(TAG, "Online but the ad didn't load — asking to try again")
                    askRetry(activity, onDone, attempt)
                }
            }
            return
        }

        // Consume the held reference so we don't show it twice
        loadedAd = null
        // Set by the SDK's reward callback, which arrives before the dismiss.
        var earned = false

        // Revenue: rewarded impressions were never reported before.
        if (BuildConfig.DEBUG) RevenueMonitor.logDebugRevenue(activity)
        ad.setOnPaidEventListener { RevenueMonitor.reportPaidEvent(activity, it) }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                AdAnalytics.show(activity, AdAnalytics.REWARDED)
            }

            override fun onAdImpression() {
                AdAnalytics.impression(activity, AdAnalytics.REWARDED)
            }

            override fun onAdClicked() {
                AdAnalytics.clicked(activity, AdAnalytics.REWARDED)
            }

            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Dismissed, earned=$earned")
                AdAnalytics.close(activity, AdAnalytics.REWARDED)
                AdAnalytics.rewardOutcome(activity, earned)
                preload(activity)
                onDone(if (earned) Result.EARNED else Result.NOT_EARNED)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                AdAnalytics.showFailed(activity, AdAnalytics.REWARDED, error.code)
                Log.e(TAG, "Show failed: ${error.message} — showing DirectLink fallback")
                preload(activity)
                showDirectLinkFallback(activity) { onDone(Result.EARNED) }
            }
        }

        ad.show(activity) {
            earned = true
            Log.d(TAG, "Reward earned: ${it.type} x${it.amount}")
        }
    }

    /** Inside a no-fill cooldown: unlock ("free") or show the countdown ("wait"). */
    private fun onCooldown(activity: Activity, onDone: (Result) -> Unit) {
        if (RewardedConfig.read(activity).mode == RewardedConfig.Mode.FREE) {
            Log.d(TAG, "No-fill cooldown (${RewardedCooldown.remainingMs(activity) / 1000}s left) — content shown without an ad")
            activity.recordEvent("reward_cooldown_free")
            onDone(Result.EARNED)
        } else {
            activity.recordEvent("reward_cooldown_wait")
            askWait(activity, onDone)
        }
    }

    /** A card dialog in the app's reward style: transparent window, dimmed screen behind. */
    private fun cardDialog(activity: Activity, content: android.view.View): Dialog =
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(content)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(DIM_AMOUNT)
            setCanceledOnTouchOutside(false)
        }

    /**
     * "Ad not ready - try again": Retry starts a fresh load (or joins the one in
     * flight) and goes through [show] again, which waits for it; Cancel or Back
     * ends with [Result.CANCELED].
     */
    private fun askRetry(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return onDone(Result.CANCELED)
        val view = DialogRewardRetryBinding.inflate(activity.layoutInflater)
        val dialog = cardDialog(activity, view.root)
        var retrying = false
        dialog.setOnDismissListener { if (!retrying) onDone(Result.CANCELED) }
        view.buttonRetryCancel.setOnClickListener { dialog.dismiss() }
        view.buttonRetry.setOnClickListener {
            retrying = true
            dialog.dismiss()
            show(activity, onDone, attempt + 1)
        }
        dialog.show()
    }

    /**
     * "Almost ready to unlock" (`nofill_mode` "wait"): a ring that drains with the
     * countdown in its centre. The copy never mentions ads - it keeps the user here
     * for the unlock. At zero the ring turns into a check and "Unlock now" asks for
     * the ad again; "Not now" / Back → CANCELED.
     */
    private fun askWait(activity: Activity, onDone: (Result) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return onDone(Result.CANCELED)
        val view = DialogRewardWaitBinding.inflate(activity.layoutInflater)
        val dialog = cardDialog(activity, view.root)

        val handler = Handler(Looper.getMainLooper())
        val total = RewardedCooldown.totalMs(activity).toFloat()
        var unlock = false

        fun setUnlockEnabled(on: Boolean) {
            view.buttonWaitUnlock.isEnabled = on
            view.buttonWaitUnlock.alpha = if (on) 1f else 0.45f
        }
        setUnlockEnabled(false)

        val tick = object : Runnable {
            override fun run() {
                val left = RewardedCooldown.remainingMs(activity)
                if (left > 0) {
                    view.ringWait.progress = (left / total * view.ringWait.max).toInt()
                    view.textWaitTime.text = DateUtils.formatElapsedTime((left + 999) / 1000)
                    handler.postDelayed(this, RING_TICK_MS)
                } else {
                    view.ringWait.progress = 0
                    view.textWaitTime.isVisible = false
                    view.imageWaitDone.isVisible = true
                    view.textWaitTitle.setText(R.string.reward_wait_ready_title)
                    view.textWaitBody.setText(R.string.reward_wait_ready)
                    setUnlockEnabled(true)
                }
            }
        }
        tick.run()

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (unlock) show(activity, onDone) else onDone(Result.CANCELED)
        }
        view.buttonWaitLater.setOnClickListener { dialog.dismiss() }
        view.buttonWaitUnlock.setOnClickListener {
            unlock = true
            dialog.dismiss()
        }
        dialog.show()
    }
}
