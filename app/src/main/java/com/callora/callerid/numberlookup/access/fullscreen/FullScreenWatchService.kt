package com.callora.callerid.numberlookup.access.fullscreen

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.callora.callerid.numberlookup.kit.EdgeInsets

/**
 * Auto-return watcher for the FSI grant round-trip. While the user sits on the
 * system "Manage full-screen intents" page it polls every [POLL_MS]; the instant
 * the toggle flips ON it broadcasts [FullScreenAccess.ACTION_FSI_GRANTED] so
 * [FullScreenReturnWatcher] can do an in-task REORDER_TO_FRONT, then stops.
 *
 * NOTE: the primary, reliable auto-return for the after-Language screen is the
 * in-activity grant poll inside [FullScreenAccessActivity] (a plain in-task
 * `startActivity` — no notification, no background-activity-start needed, because
 * the Settings page is opened in-task so the app keeps a foreground task). This
 * service + broadcast is only a secondary path for hosts that stay resident (e.g.
 * the HomeShellActivity dialog); on Android 12+/16 a background Service often cannot be
 * started on the way to Settings, so it is best-effort and never shows any UI.
 *
 * Registered in the manifest as `.permission.fsi.FullScreenWatchService`.
 */
class FullScreenWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val granted = FullScreenAccess.isGranted(this@FullScreenWatchService)
            EdgeInsets.log("FSI", "watch poll: granted=$granted")
            if (granted) {
                runCatching {
                    sendBroadcast(Intent(FullScreenAccess.ACTION_FSI_GRANTED).setPackage(packageName))
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
            runCatching { context.startService(Intent(context, FullScreenWatchService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FullScreenWatchService::class.java)) }
        }
    }
}
