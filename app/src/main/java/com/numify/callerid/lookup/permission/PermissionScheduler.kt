package com.numify.callerid.lookup.permission

import android.os.Handler
import android.os.Looper

/**
 * A small main-thread scheduler, used to honour each rule's configured `delay`
 * before a permission dialog goes up, and to cancel anything still pending when
 * the user leaves the screen or another Activity takes over.
 */
class PermissionScheduler {

    private val handler = Handler(Looper.getMainLooper())

    /** Runs [action] on the main thread after [delayMs] (immediately if <= 0). */
    fun schedule(delayMs: Long, action: () -> Unit) {
        if (delayMs <= 0L) handler.post(action) else handler.postDelayed(action, delayMs)
    }

    /** Cancels every pending action. */
    fun clear() {
        handler.removeCallbacksAndMessages(null)
    }
}
