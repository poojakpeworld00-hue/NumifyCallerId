package com.numify.callerid.lookup.permission.lockscreen

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.TelephonyManager
import androidx.activity.result.ActivityResultLauncher
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.common.WindowInsetsHelper
import java.util.Locale

/**
 * All gating + mechanics for the Firebase-controlled Full-Screen-Intent flow.
 *
 * The flow runs only when EVERY condition holds — the five from the spec:
 *  1. `Build.SDK_INT >= config.minSdk` (Android 14 / API 34),
 *  2. the FSI permission is NOT granted,
 *  3. the global feature is enabled ([LockScreenConfig.enabled]),
 *  4. the user's country is allowed ([isCountryAllowed]),
 *  5. the specific surface (Screen or Dialog) is enabled.
 *
 * Nothing here is hardcoded — [LockScreenConfig] supplies every value from Remote Config.
 * The Screen appears once after Language; the Dialog appears in MainShellActivity,
 * rate-limited by `show_after_days` + `max_show_count`. Once the permission is
 * granted, neither ever shows again.
 */
object LockScreenPermission {

    // One tag for the whole flow → filter with:  adb logcat -s FSI
    private const val TAG = "FSI"
    private const val ACTION_MANAGE = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

    /** Broadcast fired by [LockScreenWatchService] when the FSI toggle flips on. */
    const val ACTION_FSI_GRANTED =
        "com.numify.callerid.lookup.action.FSI_GRANTED"

    // --- Grant state ---

    /** Android 14+ grant check. Below 34 the permission doesn't exist → treated as usable. */
    fun isGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.canUseFullScreenIntent()
        }.getOrDefault(false)
    }

    // --- CountryItem gate (reuses the app's existing IP + SIM country signals) ---

    /**
     * True when the device's country is NOT in the config's block-list. Fail-open:
     * when the filter is off, the list is empty, or the country can't be resolved,
     * the feature is shown. Matches the IP country ([AdPreferenceStore.userCountry]),
     * the SIM/network ISO, and the locale country against the excluded list.
     */
    fun isCountryAllowed(context: Context, config: LockScreenConfig): Boolean {
        if (!config.countryFilterEnabled || config.excludedCountries.isEmpty()) {
            WindowInsetsHelper.log(TAG, "country: ALLOWED (filter off or empty list)")
            return true
        }
        val excluded = config.excludedCountries
        val signals = deviceCountrySignals(context)
        if (signals.isEmpty()) {
            WindowInsetsHelper.log(TAG, "country: ALLOWED (no country signal → fail-open)")
            return true
        }
        val blocked = signals.any { sig -> excluded.any { it.equals(sig, ignoreCase = true) } }
        WindowInsetsHelper.log(TAG, "country: ${if (blocked) "BLOCKED" else "ALLOWED"} [signals=$signals, excluded=$excluded]")
        return !blocked
    }

    /** Best-effort country signals: IP country, SIM ISO, network ISO, locale — no permission needed. */
    private fun deviceCountrySignals(context: Context): List<String> {
        val out = mutableListOf<String>()
        AdPreferenceStore.getInstance(context).userCountry.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.trim() }
            tm?.networkCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        }
        Locale.getDefault().country.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        return out.distinct()
    }

    // --- Master gate ---

    /** The five spec conditions, minus the per-surface switch (checked by callers). */
    fun canRun(context: Context, config: LockScreenConfig): Boolean {
        val sdkOk = Build.VERSION.SDK_INT >= config.minSdk
        val granted = isGranted(context)
        val countryOk = isCountryAllowed(context, config)
        val result = config.enabled && sdkOk && !granted && countryOk
        WindowInsetsHelper.log(
            TAG,
            "canRun=$result [enabled=${config.enabled}, sdk=${Build.VERSION.SDK_INT}>=${config.minSdk}?$sdkOk, granted=$granted, countryAllowed=$countryOk]"
        )
        return result
    }

    // --- Surface decisions ---

    /** Screen (after Language): master gate + `screen.enabled` + `show_once` ledger. */
    fun shouldShowScreen(context: Context, config: LockScreenConfig = LockScreenConfig.load(context)): Boolean {
        if (!canRun(context, config) || !config.screen.enabled) {
            WindowInsetsHelper.log(TAG, "SCREEN → NO (canRun failed or screen.enabled=${config.screen.enabled})")
            return false
        }
        val shown = SettingsRepository(context).fsiScreenShown
        val result = if (config.screen.showOnce) !shown else true
        WindowInsetsHelper.log(TAG, "SCREEN → ${if (result) "YES" else "NO"} [showOnce=${config.screen.showOnce}, alreadyShown=$shown]")
        return result
    }

    /** Dialog (MainShellActivity): master gate + `dialog.enabled` + interval + max-count. */
    fun shouldShowDialog(context: Context, config: LockScreenConfig = LockScreenConfig.load(context)): Boolean {
        if (!canRun(context, config) || !config.dialog.enabled) {
            WindowInsetsHelper.log(TAG, "DIALOG → NO (canRun failed or dialog.enabled=${config.dialog.enabled})")
            return false
        }
        val prefs = SettingsRepository(context)
        val count = prefs.fsiDialogShowCount
        if (count >= config.dialog.maxShowCount) {
            WindowInsetsHelper.log(TAG, "DIALOG → NO (count=$count >= max_show_count=${config.dialog.maxShowCount})")
            return false
        }
        val last = prefs.fsiDialogLastShownMs
        if (last == 0L) {
            WindowInsetsHelper.log(TAG, "DIALOG → YES (first show; count=$count/${config.dialog.maxShowCount})")
            return true
        }
        val elapsedDays = (System.currentTimeMillis() - last) / (24L * 60L * 60L * 1000L)
        val result = elapsedDays >= config.dialog.showAfterDays
        WindowInsetsHelper.log(TAG, "DIALOG → ${if (result) "YES" else "NO"} [elapsed=${elapsedDays}d, need=${config.dialog.showAfterDays}d, count=$count/${config.dialog.maxShowCount}]")
        return result
    }

    // --- Ledger stamps ---

    fun markScreenShown(context: Context) {
        SettingsRepository(context).fsiScreenShown = true
        WindowInsetsHelper.log(TAG, "ledger: screen marked shown")
    }

    fun markDialogShown(context: Context) {
        val prefs = SettingsRepository(context)
        prefs.fsiDialogLastShownMs = System.currentTimeMillis()
        prefs.fsiDialogShowCount = prefs.fsiDialogShowCount + 1
        WindowInsetsHelper.log(TAG, "ledger: dialog shown, count now ${prefs.fsiDialogShowCount}")
    }

    // --- Grant round-trip ---

    /**
     * The "Manage full-screen intents" intent — `EXCLUDE_FROM_RECENTS` only.
     *
     * **Deliberately NOT `FLAG_ACTIVITY_NO_HISTORY`** — see the matching note on
     * `OverlayPermissionUtils.buildOverlayIntent`. The page is a `singleTask` Settings
     * activity in its own task, so NO_HISTORY cannot dispose of it, and adding it
     * breaks Back inside Settings while also firing our for-result callback early
     * (which stops the grant poll and kills the auto-return).
     *
     * Intentionally **no** `FLAG_ACTIVITY_NEW_TASK` either — the page is launched
     * for-result, so it must be started from the caller's task.
     */
    private fun manageIntent(context: Context) =
        Intent(ACTION_MANAGE, Uri.parse("package:${context.packageName}"))
            .addFlags(
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )

    /**
     * Opens the system "Manage full-screen intents" page **in the host's task**
     * via [launcher] (matching the working overlay flow) and arms the auto-return
     * watcher. The App Open ad is suppressed for the return.
     */
    fun openSettings(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        AppOpenAdManager.skipNextAppOpenAd = true
        val launched = runCatching { launcher.launch(manageIntent(activity)) }.isSuccess
        if (launched) {
            WindowInsetsHelper.log(TAG, "openSettings: FSI manage page launched in-task, watcher armed")
            LockScreenWatchService.start(activity)
        } else {
            WindowInsetsHelper.error(TAG, "Failed to open FSI settings", null)
        }
    }

    /** Stop the watcher — call from the host's onResume once it's back. Idempotent. */
    fun stopWatch(context: Context) = LockScreenWatchService.stop(context)
}
