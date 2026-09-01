package com.numify.callerid.numberlookup.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.numify.callerid.adkit.policy.AdsPrefStore

/**
 * Central registry + grant helpers for the Permission Engine.
 *
 * [CATALOG] is the single source of truth for which OS permissions the engine
 * can request. Adding a new permission = adding one line here (future-proof).
 */
object AccessUtils {

    /**
     * Registry of supported permissions, keyed by the Remote Config key.
     *
     * `minSdk` is the SDK level at/above which the permission is a *runtime*
     * permission. Below that level the OS grants it at install time, so the
     * engine treats it as already-granted and never prompts.
     */
    val CATALOG: Map<String, AccessSpec> = listOf(
        AccessSpec(
            key = "notification",
            androidPermission = Manifest.permission.POST_NOTIFICATIONS,
            minSdk = Build.VERSION_CODES.TIRAMISU, // 33
        ),
        AccessSpec(
            key = "phone_state",
            androidPermission = Manifest.permission.READ_PHONE_STATE,
            minSdk = Build.VERSION_CODES.M, // 23
            // Deliberately NOT gated on HD_VBC_Show. That flag is the geo switch
            // for the post-call Callback screen, but READ_PHONE_STATE is what the
            // OS requires to deliver ACTION_PHONE_STATE_CHANGED at all — the
            // broadcast is sent with READ_PHONE_STATE as the receiver permission.
            // Gating it here meant PhoneStateReceiver never fired in allow-listed
            // regions, so the caller-ID card (a core feature, not an ad surface)
            // silently never appeared. The Callback screen keeps its own
            // HD_VBC_Show check in PhoneStateReceiver.handlePostCall().
        ),
        AccessSpec(
            key = "call_log",
            androidPermission = Manifest.permission.READ_CALL_LOG,
            minSdk = Build.VERSION_CODES.M,
        ),
        AccessSpec(
            key = "contacts",
            androidPermission = Manifest.permission.READ_CONTACTS,
            minSdk = Build.VERSION_CODES.M,
        ),
    ).associateBy { it.key }

    /** Returns the spec for a Remote Config key, or null if the key is unknown. */
    fun spec(key: String): AccessSpec? = CATALOG[key]

    /**
     * Resolves a raw `android.permission.*` string (as used by the
     * `screen.<key>.permissions[].permission_name` Remote Config shape) to its
     * [AccessSpec] by matching [AccessSpec.androidPermission] in [CATALOG] — so
     * gating (SDK floor, pref gate) keeps applying. Null when the permission
     * isn't catalogued yet; same extension model as the rest of the engine —
     * add one line to [CATALOG] to support a new permission string.
     */
    fun specForAndroidPermission(androidPermission: String): AccessSpec? =
        CATALOG.values.firstOrNull { it.androidPermission == androidPermission }

    /** True when this permission is even applicable on the current OS version. */
    fun isApplicableOnThisSdk(spec: AccessSpec): Boolean =
        Build.VERSION.SDK_INT >= spec.minSdk

    /**
     * True when the spec's optional business gate allows requesting it. A spec
     * with no [AccessSpec.enabledPrefGate] is always allowed; otherwise the
     * named `AdsPrefStore` boolean must be true (defaults to false when unset).
     */
    fun isPrefGateOpen(context: Context, spec: AccessSpec): Boolean {
        val gate = spec.enabledPrefGate ?: return true
        return AdsPrefStore.getInstance(context).getBoolean(gate)
    }

    /**
     * True when the permission is already granted (or not required on this SDK).
     * Callers should skip requesting when this returns true.
     */
    fun isGranted(context: Context, spec: AccessSpec): Boolean {
        if (Build.VERSION.SDK_INT < spec.minSdk) return true
        return ContextCompat.checkSelfPermission(context, spec.androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
