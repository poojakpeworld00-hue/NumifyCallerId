package com.callerid.numberlookup.home.resolver.telephony

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import com.callerid.numberlookup.home.repository.BlocklistRepository

/**
 * Screens incoming calls and turns blocked numbers away silently **before** they
 * ring. It is active only while the app holds the CallScreening role, which is
 * Android 10 and later and granted from Settings. This is the correct way to
 * block a call: unlike the PHONE_STATE receiver's endCall() fallback, the call
 * never rings through at all.
 */
class CallScreeningGateway : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val isIncoming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        } else true

        val number = callDetails.handle?.schemeSpecificPart // tel: number
        val block = isIncoming && !number.isNullOrBlank() &&
                BlocklistRepository(this).isNumberBlocked(number)

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
