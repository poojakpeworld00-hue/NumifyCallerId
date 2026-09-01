package com.numify.callerid.lookup.permission

import android.content.Context

/**
 * SharedPreferences-backed persistence for the Permission Engine.
 *
 * Kept intentionally tiny and independent of the rest of the app's prefs so the
 * engine stays self-contained. Tracks:
 *  - `shown`   → whether a "show once" permission has already been offered.
 *  - `asked`   → whether the OS request has ever been fired (useful for
 *                 rationale / permanently-denied handling by callers).
 *
 * (SharedPreferences is used rather than DataStore to match the rest of this
 * project and to keep the reads synchronous on the main thread — the values are
 * a handful of booleans.)
 */
class PermissionPreferences(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Has a `showOnce` request for [key] already been presented this install? */
    fun wasShown(key: String): Boolean = prefs.getBoolean(shownKey(key), false)

    /** Mark a `showOnce` request for [key] as presented so it is never re-offered. */
    fun markShown(key: String) {
        prefs.edit().putBoolean(shownKey(key), true).apply()
    }

    /** Has the OS permission dialog for [key] ever been requested? */
    fun wasAsked(key: String): Boolean = prefs.getBoolean(askedKey(key), false)

    /** Record that the OS dialog for [key] was requested at least once. */
    fun markAsked(key: String) {
        prefs.edit().putBoolean(askedKey(key), true).apply()
    }

    private fun shownKey(key: String) = "shown_$key"
    private fun askedKey(key: String) = "asked_$key"

    companion object {
        private const val FILE = "permission_engine_prefs"
    }
}
