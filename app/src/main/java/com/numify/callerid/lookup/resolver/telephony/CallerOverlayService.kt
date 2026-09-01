package com.numify.callerid.lookup.resolver.telephony

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.resolver.CallEndWatcher
import com.numify.callerid.lookup.resolver.CallerLabel
import com.numify.callerid.lookup.feature.incomingcall.IncomingCallActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts the caller-ID card on screen while a call is ringing.
 *
 * - **Device unlocked**: a floating [WindowManager] overlay
 *   (TYPE_APPLICATION_OVERLAY), which is why the app asks for SYSTEM_ALERT_WINDOW.
 * - **Device locked**: overlays behave unreliably over the keyguard, so it hands
 *   off to [IncomingCallActivity] with showWhenLocked and turnScreenOn, then stops.
 *
 * [CallStateReceiver] starts it on RINGING and stops it on OFFHOOK or IDLE.
 */
class CallerOverlayService : Service() {

    private val TAG = "CallerOverlay"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    /** Self-dismiss the moment the call leaves the ringing/active state. */
    private val callEndWatcher by lazy { CallEndWatcher(this) { stopSelf() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.getStringExtra(EXTRA_NUMBER)?.takeIf { it.isNotBlank() } ?: run {
            stopSelf(); return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — cannot show overlay")
            stopSelf(); return START_NOT_STICKY
        }

        val keyguard = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguard?.isKeyguardLocked == true) {
            // Locked: a show-when-locked activity is the reliable path over the keyguard.
            startActivity(IncomingCallActivity.newIntent(this, number))
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        showOverlay(number)
        callEndWatcher.start()
        return START_STICKY
    }

    private fun showOverlay(number: String) {
        // Replace any previous card (e.g. rapid re-start) before adding a new one.
        removeOverlay()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_caller_id, null)
        view.findViewById<View>(R.id.buttonIncallClose).setOnClickListener { stopSelf() }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            val density = resources.displayMetrics.density
            // Inset from the screen edges so the card doesn't span full width.
            width = resources.displayMetrics.widthPixels - (24 * density).toInt()
        }

        runCatching {
            windowManager?.addView(view, params)
            overlayView = view
        }.onFailure {
            Log.w(TAG, "addView failed", it)
            stopSelf()
            return
        }

        // Resolve caller details off the main thread, then bind.
        scope.launch {
            val info = withContext(Dispatchers.IO) { CallerLabel.resolve(this@CallerOverlayService, number) }
            overlayView?.let { CallerLabel.bind(this@CallerOverlayService, it, number, info) }
        }
    }

    private fun removeOverlay() {
        overlayView?.let { v -> runCatching { windowManager?.removeView(v) } }
        overlayView = null
    }

    /**
     * Keeps the service alive for the duration of the ring. startForeground can be
     * refused once the PHONE_STATE broadcast's exemption has lapsed, which is
     * harmless: the WindowManager overlay does not rely on the foreground service,
     * so the failure is swallowed.
     */
    private fun startAsForeground() {
        val channelId = "caller_id_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(channelId, getString(R.string.incall_service_active), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.incall_service_active))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground refused — running as plain service", it) }
    }

    override fun onDestroy() {
        callEndWatcher.stop()
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NUMBER = "extra_number"

        fun start(context: Context, number: String) {
            val intent = Intent(context, CallerOverlayService::class.java)
                .putExtra(EXTRA_NUMBER, number)
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CallerOverlayService::class.java)) }
        }
    }
}
