package com.contacts.callerid.number.lookup.monetize.delivery.engagement.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.IntentFilter
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.contacts.callerid.number.lookup.resolver.telephony.CallStateReceiver

class EngagementSyncService : JobService() {

    private var callReceiver: CallStateReceiver? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d("CallJobService", "Job started")

        // Optional: Create Notification Channel (for compatibility)
        createNotificationChannel()

        // Register the BroadcastReceiver
        callReceiver = CallStateReceiver()
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }

        try {
            registerReceiver(callReceiver, filter)
            Log.d("CallJobService", "CallStateReceiver registered successfully")
        } catch (e: Exception) {
            Log.e("CallJobService", "Failed to register CallStateReceiver: ${e.message}")
        }

        // Finish the job immediately
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        try {
            callReceiver?.let {
                unregisterReceiver(it)
                Log.d("CallJobService", "CallStateReceiver unregistered")
            }
        } catch (e: Exception) {
            Log.e("CallJobService", "Error during unregistration: ${e.message}")
        }
        return false
    }

    // Optional: Create notification channel (used in other parts of your app)
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = CHANNEL_ID
            val name = "Call Monitor"
            val description = "Notification channel for monitoring phone calls"
            val importance = NotificationManager.IMPORTANCE_LOW

            val channel = NotificationChannel(channelId, name, importance).apply {
                this.description = description
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "call_monitor_channel"
        const val NOTIFICATION_ID = 100050005
    }

}

