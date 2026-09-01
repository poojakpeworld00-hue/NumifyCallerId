package com.numify.callerid.lookup.permission.lockscreen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.numify.callerid.lookup.common.WindowInsetsHelper

/**
 * The host-side half of the FSI auto-return. Whichever Activity opened the FSI
 * settings page registers it as the `returnTo`; when [LockScreenWatchService]
 * broadcasts [LockScreenPermission.ACTION_FSI_GRANTED] it relaunches that host to
 * the front, dropping the system Settings page behind it so the host's `onResume`
 * can act on the grant.
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
            // EXACTLY mirrors the working overlay receiver (MainShellActivity
            // .overlayGrantedReceiver): REORDER_TO_FRONT + SINGLE_TOP reorder the
            // EXISTING host instance to the top of the SAME (foreground) task —
            // onNewIntent → onResume — so its armed state survives and the FSI
            // Settings page (opened in-task, NO_HISTORY) drops away.
            //
            // Do NOT add CLEAR_TASK/NEW_TASK/CLEAR_TOP: those force a new/cleared
            // task instead of the in-task reorder. That is what broke the
            // auto-return here while the identical overlay flow worked — the
            // in-task reorder does not need the background-activity-start privilege
            // (which an FSI grant, unlike an overlay grant, does not confer), but a
            // fresh task-start does and was being blocked. Guarded so any blocked
            // start can never throw out of onReceive.
            runCatching {
                activity.startActivity(
                    Intent(activity, activity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
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
