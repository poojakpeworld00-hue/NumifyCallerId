package com.callerid.numberlookup.home.permission.lockscreen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.callerid.numberlookup.home.common.WindowInsetsHelper

/**
 * Auto-return watcher for the FSI grant round-trip. It polls every [POLL_MS]
 * while the user is on the system "Manage full-screen intents" page, and the
 * moment the toggle turns ON it broadcasts
 * [LockScreenPermission.ACTION_FSI_GRANTED] so [LockScreenReturnWatcher] can
 * perform an in-task REORDER_TO_FRONT, then shuts itself down.
 *
 * NOTE: the dependable auto-return after the Language screen is not this - it is
 * the in-activity grant poll inside [LockScreenAlertActivity], a plain in-task
 * `startActivity` that needs no notification and no background-activity-start,
 * because opening the Settings page in-task keeps the app holding a foreground
 * task. This service and its broadcast are only a secondary path for hosts that
 * stay resident, such as the MainShellActivity dialog. On Android 12+/16 a
 * background Service frequently cannot be started on the way to Settings, so it
 * is best-effort and never puts anything on screen.
 *
 * Declared in the manifest as `.permission.lockscreen.LockScreenWatchService`.
 */
class LockScreenWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val granted = LockScreenPermission.isGranted(this@LockScreenWatchService)
            WindowInsetsHelper.log("FSI", "watch poll: granted=$granted")
            if (granted) {
                runCatching {
                    sendBroadcast(Intent(LockScreenPermission.ACTION_FSI_GRANTED).setPackage(packageName))
                }
                stopSelf()
            } else handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        handler.removeCallbacks(poll); handler.post(poll)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll); super.onDestroy()
    }

    companion object {
        private const val POLL_MS = 500L

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, LockScreenWatchService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LockScreenWatchService::class.java)) }
        }
    }
}
