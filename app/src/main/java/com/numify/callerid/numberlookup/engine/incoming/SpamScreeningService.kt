package com.numify.callerid.numberlookup.engine.incoming

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.numify.callerid.numberlookup.store.BlockRegistry

/**
 * Screens incoming calls and silently rejects blocked numbers **before** they
 * ring. Active only while the app holds the CallScreening role (Android 10+,
 * granted from Settings). This is the proper way to block calls — unlike the
 * PHONE_STATE receiver's endCall() fallback, the call never rings through.
 */
class SpamScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else true

        val number = callDetails.handle?.schemeSpecificPart // tel: number
        val block = isIncoming && !number.isNullOrBlank() &&
                BlockRegistry(this).isBlocked(number)

        if (block) Log.d(TAG, "blocked incoming call screened: $number")

        val response = CallResponse.Builder()
            .setDisallowCall(block)   // don't let the call through
            .setRejectCall(block)     // hang up immediately
            .setSkipCallLog(false)    // still record it in the call log
            .setSkipNotification(block) // no missed-call notification for blocked
            .build()

        respondToCall(callDetails, response)
    }

    companion object {
        private const val TAG = "CallScreening"
    }
}
