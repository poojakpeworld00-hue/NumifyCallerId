package com.callerid.numberlookup.home.feature.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Keeps the app's process running while the user is on the system "Display over
 * other apps" page, so MainShellActivity's grant poll can see the toggle and
 * bring them back.
 *
 * Without it there is no auto-return on Android 14+. With Settings on top of our
 * task, MainShellActivity is stopped and the process counts as cached, and the
 * cached-app freezer suspends it (seen as `isFrozen=true` on a Samsung A35,
 * Android 16): the poll's Handler never runs, so the grant goes unnoticed until
 * the user presses Back. A started service lifts the process out of the cached
 * state, which is also why the FSI round-trip, armed with LockScreenWatchService,
 * already returned on its own on the same device.
 *
 * It only polls to know when to stop - granted, or [MAX_MS] gone by - and never
 * starts anything itself: the return stays with MainShellActivity, which stops
 * this from stopOverlayGrantPoll whichever way the round-trip ends. Started from
 * the foreground (the user's tap), so the background-start limits do not apply.
 */
class OverlayWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var deadline = 0L

    private val poll = object : Runnable {
        override fun run() {
            when {
                OverlayPermissionUtils.isGranted(this@OverlayWatchService) -> {
                    Log.d(TAG, "overlay granted — watcher done")
                    stopSelf()
                }
                SystemClock.elapsedRealtime() > deadline -> {
                    Log.d(TAG, "watcher timed out")
                    stopSelf()
                }
                else -> handler.postDelayed(this, POLL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        deadline = SystemClock.elapsedRealtime() + MAX_MS
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, POLL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayWatch"
        private const val POLL_MS = 500L

        /** Matches MainShellActivity's own overlay poll deadline. */
        private const val MAX_MS = 90_000L

        fun start(context: Context) {
            runCatching { context.startService(Intent(context, OverlayWatchService::class.java)) }
                .onFailure { Log.w(TAG, "could not start watcher", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, OverlayWatchService::class.java)) }
        }
    }
}
