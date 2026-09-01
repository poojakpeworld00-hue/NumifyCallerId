package com.numify.callerid.lookup.common

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * The single source of truth for whether Caller ID is switched on for this app.
 *
 * Here, "Caller ID on" means the app holds the Android 10+ **CallScreening role**
 * ([RoleManager.ROLE_CALL_SCREENING]) - the very role the Settings screen
 * manages. Holding it is what allows the app to screen, identify and block
 * incoming calls, so every block-management feature is gated behind it.
 *
 * All role checks belong here, so callers never have to repeat the RoleManager
 * plumbing themselves.
 */
object CallerIdCoordinator {

    /**
     * True when Caller ID counts as enabled.
     *
     * On devices where the CallScreening role does not exist - anything before
     * Android 10, or a build where the role is unavailable - this returns `true`,
     * so the user is never trapped behind a gate they have no way to satisfy and
     * block management stays open to them.
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
