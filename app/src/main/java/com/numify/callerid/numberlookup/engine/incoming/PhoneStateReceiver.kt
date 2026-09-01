package com.numify.callerid.numberlookup.engine.incoming

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.numify.callerid.adkit.policy.AdsPrefStore
import com.numify.callerid.adkit.runtime.OpenAdManager
import com.numify.callerid.adkit.runtime.showcase.FloatingBubbleManager
import com.numify.callerid.adkit.runtime.showcase.AdShowcaseActivity
import com.numify.callerid.adkit.runtime.showcase.services.BackgroundJobService.Companion.NOTIFICATION_ID
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.store.BlockRegistry
import com.numify.callerid.numberlookup.kit.CallerIdController
import com.numify.callerid.numberlookup.screen.ringing.RingScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * THE single [TelephonyManager.ACTION_PHONE_STATE_CHANGED] receiver for the app.
 * Consolidates what used to be two duplicate receivers. It drives both:
 *
 *  - **Caller-ID card** — RINGING (+ number + overlay) → show [CallerBubbleService];
 *    OFFHOOK / IDLE → dismiss it (stop the service, finish any [RingScreenActivity]).
 *  - **Post-call summary** — on IDLE, determine the call type and show
 *    [AdShowcaseActivity] (overlay/FGS path) or a full-screen notification fallback.
 *
 * Registered in the manifest (fires when the app is dead) and also dynamically by
 * BackgroundJobService while the app is alive. A 2-second debounce on IDLE dedupes the
 * overlapping registrations.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        if (!number.isNullOrEmpty()) lastNumber = number
        Log.d(TAG, "state=$state number=$number")

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                wasRinging = true
                wasOffhook = false
                callStartTime = System.currentTimeMillis()

                // Reject blocked numbers immediately — no ring-through, no caller-ID card.
                if (!number.isNullOrBlank() && BlockRegistry(context).isBlocked(number)) {
                    wasBlocked = true
                    Log.d(TAG, "blocked number rejected: $number")
                    endCall(context)
                    return
                }

                // Caller-ID card needs a number, the overlay permission, AND the
                // Caller ID role itself. The role was previously not checked here,
                // so the card appeared for users who had granted overlay but never
                // turned Caller ID on — the one switch Settings and Blocklist both
                // present as *the* control for this feature. Both gates now agree.
                when {
                    number.isNullOrBlank() ->
                        Log.w(TAG, "ringing without a number — skipping caller-ID card")

                    !CallerIdController.isCallerIdEnabled(context) ->
                        Log.d(TAG, "Caller ID not enabled — skipping caller-ID card")

                    !Settings.canDrawOverlays(context) ->
                        Log.d(TAG, "no overlay permission — skipping caller-ID card")

                    else -> CallerBubbleService.start(context, number)
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                wasOffhook = true
                if (callStartTime == 0L) callStartTime = System.currentTimeMillis() // outgoing
                dismissCard(context)
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                dismissCard(context)

                // A blocked call was rejected — don't show the post-call summary.
                if (wasBlocked) {
                    resetState()
                    return
                }

                // Debounce duplicate IDLE broadcasts (manifest + dynamic registration).
                val now = System.currentTimeMillis()
                if (now - lastTime < 2000) {
                    Log.d(TAG, "duplicate IDLE skipped")
                    resetState()
                    return
                }
                lastTime = now

                val phoneNumber = lastNumber ?: "Private Number"
                val endTime = Date()
                val startTime = if (callStartTime > 0) Date(callStartTime) else endTime
                val callType = when {
                    wasRinging && !wasOffhook -> "MISSED"    // rang, never answered
                    wasRinging && wasOffhook -> "INCOMING"   // rang and answered
                    !wasRinging && wasOffhook -> "OUTGOING"  // dialed out
                    else -> "UNKNOWN"
                }

                handlePostCall(context, phoneNumber, startTime, endTime, callType)
                resetState()
            }
        }
    }

    /** Tears down the caller-ID card and tells any locked-screen activity to finish. */
    private fun dismissCard(context: Context) {
        CallerBubbleService.stop(context)
        context.sendBroadcast(Intent(ACTION_CALL_ENDED).setPackage(context.packageName))
    }

    private fun resetState() {
        callStartTime = 0L
        lastNumber = null
        wasRinging = false
        wasOffhook = false
        wasBlocked = false
    }

    /**
     * Best-effort rejection of a blocked call via the hidden ITelephony.endCall()
     * (reflection — needs no runtime permission).
     *
     * This is only the fallback for devices that don't hold the CallScreening role;
     * the role-based [SpamScreeningService] is the primary blocker and rejects
     * calls before they ring. Note Android 9+ (API 28+) restricts this private API,
     * so it may be a no-op there — which is why granting the CallScreening role is
     * the reliable path.
     */
    @Suppress("DiscouragedPrivateApi", "PrivateApi")
    private fun endCall(context: Context) {
        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val method = tm.javaClass.getDeclaredMethod("getITelephony").apply { isAccessible = true }
            val telephony = method.invoke(tm) ?: return
            telephony.javaClass.getDeclaredMethod("endCall").invoke(telephony)
        } catch (e: Exception) {
            Log.e(TAG, "endCall failed", e)
        }
    }

    // -------------------- Post-call summary screen --------------------

    private fun handlePostCall(
        context: Context, phoneNumber: String, startTime: Date, endTime: Date, type: String
    ) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (!AdsPrefStore.getInstance(context).getBoolean("HD_VBC_Show")) return@launch

                OpenAdManager.callbackshow = true
                delay(500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && canShowOverlay(context)) {
                    // Android 14+ blocks direct background startActivity (BAL); add an
                    // invisible overlay window first, then launch and remove it.
                    try {
                        FloatingBubbleManager(context).presentCallbackScreen(phoneNumber, startTime, endTime, type)
                    } catch (e: Exception) {
                        showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                } else if (canShowOverlay(context)) {
                    try {
                        launchCallbackScreen(context, phoneNumber, startTime, endTime, type)
                    } catch (e: Exception) {
                        showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                    }
                } else {
                    showFullScreenNotification(context, phoneNumber, startTime, endTime, type)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun canShowOverlay(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun launchCallbackScreen(
        context: Context, phone: String, start: Date, end: Date, type: String
    ) {
        try {
            val intent = Intent(context, AdShowcaseActivity::class.java).apply {
                putExtra("phone", phone)
                putExtra("start_time", start.time)
                putExtra("end_time", end.time)
                putExtra("call_type", type)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "callback activity launch failed", e)
        }
    }

    private fun showFullScreenNotification(
        context: Context, phone: String, start: Date, end: Date, type: String
    ) {
        Log.e(TAG, "showFullScreenNotification: ")
        if (AdShowcaseActivity.isActive || RingScreenActivity.isActive) {
            Log.d(TAG, "post-call screen in foreground — suppressing notification")
            return
        }
        val powerManager = context.getSystemService(PowerManager::class.java)
        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            val channelId = "post_call_channel"
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId, "Post Call Info", NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Shows callback screen after a call"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(context, AdShowcaseActivity::class.java).apply {
                putExtra("phone", phone)
                putExtra("start_time", start.time)
                putExtra("end_time", end.time)
                putExtra("call_type", type)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Call ended: $phone")
                .setContentText("Tap or wait — showing call summary...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).cancelAll()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "PhoneStateReceiver"
        const val ACTION_CALL_ENDED = "com.numify.callerid.numberlookup.CALL_ENDED"

        // Cross-broadcast call-state tracking (receiver instances are short-lived).
        private var lastTime = 0L
        private var callStartTime = 0L
        private var lastNumber: String? = null
        private var wasRinging = false
        private var wasOffhook = false
        private var wasBlocked = false
    }
}
