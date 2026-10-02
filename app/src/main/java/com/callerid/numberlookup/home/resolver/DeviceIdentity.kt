package com.callerid.numberlookup.home.resolver

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * This install's `deviceId` for the contact-saver API: every uploaded contact is
 * stored under it, and it is the only thing `DELETE /android/upload/contacts`
 * matches on.
 *
 * It must outlive a reinstall. An id kept only in SharedPreferences is wiped
 * with the app, the reinstall makes a new one, and everything uploaded under
 * the old id can then never be deleted by anyone. So it is
 * [Settings.Secure.ANDROID_ID], which since Android 8 is fixed per device, user
 * and signing key and survives uninstalling.
 *
 * The API wants 8–64 characters of letters, digits, `-` or `_`, and lower-cases
 * it server-side; ANDROID_ID is 16 hex digits, so it fits. Only if that is
 * missing or is the well-known broken value some old devices report does it
 * fall back to a stored UUID - which cannot survive a reinstall, but is still
 * consistent between this install's upload and its delete.
 */
object DeviceIdentity {

    private const val PREFS = "device_identity"
    private const val KEY_FALLBACK = "fallback_device_id"

    /** Reported by a batch of Android 2.2 devices; identical on all of them. */
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    private val VALID = Regex("^[a-z0-9_-]{8,64}$")

    @SuppressLint("HardwareIds") // the point is a per-device id that survives reinstall
    fun id(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.trim()?.lowercase()
        if (androidId != null && androidId != BROKEN_ANDROID_ID && VALID.matches(androidId)) {
            return androidId
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_FALLBACK, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_FALLBACK, it).apply()
        }
    }
}
