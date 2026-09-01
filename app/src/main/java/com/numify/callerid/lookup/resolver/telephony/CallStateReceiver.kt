package com.numify.callerid.lookup.resolver.telephony

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
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.engagement.FloatingWidgetManager
import com.numify.callerid.monetize.delivery.engagement.EngagementHubActivity
import com.numify.callerid.monetize.delivery.engagement.background.EngagementSyncService.Companion.NOTIFICATION_ID
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.common.CallerIdCoordinator
import com.numify.callerid.lookup.feature.assistant.MissedCallNotifier
import com.numify.callerid.lookup.feature.incomingcall.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

/**
 * The app's one and only [TelephonyManager.ACTION_PHONE_STATE_CHANGED] receiver,
 * consolidating what used to be two duplicates. It drives both surfaces:
 *
 *  - **Caller-ID card** - RINGING, with a number and the overlay permission,
 *    starts [CallerOverlayService]; OFFHOOK or IDLE tears it down again, stopping
 *    the service and finishing any [IncomingCallActivity].
 *  - **Post-call summary** - on IDLE it works out the call type and shows
 *    [EngagementHubActivity] over the overlay/FGS path, falling back to a
 *    full-screen notification.
 *
 * It is declared in the manifest, so it fires even with the app dead, and is also
 * registered dynamically by EngagementSyncService while the app is alive. A
 * two-second debounce on IDLE deduplicates those overlapping registrations.
 */
class CallStateReceiver : BroadcastReceiver() {

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
                if (!number.isNullOrBlank() && BlocklistRepository(context).isNumberBlocked(number)) {
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

                    !CallerIdCoordinator.isCallerIdEnabled(context) ->
                        Log.d(TAG, "Caller ID not enabled — skipping caller-ID card")

                    !Settings.canDrawOverlays(context) ->
                        Log.d(TAG, "no overlay permission — skipping caller-ID card")

                    else -> CallerOverlayService.start(context, number)
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

                val phoneNumber = lastNumber ?: PRIVATE_NUMBER
                val endTime = Date()
                val startTime = if (callStartTime > 0) Date(callStartTime) else endTime
                val callType = when {
                    wasRinging && !wasOffhook -> "MISSED"    // rang, never answered
                    wasRinging && wasOffhook -> "INCOMING"   // rang and answered
                    !wasRinging && wasOffhook -> "OUTGOING"  // dialed out
                    else -> "UNKNOWN"
                }

                // A missed call is the one outcome the user comes back to later,
                // so it gets its own dismissible notification with a drafted
                // reply — independent of the callback screen, which only appears
                // while the device is locked.
                if (callType == "MISSED" && lastNumber != null) {
                    MissedCallNotifier.show(context, phoneNumber)
                }

                handlePostCall(context, phoneNumber, startTime, endTime, callType)
                resetState()
            }
        }
    }

    /** Tears down the caller-ID card and tells any locked-screen activity to finish. */
    private fun dismissCard(context: Context) {
        CallerOverlayService.stop(context)
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
     * Best-effort rejection of a blocked call through the hidden
     * ITelephony.endCall(), reached by reflection and needing no runtime
     * permission.
     *
     * It is only the fallback for devices that do not hold the CallScreening role.
     * The role-based [CallScreeningGateway] is the primary blocker and turns calls
     * away before they ring. Android 9 (API 28) and later restrict this private
     * API, so it may do nothing at all there - which is precisely why holding the
     * CallScreening role is the dependable path.
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
                if (!AdPreferenceStore.getInstance(context).getBoolean("HD_VBC_Show")) return@launch

                AppOpenAdManager.callbackshow = true
                delay(500)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && canShowOverlay(context)) {
                    // Android 14+ blocks direct background startActivity (BAL); add an
                    // invisible overlay window first, then launch and remove it.
                    try {
                        FloatingWidgetManager(context).showCallbackScreen(phoneNumber, startTime, endTime, type)
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
            val intent = Intent(context, EngagementHubActivity::class.java).apply {
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
        if (EngagementHubActivity.isActive || IncomingCallActivity.isActive) {
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
                    channelId,
                    context.getString(R.string.notif_channel_post_call),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.notif_channel_post_call_desc)
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
            }

            val intent = Intent(context, EngagementHubActivity::class.java).apply {
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
                .setContentTitle(context.getString(R.string.notif_call_ended, displayName(context, phone)))
                .setContentText(context.getString(R.string.notif_call_ended_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pendingIntent, true)
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).cancelAll()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * What to print for [phone]. The withheld-number case carries [PRIVATE_NUMBER],
     * which is an internal token and must never reach the screen — it is English
     * by definition and is compared, not read.
     */
    private fun displayName(context: Context, phone: String): String =
        if (phone.equals(PRIVATE_NUMBER, ignoreCase = true)) {
            context.getString(R.string.caller_private_number)
        } else {
            phone
        }

    companion object {
        private const val TAG = "CallStateReceiver"

        /**
         * Stand-in for a withheld caller ID, passed between this receiver and
         * [EngagementHubActivity] through the intent extras.
         *
         * Deliberately a fixed English token rather than a string resource: it is
         * compared with `equals` on the receiving side, so localizing it would
         * make the comparison fail in all eleven other locales and a withheld
         * number would be treated as a real one. The localized text a user
         * actually sees is `R.string.caller_private_number`.
         */
        const val PRIVATE_NUMBER = "Private Number"
        const val ACTION_CALL_ENDED = "com.numify.callerid.lookup.CALL_ENDED"

        // Cross-broadcast call-state tracking (receiver instances are short-lived).
        private var lastTime = 0L
        private var callStartTime = 0L
        private var lastNumber: String? = null
        private var wasRinging = false
        private var wasOffhook = false
        private var wasBlocked = false
    }
}
