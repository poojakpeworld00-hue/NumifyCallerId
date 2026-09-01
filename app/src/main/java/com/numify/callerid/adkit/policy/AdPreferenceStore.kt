package com.numify.callerid.adkit.policy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AdPreferenceStore constructor(context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PERMISSION_DENY_COUNT = "permission_deny_count"
        const val LANGUAGE_ONETIME = "IsLanguageOnTime"
        const val IS_SPLASH = "IsSplash"
        const val IS_INTRO = "isIntro"
        const val IsFirsttime = "IsFirsttime"
        const val IS_PERMISSION = "isPermission"

        // ⭐ NEW FIELDS (From AppPreferences)
        var VD_SELECTED_LANGUAGE = "VD_selected_app_language"
        private const val VD_SELECTED_THEME = "VD_selected_theme"
        private const val VD_PERMISSIONS_GRANTED = "VD_permissions_granted"

        private const val VD_TERMS_PERMISSIONS_GRANTED = "VD_terms_permissions_granted"

        private const val PREFERENCE_NAME = "My_Bank_Balance_App"
        var IS_FIRST_LAUNCH = "VD_is_first_launch"

        @Volatile
        private var instance: AdPreferenceStore? = null

        fun getInstance(context: Context): AdPreferenceStore {
            return instance ?: synchronized(this) {
                instance ?: AdPreferenceStore(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * The already-initialised instance, or null if [getInstance] was never
         * called. Lets context-less callers (e.g. PermissionRepository) read a
         * flag such as `OnMaketing` — it's initialised early in the launch flow.
         */
        fun getOrNull(): AdPreferenceStore? = instance
    }

    // ------------------------------------------------
    // OLD PROPERTIES
    // ------------------------------------------------

    var IsIntro: Boolean
        get() = preferences.getBoolean(IS_INTRO, false)
        set(value) = preferences.edit { putBoolean(IS_INTRO, value) }

    var IsFirstTime: Boolean
        get() = preferences.getBoolean(IsFirsttime, true)
        set(value) = preferences.edit { putBoolean(IsFirsttime, value) }

    var IsLanguageOnTime: Boolean
        get() = preferences.getBoolean(LANGUAGE_ONETIME, false)
        set(value) = preferences.edit { putBoolean(LANGUAGE_ONETIME, value) }

    var IsPermission: Boolean
        get() = preferences.getBoolean(IS_PERMISSION, false)
        set(value) = preferences.edit { putBoolean(IS_PERMISSION, value) }

    var result_HD_VBC_Type: String
        get() = preferences.getString("result_HD_VBC_Type", "b") ?: "b"
        set(value) = preferences.edit { putString("result_HD_VBC_Type", value) }

    // ------------------------------------------------
    // ⭐ NEW PROPERTIES FROM AppPreferences
    // ------------------------------------------------

    // Language
    var selectedLanguage: String
        get() = preferences.getString(VD_SELECTED_LANGUAGE, "") ?: ""
        set(value) {
            preferences.edit().putString(VD_SELECTED_LANGUAGE, value).apply() // asynchronous
        }

    // Permissions
    var permissionsGranted: Boolean
        get() = preferences.getBoolean(VD_PERMISSIONS_GRANTED, false)
        set(value) = preferences.edit { putBoolean(VD_PERMISSIONS_GRANTED, value) }

    // Save country
    var userCountry: String
        get() = preferences.getString("user_country", "") ?: ""
        set(value) = preferences.edit { putString("user_country", value) }

    var userRegion: String
        get() = preferences.getString("user_Region", "") ?: ""
        set(value) = preferences.edit { putString("user_Region", value) }
    var userCity: String
        get() = preferences.getString("user_City", "") ?: ""
        set(value) = preferences.edit { putString("user_City", value) }

    // termssPermissions
    var termssPermissions: Boolean
        get() = preferences.getBoolean(VD_TERMS_PERMISSIONS_GRANTED, false)
        set(value) = preferences.edit { putBoolean(VD_TERMS_PERMISSIONS_GRANTED, value) }

    var isGrid: Boolean
        get() = preferences.getBoolean("isGrid", false)
        set(value) = preferences.edit {
            putBoolean("isGrid", value)
        }
    var isFirstLaunch: Boolean
        get() = preferences.getBoolean(IS_FIRST_LAUNCH, false)
        set(value) = preferences.edit().putBoolean(IS_FIRST_LAUNCH, value).apply()

    fun update(block: SharedPreferences.Editor.() -> Unit) {
        preferences.edit().apply(block).apply()
    }

    // ------------------------------------------------
    // UTILITY METHODS
    // ------------------------------------------------

    fun putString(key: String?, value: String?) {
        return preferences.edit().putString(key, value).apply()
    }

    fun getString(key: String?): String? {
        return getString(key, "")
    }

    fun getString(key: String?, defaultValue: String?): String? {
        return preferences.getString(key, defaultValue)
    }

    fun putBoolean(key: String?, value: Boolean) {
        return preferences.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String?, defaultValue: Boolean): Boolean {
        return preferences.getBoolean(key, defaultValue)
    }

    fun getBoolean(key: String?): Boolean {
        return getBoolean(key, false)
    }

    fun putLong(key: String?, value: Long) {
        return preferences.edit().putLong(key, value).apply()
    }

    fun getLong(key: String?, defaultValue: Long): Long {
        return preferences.getLong(key, defaultValue)
    }

    fun getLong(key: String?): Long {
        return getLong(key, -1)
    }

    fun putInt(key: String?, value: Int) {
        return preferences.edit().putInt(key, value).apply()
    }

    fun getInt(key: String?): Int {
        return getInt(key, -1)
    }

    fun getInt(key: String?, defaultValue: Int): Int {
        return preferences.getInt(key, defaultValue)
    }

    fun clear() {
        preferences.edit().clear().apply()
    }


}
