package com.numify.callerid.monetize.delivery

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Tears down a caller-ID overlay when the user leaves via Home or Recents.
 *
 * Android sends ACTION_CLOSE_SYSTEM_DIALOGS, with reason "homekey" or
 * "recentapps", when either button is pressed. This helper listens for that
 * broadcast while its host Activity is resumed and calls [onCloseRequested], so
 * the overlay window is dismissed cleanly rather than being stranded on screen.
 *
 * It is a [DefaultLifecycleObserver]: the Activity registers it once and the
 * helper hooks itself up to RESUME, STOP and DESTROY on its own.
 *
 * From a caller-ID Activity:
 *
 *     private val systemDialogHelper by lazy {
 *         SystemDialogHelper(this) { dismissCallerIdWindow() }
 *     }
 *
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         lifecycle.addObserver(systemDialogHelper)
 *         // ...
 *     }
 *
 * @param activity         host Activity, used both as the receiver Context and
 *                         for the isFinishing / isDestroyed guards.
 * @param onCloseRequested invoked on the main thread when the overlay should close.
 */
class SystemDialogHelper(
    private val activity: Activity,
    private val onCloseRequested: () -> Unit,
) : DefaultLifecycleObserver {

    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTeardown: Runnable? = null

    // --- Lifecycle callbacks -------------------------------------------------

    override fun onResume(owner: LifecycleOwner) {
        // A recent onStop may have queued a teardown — cancel it so a quick
        // stop/restart (rotation, fast app-switch) does not thrash the receiver.
        cancelPendingTeardown()
        register()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Defer teardown 100ms to debounce a stop immediately followed by restart.
        val teardown = Runnable {
            unregister()
            pendingTeardown = null
        }
        pendingTeardown = teardown
        mainHandler.postDelayed(teardown, TEARDOWN_DELAY_MS)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        cancelPendingTeardown()
        unregister()
        receiver = null
    }

    // --- Receiver registration ----------------------------------------------

    private fun register() {
        if (isRegistered) return
        val r = receiver ?: createReceiver().also { receiver = it }
        ContextCompat.registerReceiver(
            activity,
            r,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isRegistered = true
    }

    private fun unregister() {
        if (!isRegistered) return
        val r = receiver ?: return
        try {
            activity.unregisterReceiver(r)
        } catch (_: IllegalArgumentException) {
            // Receiver was not registered — safe to ignore.
        } finally {
            isRegistered = false
        }
    }

    private fun cancelPendingTeardown() {
        pendingTeardown?.let { mainHandler.removeCallbacks(it) }
        pendingTeardown = null
    }

    private fun createReceiver(): BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action != Intent.ACTION_CLOSE_SYSTEM_DIALOGS) return
                when (intent.getStringExtra(EXTRA_REASON)) {
                    REASON_HOME_KEY, REASON_RECENT_APPS -> {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onCloseRequested()
                        }
                    }
                }
            } catch (_: Exception) {
                // Match the original: never let a broadcast crash the Activity.
            }
        }
    }

    private companion object {
        const val TEARDOWN_DELAY_MS = 100L
        const val EXTRA_REASON = "reason"
        const val REASON_HOME_KEY = "homekey"
        const val REASON_RECENT_APPS = "recentapps"
    }
}
