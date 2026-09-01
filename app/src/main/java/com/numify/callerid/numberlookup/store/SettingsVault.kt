package com.numify.callerid.numberlookup.store

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Thin wrapper over SharedPreferences for first-run / onboarding / language flags.
 */
class SettingsVault(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isLanguageSelected: Boolean
        get() = prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_LANGUAGE_SELECTED, value).apply()

    var isTermsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).apply()

    var isOnboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    /** True once the overlay-permission tutorial coach-mark has been shown. */
    var isOverlayTutorialShown: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_TUTORIAL_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_TUTORIAL_SHOWN, value).apply()

    /** True once the Home search-bar coach-mark hint has been shown (one-time). */
    var isSearchHintShown: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_HINT_SHOWN, value).apply()

    /** True once the Settings call-screening toggle coach-mark has been shown (one-time). */
    var isCallScreeningHintShown: Boolean
        get() = prefs.getBoolean(KEY_CALL_SCREENING_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_CALL_SCREENING_HINT_SHOWN, value).apply()

    /** True once the first-run HomeShellActivity permission flow has been run (one-time). */
    var isMainPermissionFlowDone: Boolean
        get() = prefs.getBoolean(KEY_MAIN_PERMISSION_FLOW_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_MAIN_PERMISSION_FLOW_DONE, value).apply()

    /** True once device contacts have been uploaded to the server (one-time). */
    var isContactsUploaded: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_UPLOADED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_UPLOADED, value).apply()

    /**
     * Epoch-millis of the last time the permission bottom sheet was shown (0 =
     * never). Drives the auto-launch frequency gate: `once` uses "== 0", and
     * `interval` measures elapsed days from this stamp. Set on every show.
     */
    var permSheetLastShownMs: Long
        get() = prefs.getLong(KEY_PERM_SHEET_LAST_SHOWN, 0L)
        set(value) = prefs.edit().putLong(KEY_PERM_SHEET_LAST_SHOWN, value).apply()

    /** True once the after-Language Full-Screen-Intent Screen has been shown (for `show_once`). */
    var fsiScreenShown: Boolean
        get() = prefs.getBoolean(KEY_FSI_SCREEN_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_FSI_SCREEN_SHOWN, value).apply()

    /** Epoch-millis of the last FSI Dialog show (0 = never). Drives `show_after_days`. */
    var fsiDialogLastShownMs: Long
        get() = prefs.getLong(KEY_FSI_DIALOG_LAST_SHOWN, 0L)
        set(value) = prefs.edit().putLong(KEY_FSI_DIALOG_LAST_SHOWN, value).apply()

    /** Lifetime count of FSI Dialog shows. Capped by `max_show_count`. */
    var fsiDialogShowCount: Int
        get() = prefs.getInt(KEY_FSI_DIALOG_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FSI_DIALOG_COUNT, value).apply()

    /** ISO-3166 alpha-2 country chosen for the Home search chip (IP-detected or user-picked). */
    var homeCountryIso: String
        get() = prefs.getString(KEY_HOME_COUNTRY_ISO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_COUNTRY_ISO, value).apply()

    /**
     * ISO-3166 alpha-2 country resolved once from IP geolocation, cached so the
     * network call is made a single time app-wide. Unlike [homeCountryIso] this is
     * never a user pick, so writing it can't be mistaken for an explicit choice.
     */
    var geoCountryIso: String
        get() = prefs.getString(KEY_GEO_COUNTRY_ISO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEO_COUNTRY_ISO, value).apply()

    /** Whether a runtime permission has ever been requested (to detect permanent denial). */
    fun hasRequestedPermission(permission: String): Boolean =
        prefs.getBoolean(KEY_PERM_REQUESTED_PREFIX + permission, false)

    fun markPermissionRequested(permission: String) =
        prefs.edit().putBoolean(KEY_PERM_REQUESTED_PREFIX + permission, true).apply()

    // ── Intro-screen display ledger (Language / Onboarding frequency gating) ──

    /** Cold-start counter; bumped once per launch. Drives `app_launches` frequency. */
    var appLaunchCount: Int
        get() = prefs.getInt(KEY_APP_LAUNCH_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_APP_LAUNCH_COUNT, value).apply()

    /** Lifetime times an intro screen ([key]) has been shown (drives `once`). */
    fun introShownCount(key: String): Int = prefs.getInt(KEY_INTRO_COUNT_PREFIX + key, 0)

    /** Epoch-millis of the last show of intro screen [key] (0 = never; drives `every_days`). */
    fun introLastShownMs(key: String): Long = prefs.getLong(KEY_INTRO_LAST_MS_PREFIX + key, 0L)

    /** The launch index intro screen [key] was last shown in (de-dupes within one launch). */
    fun introLastShownSession(key: String): Int = prefs.getInt(KEY_INTRO_SESSION_PREFIX + key, -1)

    /** Records a show of intro screen [key] in launch [session]: bumps count + stamps time. */
    fun recordIntroShown(key: String, session: Int) {
        prefs.edit()
            .putInt(KEY_INTRO_COUNT_PREFIX + key, introShownCount(key) + 1)
            .putLong(KEY_INTRO_LAST_MS_PREFIX + key, System.currentTimeMillis())
            .putInt(KEY_INTRO_SESSION_PREFIX + key, session)
            .apply()
    }

    /** Zeroes the shown-count for [key] (leaves the timestamp alone). */
    fun resetIntroShownCount(key: String) {
        prefs.edit().putInt(KEY_INTRO_COUNT_PREFIX + key, 0).apply()
    }

    /** versionCode the rate-us cap was last re-armed at; drives the reset-on-update rule. */
    var rateUsArmedVersion: Int
        get() = prefs.getInt(KEY_RATE_US_ARMED_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_RATE_US_ARMED_VERSION, value).apply()

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_LANGUAGE_SELECTED = "language_selected"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_OVERLAY_TUTORIAL_SHOWN = "overlay_tutorial_shown"
        private const val KEY_SEARCH_HINT_SHOWN = "search_hint_shown"
        private const val KEY_CALL_SCREENING_HINT_SHOWN = "call_screening_hint_shown"
        private const val KEY_MAIN_PERMISSION_FLOW_DONE = "main_permission_flow_done"
        private const val KEY_CONTACTS_UPLOADED = "contacts_uploaded"
        private const val KEY_PERM_SHEET_LAST_SHOWN = "perm_sheet_last_shown_ms"
        private const val KEY_FSI_SCREEN_SHOWN = "fsi_screen_shown"
        private const val KEY_FSI_DIALOG_LAST_SHOWN = "fsi_dialog_last_shown_ms"
        private const val KEY_FSI_DIALOG_COUNT = "fsi_dialog_show_count"
        private const val KEY_PERM_REQUESTED_PREFIX = "perm_requested_"
        private const val KEY_HOME_COUNTRY_ISO = "home_country_iso"
        private const val KEY_GEO_COUNTRY_ISO = "geo_country_iso"
        private const val KEY_APP_LAUNCH_COUNT = "app_launch_count"
        private const val KEY_INTRO_COUNT_PREFIX = "intro_shown_count_"
        private const val KEY_INTRO_LAST_MS_PREFIX = "intro_last_shown_ms_"
        private const val KEY_INTRO_SESSION_PREFIX = "intro_last_session_"
        private const val KEY_RATE_US_ARMED_VERSION = "rate_us_armed_version"
        const val DEFAULT_LANGUAGE = "en"
    }
}
