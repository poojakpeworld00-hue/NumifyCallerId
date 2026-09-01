package com.callora.callerid.numberlookup.screen.rateus

import android.app.Activity
import android.content.Context
import android.util.Log
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.policy.logKeyEvent
import com.callora.callerid.numberlookup.BuildConfig
import com.callora.callerid.numberlookup.screen.gate.OnboardingFlowConfig
import com.callora.callerid.numberlookup.store.SettingsVault
import com.google.android.play.core.review.ReviewManagerFactory
import org.json.JSONObject

/**
 * The Play **In-App Review** prompt shown on Home, driven by the `rate_us`
 * Remote Config block.
 *
 * Distinct from `is_rateus`, which gates the *manual* "Rate us" row in Settings.
 * Google's policy is that the in-app flow must not be triggered by a button, so
 * that row opens the store listing and this gate owns the automatic prompt.
 *
 * **The cap counts attempts, not impressions.** Play's API is quota-limited per
 * user and deliberately reports success whether or not the sheet was actually
 * displayed — there is no callback that says "shown". So [maxShowCount] bounds
 * how often we *ask* Play, and nothing here can know how many review sheets a
 * user really saw.
 */
object RateUsGate {

    /** Ledger key in [SettingsVault], and the Remote Config key. */
    private const val KEY = "rate_us"

    /** One grep-able tag for the whole gate: `adb logcat -s RateUs`. */
    private const val TAG = "RateUs"
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class Config(
        val isEnable: Boolean,
        val promptSession: String,
        val promptInterval: Int,
        val minSessions: Int,
        val maxShowCount: Int,
        val countryCheckEnabled: Boolean,
        val excludedCountries: List<String>,
    )

    fun config(context: Context): Config {
        val obj = runCatching {
            JSONObject(AdsPrefStore.getInstance(context).getString(KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val excluded = obj.optJSONArray("screen_excluded_countries")?.let { arr ->
            (0 until arr.length()).mapNotNull {
                arr.optString(it).trim().uppercase().ifBlank { null }
            }
        } ?: emptyList()
        return Config(
            isEnable = obj.optBoolean("isEnable", false),
            promptSession = obj.optString("prompt_session", "sessions"),
            promptInterval = obj.optInt("prompt_interval", 0),
            minSessions = obj.optInt("min_sessions", 0),
            maxShowCount = obj.optInt("max_show_count", 0),
            countryCheckEnabled = obj.optBoolean("is_screenListCountryCheck", false),
            excludedCountries = excluded,
        )
    }

    /**
     * True when every gate passes. `isEnable` defaults to **false**: an absent or
     * malformed `rate_us` block must not start prompting on its own.
     *
     * Every path logs under [TAG] in debug builds. A silent gate is close to
     * untestable here: Play shows nothing on a sideloaded build even when the
     * gate passes, so without a line naming the blocker there is no way to tell
     * "we never asked" from "we asked and Play declined". `adb logcat -s RateUs`.
     */
    fun shouldPrompt(context: Context): Boolean {
        val cfg = config(context)
        val prefs = SettingsVault(context)

        fun deny(reason: String): Boolean {
            if (BuildConfig.DEBUG) Log.d(TAG, "skip — $reason  [${state(prefs, cfg)}]")
            return false
        }

        if (!cfg.isEnable) {
            return deny("isEnable=false (rate_us absent from RC, or switched off)")
        }
        if (!OnboardingFlowConfig.isCountryAllowed(
                context, cfg.countryCheckEnabled, cfg.excludedCountries
            )
        ) return deny("country excluded by ${cfg.excludedCountries}")

        rearmOnAppUpdate(prefs)

        // Google's first guideline: only ask once the user has experienced enough
        // of the app to give useful feedback. This is the floor for the FIRST
        // prompt — in `days` mode the ledger is empty on day one, so the cadence
        // alone would pass immediately.
        if (prefs.appLaunchCount < cfg.minSessions) {
            return deny("session ${prefs.appLaunchCount} < min_sessions ${cfg.minSessions}")
        }
        if (cfg.maxShowCount > 0 && prefs.introShownCount(KEY) >= cfg.maxShowCount) {
            return deny("attempt cap reached (${prefs.introShownCount(KEY)}/${cfg.maxShowCount})")
        }
        if (!cadencePasses(prefs, cfg)) {
            return deny("cadence '${cfg.promptSession}'/${cfg.promptInterval} not due")
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "PROMPT — all gates passed  [${state(prefs, cfg)}]")
        return true
    }

    /** One-line snapshot appended to every decision, so a log line stands alone. */
    private fun state(prefs: SettingsVault, cfg: Config): String =
        "session=${prefs.appLaunchCount} attempts=${prefs.introShownCount(KEY)}/${cfg.maxShowCount} " +
            "mode=${cfg.promptSession}/${cfg.promptInterval} minSessions=${cfg.minSessions} " +
            "lastMs=${prefs.introLastShownMs(KEY)}"

    /**
     * Unknown modes return **false**, not true.
     *
     * `OnboardingFlowConfig.sessionGatePasses()` ends in `else -> true`, which
     * makes a typo — or a plausible-looking `"never"` — mean "every session".
     * A prompt that fires because someone misspelled a config value is worse
     * than one that never fires, so this fails closed.
     */
    private fun cadencePasses(prefs: SettingsVault, cfg: Config): Boolean =
        when (cfg.promptSession.trim().lowercase()) {
            "always" -> true
            "once" -> prefs.introShownCount(KEY) == 0
            // `> 0` matters: 0 % N == 0, so a zero launch counter would otherwise
            // pass and prompt on a cold start that never went through Splash.
            "sessions" -> cfg.promptInterval > 0 &&
                prefs.appLaunchCount > 0 &&
                prefs.appLaunchCount % cfg.promptInterval == 0
            "days" -> {
                val last = prefs.introLastShownMs(KEY)
                cfg.promptInterval > 0 &&
                    (last == 0L || (System.currentTimeMillis() - last) / DAY_MS >= cfg.promptInterval)
            }
            else -> false
        }

    /**
     * Re-arms the lifetime cap on every new `versionCode`. Without this a user who
     * declines through the cap is never asked again — not a year and five releases
     * later — because the count only ever grows.
     */
    private fun rearmOnAppUpdate(prefs: SettingsVault) {
        val current = BuildConfig.VERSION_CODE
        if (prefs.rateUsArmedVersion == current) return
        // First run after install/update. Don't wipe a count recorded by THIS
        // version (armedVersion == -1 on a fresh install, where the count is 0).
        if (prefs.rateUsArmedVersion != -1) prefs.resetIntroShownCount(KEY)
        prefs.rateUsArmedVersion = current
    }

    /**
     * Requests and launches the review flow. [onDone] always runs exactly once,
     * on the main thread, whether the flow showed, failed, or was skipped — the
     * caller uses it to continue whatever it was sequencing.
     *
     * The ledger is stamped when the flow is *launched*, since that is the only
     * thing Play tells us.
     */
    fun launch(activity: Activity, onDone: (() -> Unit)? = null) {
        val finish = { onDone?.invoke(); Unit }
        if (activity.isFinishing || activity.isDestroyed) {
            finish(); return
        }
        runCatching {
            val manager = ReviewManagerFactory.create(activity)
            if (BuildConfig.DEBUG) Log.d(TAG, "requestReviewFlow()…")
            manager.requestReviewFlow().addOnCompleteListener { request ->
                if (!request.isSuccessful || activity.isFinishing || activity.isDestroyed) {
                    // Routine on a sideloaded/debug build — Play refuses the request
                    // outside a Play-installed track. Not a gate problem.
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "request FAILED: ${request.exception?.message ?: "activity gone"}")
                    }
                    activity.logKeyEvent("RateUs_Request_Failed")
                    finish(); return@addOnCompleteListener
                }
                manager.launchReviewFlow(activity, request.result)
                    .addOnCompleteListener { flow ->
                        markShown(activity)
                        // Completes the same way whether the sheet appeared or Play
                        // silently no-opped on quota — there is no "was shown" signal.
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "flow completed (success=${flow.isSuccessful}) — " +
                                "attempt recorded; Play does NOT report whether the sheet showed")
                        }
                        activity.logKeyEvent("RateUs_Flow_Launched")
                        finish()
                    }
            }
        }.onFailure {
            if (BuildConfig.DEBUG) Log.d(TAG, "launch threw: ${it.message}")
            finish()
        }
    }

    /** De-dupes within a single launch, same as the onboarding screens. */
    private fun markShown(context: Context) {
        val prefs = SettingsVault(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(KEY) == session) return
        prefs.recordIntroShown(KEY, session)
    }
}
