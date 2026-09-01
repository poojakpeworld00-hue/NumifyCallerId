package com.numify.callerid.numberlookup.kit

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper around a single SharedPreferences file for first-launch state,
 * language preference, and theme preference.
 */
object PreferenceStore {

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"

    private const val FILE = "plantify_prefs"
    private const val KEY_LANGUAGE = "language_code"
    private const val KEY_TERMS_ACCEPTED = "terms_accepted"
    private const val KEY_ONBOARDING_DONE = "onboarding_done"
    private const val KEY_THEME = "app_theme"
    private const val KEY_INSTALLED_AT = "installed_at"
    private const val KEY_NOTIFICATIONS_ON = "notifications_on"
    private const val KEY_PLANT_REMINDER_ON = "plant_reminder_on"

    /** Marker value persisted when the user picks "Default" — empty = follow system locale. */
    const val LANGUAGE_DEFAULT = ""

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(ctx: Context): String? = prefs(ctx).getString(KEY_LANGUAGE, null)
    fun selectedLanguage(ctx: Context): String = language(ctx).orEmpty()
    fun setLanguage(ctx: Context, code: String) =
        prefs(ctx).edit().putString(KEY_LANGUAGE, code).apply()

    fun selectedTheme(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME, "").orEmpty()
    fun setTheme(ctx: Context, theme: String) =
        prefs(ctx).edit().putString(KEY_THEME, theme).apply()

    fun termsAccepted(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_TERMS_ACCEPTED, false)
    fun setTermsAccepted(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_TERMS_ACCEPTED, true).apply()

    fun onboardingDone(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDING_DONE, false)
    fun setOnboardingDone(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()

    fun notificationsOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_NOTIFICATIONS_ON, true)
    fun setNotificationsOn(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_NOTIFICATIONS_ON, on).apply()

    fun plantReminderOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PLANT_REMINDER_ON, true)
    fun setPlantReminderOn(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PLANT_REMINDER_ON, on).apply()

    /**
     * First-seen timestamp, lazily seeded the first time this is read. Used by
     * the Quick Stats card to show "days since install".
     */
    fun installedAt(ctx: Context): Long {
        val p = prefs(ctx)
        val saved = p.getLong(KEY_INSTALLED_AT, 0L)
        if (saved > 0L) return saved
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_INSTALLED_AT, now).apply()
        return now
    }
}
