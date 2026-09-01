package com.numify.callerid.numberlookup.screen.consent

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
     * Intent to the system "display over other apps" screen for this app.
     *
     * **Deliberately NOT `FLAG_ACTIVITY_NO_HISTORY`.** It looks like the right tool
     * for "don't let this page linger", and it is not, for two reasons:
     *
     *  1. The page resolves to a `singleTask` Settings activity, so it opens in
     *     Settings' OWN task no matter what we pass. NO_HISTORY cannot dispose of
     *     an activity in another task, so it never fixed the lingering page.
     *  2. It actively breaks the page. On OEM builds where this action opens the
     *     full app LIST rather than our app's detail row, tapping any app finishes
     *     the list, so Back from the detail lands nowhere sensible. Worse, the
     *     early finish fires our for-result callback, which stops the grant poll —
     *     so enabling the toggle no longer returns the user to the app.
     *
     * Lingering is handled instead by `MainShellActivity.exitToHome()`, which
     * fronts the launcher before finishing our task.
     *
     * Also intentionally **no** `FLAG_ACTIVITY_NEW_TASK` / `CLEAR_TASK`: the page is
     * launched *for-result*, so it must be started from the caller's task.
     */
    fun buildOverlayIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )
}
