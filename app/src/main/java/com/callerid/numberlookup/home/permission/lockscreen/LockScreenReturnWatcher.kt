package com.callerid.numberlookup.home.permission.lockscreen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.common.WindowInsetsHelper

/**
 * The host-side half of the FSI auto-return. Whichever Activity opened the FSI
 * settings page registers it as the `returnTo`; when [LockScreenWatchService]
 * broadcasts [LockScreenPermission.ACTION_FSI_GRANTED] it brings that host back to
 * the front, clearing the system Settings page off the task so the host's
 * `onResume` can act on the grant.
 *
 * The `startActivity` is issued from the Activity context rather than a
 * background service, which is the route Android permits - the same shape as the
 * app's overlay-permission flow.
 */
class LockScreenReturnWatcher(private val activity: Activity) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            WindowInsetsHelper.log("FSI", "return: grant broadcast received → front ${activity::class.java.simpleName}")
            // Mirrors the overlay return (MainShellActivity.onOverlayGranted):
            // CLEAR_TOP + SINGLE_TOP bring the EXISTING host instance back to the
            // top of the SAME (foreground) task - onNewIntent → onResume - so its
            // armed state survives, and finish the FSI Settings page sitting above
            // it. That page runs in our task without NO_HISTORY, so the old
            // REORDER_TO_FRONT only lifted the host over it: the page stayed in the
            // task underneath, Back returned to it, and after exit it was left as
            // this app's card in Recents.
            //
            // Do NOT add NEW_TASK/CLEAR_TASK: those start a new/cleared task
            // instead of staying in-task. That is what broke the auto-return here
            // before - an in-task start does not need the background-activity-start
            // privilege (which an FSI grant, unlike an overlay grant, does not
            // confer), but a fresh task-start does and was being blocked. CLEAR_TOP
            // alone stays in-task. Guarded so any blocked start can never throw out
            // of onReceive.
            runCatching {
                activity.startActivity(
                    Intent(activity, activity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                )
            }
        }
    }

    fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            activity, receiver,
            IntentFilter(LockScreenPermission.ACTION_FSI_GRANTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { activity.unregisterReceiver(receiver) }
        registered = false
    }
}
