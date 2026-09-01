package com.callora.callerid.numberlookup.kit

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Single source of truth for whether "Caller ID" is enabled for this app.
 *
 * In this app "Caller ID on" means we hold the Android 10+ **CallScreening role**
 * ([RoleManager.ROLE_CALL_SCREENING]) — the same role the Settings screen manages.
 * Holding it is what lets the app screen/identify (and block) incoming calls, so
 * every block-management feature is gated on it.
 *
 * Keep all role checks here so callers never duplicate the RoleManager plumbing.
 */
object CallerIdController {

    /**
     * True when Caller ID is considered enabled.
     *
     * On devices where the CallScreening role does not exist (pre-Android 10, or
     * the role is unavailable on this build) we return `true` so we never trap the
     * user behind a gate they cannot satisfy — block management stays open there.
     */
    fun isCallerIdEnabled(context: Context): Boolean {
        if (!isRoleAvailable(context)) return true
        val rm = context.getSystemService(RoleManager::class.java) ?: return true
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    /** True when the CallScreening role can actually be requested on this device. */
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    }

    /**
     * Intent that launches the system role-request dialog for CallScreening, or
     * `null` when the role is unavailable / already held (nothing to request).
     */
    fun buildEnableIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }
}
