package com.numify.callerid.numberlookup.access

import android.os.Handler
import android.os.Looper

/**
 * Tiny main-thread scheduler used to honour each rule's configured `delay`
 * before a permission dialog is shown, and to cancel any pending work when the
 * user leaves the screen or a new Activity takes over.
 */
class AccessScheduler {

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
