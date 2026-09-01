package com.numify.callerid.monetize.delivery.engagement

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
import com.numify.callerid.lookup.R
import java.util.Date

/**
 * From Android 14 (API 34) onwards, launching an Activity out of a
 * BroadcastReceiver is refused by the tighter background-activity-launch rules,
 * FLAG_ACTIVITY_NEW_TASK notwithstanding.
 *
 * The way around it: add an invisible TYPE_APPLICATION_OVERLAY window through
 * WindowManager, which needs SYSTEM_ALERT_WINDOW / canDrawOverlays. That overlay
 * counts as a non-app visible window, and that is what makes the process
 * foreground-eligible. After a brief delay the Activity is launched and the
 * overlay is torn straight back down.
 */
class FloatingWidgetManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatView: View? = null

    /**
     * Add invisible overlay → launch [EngagementHubActivity] → remove overlay.
     */
    fun showCallbackScreen(
        phone: String,
        startTime: Date,
        endTime: Date,
        callType: String
    ) {
        val inflater = LayoutInflater.from(context)
        floatView = inflater.inflate(R.layout.overlay_call_panel, null)

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
            com.numify.callerid.monetize.delivery.engagement
                .background.EngagementSyncService.NOTIFICATION_ID
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
