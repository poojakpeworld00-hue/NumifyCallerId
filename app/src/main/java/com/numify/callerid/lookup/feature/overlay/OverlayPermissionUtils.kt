package com.numify.callerid.lookup.feature.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Helpers for the "display over other apps" (overlay) permission used by the
 * caller-ID overlay. Keeps the permission check and the Settings intent in one
 * place so every caller agrees. Grant polling lives in the caller —
 * `MainShellActivity.startOverlayPermissionFlow()` owns the only implementation
 * (a main-thread Handler poll; the old background watcher Service was removed).
 */
object OverlayPermissionUtils {

    /** True when we already have the overlay permission (or don't need it). */
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * Intent onto the system "display over other apps" screen for this app.
     *
     * **`FLAG_ACTIVITY_NO_HISTORY` is omitted on purpose.** It reads like the
     * obvious way to stop the page lingering, and it is the wrong tool twice over:
     *
     *  1. The action resolves to a `singleTask` Settings activity, so it opens in
     *     Settings' own task whatever we pass. NO_HISTORY cannot dispose of an
     *     activity in a different task, so it never removed the lingering page.
     *  2. It breaks the page outright. On OEM builds where this action lands on
     *     the full app LIST instead of our own detail row, tapping any entry
     *     finishes the list, so Back from the detail goes nowhere sensible. Worse,
     *     that early finish fires our for-result callback and stops the grant
     *     poll, so flipping the toggle no longer brings the user back.
     *
     * The lingering page is dealt with in `MainShellActivity.exitToHome()`, which
     * brings the launcher forward before finishing our own task.
     *
     * `FLAG_ACTIVITY_NEW_TASK` and `CLEAR_TASK` are likewise absent by design: the
     * page is started for-result, so it has to run in the caller's task.
     */
    fun buildOverlayIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
}
