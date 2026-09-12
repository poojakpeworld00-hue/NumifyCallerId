package com.numify.callerid.lookup.feature.overlay

import android.content.Context
import com.numify.callerid.monetize.strategy.AdPreferenceStore

/**
 * Decides whether opening a tool should detour through the "Display over other
 * apps" Settings page first.
 *
 * The detour is an engagement funnel, not a requirement: none of the twelve
 * tools needs the overlay permission to work, and the user is opening a
 * stopwatch, not a caller-ID feature. So it is off unless Remote Config turns it
 * on, it never blocks the tool, and it asks **once per app session**.
 *
 * That last rule is the important one. Asked literally per tap, someone who
 * declined and then opened five tools would be thrown into system Settings five
 * times, which reads as the app being broken rather than as a request. Once per
 * session is a nudge; five times is a loop.
 */
object ToolOverlayGate {

    /**
     * Remote Config key. Absent means off — a config that fails to fetch must not
     * leave the build sending people into system Settings by default.
     */
    const val RC_KEY = "tools_overlay_ask"

    /**
     * Process-wide, so it survives the shell being recreated (rotation, config
     * change) but not the app being killed. "Session" means what a user would
     * mean by it.
     */
    @Volatile
    private var askedThisSession = false

    /**
     * True when this tool tap should open the overlay Settings page first:
     * Remote Config says so, the permission is not already held, and we have not
     * already asked since launch.
     */
    fun shouldAsk(context: Context): Boolean {
        if (askedThisSession) return false
        if (!OverlayAskPolicy.isAskingAllowed(context)) return false
        if (!AdPreferenceStore.getInstance(context).getBoolean(RC_KEY)) return false
        return !OverlayPermissionUtils.isGranted(context)
    }

    /** Records that the ask has happened, whatever the user does with it. */
    fun markAsked() {
        askedThisSession = true
    }
}
