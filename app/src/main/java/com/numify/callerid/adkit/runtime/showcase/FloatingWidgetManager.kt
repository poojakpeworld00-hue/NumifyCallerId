package com.numify.callerid.adkit.runtime.showcase

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.numify.callerid.numberlookup.R
import java.util.Date

/**
 * On Android 14+ (API 34), starting an Activity from a BroadcastReceiver is
 * blocked by stricter background-activity-launch (BAL) restrictions, even with
 * FLAG_ACTIVITY_NEW_TASK.
 *
 * Workaround: Add an invisible TYPE_APPLICATION_OVERLAY window via
 * WindowManager (requires SYSTEM_ALERT_WINDOW / canDrawOverlays). The overlay
 * counts as a "non-app visible window" which gives the process foreground
 * eligibility. After a short delay the activity is launched and the overlay is
 * immediately removed.
 */
class FloatingWidgetManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null

    /**
     * Add invisible overlay → launch [EngagementHubActivity] → remove overlay.
     */
    fun presentCallbackScreen(
        phone: String,
        startTime: Date,
        endTime: Date,
        callType: String
    ) {
        val inflater = LayoutInflater.from(context)
        floatView = inflater.inflate(R.layout.bubble_call_panel, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        windowManager = context.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager?.addView(floatView, params)

        // Cancel any lingering call-related notifications
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(
            com.numify.callerid.adkit.runtime.showcase
                .services.EngagementSyncService.NOTIFICATION_ID
        )

        // Short delay so the overlay registers as a foreground window,
        // then launch the activity and clean up.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val intent = Intent(context, EngagementHubActivity::class.java).apply {
                    putExtra("phone", phone)
                    putExtra("start_time", startTime.time)
                    putExtra("end_time", endTime.time)
                    putExtra("call_type", callType)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                remove()
            }
        }, 300)
    }

    fun remove() {
        floatView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) { }
            floatView = null
        }
    }
}
