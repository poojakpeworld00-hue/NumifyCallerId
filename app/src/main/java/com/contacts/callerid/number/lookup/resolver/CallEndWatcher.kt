package com.contacts.callerid.number.lookup.resolver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Watches the device call state and invokes [onEnded] as soon as a call that was
 * ringing or connected drops back to IDLE.
 *
 * The manifest-declared
 * [com.contacts.callerid.number.lookup.resolver.telephony.CallStateReceiver] already
 * dismisses the caller-ID card on the IDLE broadcast, but the OS is free to delay
 * or drop later PHONE_STATE broadcasts to a manifest receiver, which can strand
 * the card on screen after the call is over. Listening directly, with the card
 * owning the listener, guarantees it goes away the instant the ringing stops.
 *
 * Needs READ_PHONE_STATE. [start] and [stop] must both be called on a Looper
 * thread, meaning the main thread.
 */
class CallEndWatcher(context: Context, private val onEnded: () -> Unit) {

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
