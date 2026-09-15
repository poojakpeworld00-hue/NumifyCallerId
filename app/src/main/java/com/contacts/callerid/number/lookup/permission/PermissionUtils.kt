package com.contacts.callerid.number.lookup.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore

/**
 * Permission catalog and grant checks for the runtime-permission engine.
 *
 * Everything the engine is allowed to ask for lives in [CATALOG]; supporting
 * one more permission means adding a single entry there and nothing else.
 */
object PermissionUtils {

    /**
     * Supported permissions indexed by their Remote Config key.
     *
     * `minSdk` marks the API level from which the OS treats the permission as a
     * runtime grant. Below that level it is granted at install time, so the
     * engine reports it as already held and never raises a prompt.
     */
    val CATALOG: Map<String, PermissionSpec> = listOf(
        PermissionSpec(
            key = "notification",
            androidPermission = Manifest.permission.POST_NOTIFICATIONS,
            minSdk = Build.VERSION_CODES.TIRAMISU, // Android 13
        ),
        PermissionSpec(
            key = "phone_state",
            androidPermission = Manifest.permission.READ_PHONE_STATE,
            minSdk = Build.VERSION_CODES.M, // Android 6
            // Intentionally outside the HD_VBC_Show gate. That flag only decides
            // whether the post-call Callback screen appears in a given region, but
            // READ_PHONE_STATE is what makes the OS deliver
            // ACTION_PHONE_STATE_CHANGED at all: the broadcast carries
            // READ_PHONE_STATE as its receiver permission. Gating here stopped
            // CallStateReceiver firing in allow-listed regions, which silently
            // killed the caller-ID card - a core feature, not an ad surface. The
            // Callback screen keeps its own HD_VBC_Show check in handlePostCall().
        ),
        PermissionSpec(
            key = "call_log",
            androidPermission = Manifest.permission.READ_CALL_LOG,
            minSdk = Build.VERSION_CODES.M,
        ),
        PermissionSpec(
            key = "contacts",
            androidPermission = Manifest.permission.READ_CONTACTS,
            minSdk = Build.VERSION_CODES.M,
        ),
    ).associateBy { it.key }

    /** The spec registered for a Remote Config key, or null when the key is unrecognised. */
    fun spec(key: String): PermissionSpec? = CATALOG[key]

    /**
     * Maps a raw `android.permission.*` string - the shape used by
     * `screen.<key>.permissions[].permission_name` in Remote Config - onto its
     * [PermissionSpec], by matching [PermissionSpec.androidPermission] against
     * [CATALOG] so the SDK floor and the pref gate both still apply. Returns
     * null for a permission that has not been catalogued; as everywhere else in
     * the engine, one new [CATALOG] entry is all another string needs.
     */
    fun specForAndroidPermission(androidPermission: String): PermissionSpec? =
        CATALOG.values.firstOrNull { it.androidPermission == androidPermission }

    /** Whether this permission means anything at all on the running OS version. */
    fun isApplicableOnThisSdk(spec: PermissionSpec): Boolean =
        Build.VERSION.SDK_INT >= spec.minSdk

    /**
     * Whether the spec's optional business gate permits a request. A spec with
     * no [PermissionSpec.enabledPrefGate] is always permitted; the rest need the
     * named `AdPreferenceStore` boolean to be true, and that defaults to false
     * until something writes it.
     */
    fun isPrefGateOpen(context: Context, spec: PermissionSpec): Boolean {
        val gate = spec.enabledPrefGate ?: return true
        return AdPreferenceStore.getInstance(context).getBoolean(gate)
    }

    /**
     * Whether the permission is already held, or is not required at this SDK
     * level. Callers should not request while this returns true.
     */
    fun isGranted(context: Context, spec: PermissionSpec): Boolean {
        if (Build.VERSION.SDK_INT < spec.minSdk) return true
        return ContextCompat.checkSelfPermission(context, spec.androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
