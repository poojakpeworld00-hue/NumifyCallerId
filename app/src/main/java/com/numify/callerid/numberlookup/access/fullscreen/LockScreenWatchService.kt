package com.numify.callerid.numberlookup.access.fullscreen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.numify.callerid.numberlookup.kit.WindowInsetsHelper

/**
 * Auto-return watcher for the FSI grant round-trip. While the user sits on the
 * system "Manage full-screen intents" page it polls every [POLL_MS]; the instant
 * the toggle flips ON it broadcasts [LockScreenPermission.ACTION_FSI_GRANTED] so
 * [LockScreenReturnWatcher] can do an in-task REORDER_TO_FRONT, then stops.
 *
 * NOTE: the primary, reliable auto-return for the after-Language screen is the
 * in-activity grant poll inside [LockScreenAlertActivity] (a plain in-task
 * `startActivity` — no notification, no background-activity-start needed, because
 * the Settings page is opened in-task so the app keeps a foreground task). This
 * service + broadcast is only a secondary path for hosts that stay resident (e.g.
 * the MainShellActivity dialog); on Android 12+/16 a background Service often cannot be
 * started on the way to Settings, so it is best-effort and never shows any UI.
 *
 * Registered in the manifest as `.permission.fsi.LockScreenWatchService`.
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
