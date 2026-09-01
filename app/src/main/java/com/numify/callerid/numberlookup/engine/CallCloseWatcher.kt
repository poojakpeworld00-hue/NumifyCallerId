package com.numify.callerid.numberlookup.engine

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Watches the device call state and fires [onEnded] once a call that was ringing or
 * active returns to IDLE.
 *
 * The manifest [com.numify.callerid.numberlookup.engine.incoming.PhoneStateReceiver]
 * already dismisses the caller-ID card on the IDLE broadcast, but the OS can delay or
 * drop later PHONE_STATE broadcasts to a manifest receiver — leaving the card on screen
 * after the call has ended. Listening directly (the card owns the listener) guarantees
 * it disappears the moment the ring stops.
 *
 * Requires READ_PHONE_STATE. [start]/[stop] must be called on a Looper thread (main).
 */
class CallCloseWatcher(context: Context, private val onEnded: () -> Unit) {

    private val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private val appContext = context.applicationContext

    private var legacy: PhoneStateListener? = null
    private var modern: TelephonyCallback? = null

    /** Becomes true once we've seen RINGING/OFFHOOK, so the initial state isn't mistaken for an end. */
    private var sawActive = false

    @SuppressLint("MissingPermission")
    fun start() {
        val tm = tm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handle(state)
            }
            modern = cb
            runCatching { tm.registerTelephonyCallback(appContext.mainExecutor, cb) }
        } else {
            val l = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
            }
            legacy = l
            @Suppress("DEPRECATION")
            runCatching { tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE) }
        }
    }

    private fun handle(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> sawActive = true
            TelephonyManager.CALL_STATE_IDLE -> if (sawActive) onEnded()
        }
    }

    fun stop() {
        val tm = tm ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modern?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        } else {
            @Suppress("DEPRECATION")
            legacy?.let { runCatching { tm.listen(it, PhoneStateListener.LISTEN_NONE) } }
        }
        modern = null
        legacy = null
    }
}
