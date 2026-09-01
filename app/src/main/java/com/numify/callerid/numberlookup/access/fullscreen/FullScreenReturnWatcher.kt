package com.numify.callerid.numberlookup.access.fullscreen

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.numify.callerid.numberlookup.kit.EdgeInsets

/**
 * Host-side half of the FSI auto-return. Registered by whichever Activity opened
 * the FSI settings page (the `returnTo`); when [FullScreenWatchService] broadcasts
 * [FullScreenAccess.ACTION_FSI_GRANTED], it re-launches the host to the front so the
 * system Settings page drops behind and the host's `onResume` can react to the
 * grant.
 *
 * `startActivity` is issued from the Activity context (not a background service),
 * which is the path Android allows — mirroring the app's overlay-permission flow.
 */
class FullScreenReturnWatcher(private val activity: Activity) {

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            EdgeInsets.log("FSI", "return: grant broadcast received → front ${activity::class.java.simpleName}")
            // EXACTLY mirrors the working overlay receiver (HomeShellActivity
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
            IntentFilter(FullScreenAccess.ACTION_FSI_GRANTED),
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
