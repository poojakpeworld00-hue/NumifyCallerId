package com.numify.callerid.lookup.feature.incomingcall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.resolver.CallEndWatcher
import com.numify.callerid.lookup.resolver.CallerLabel
import com.numify.callerid.monetize.delivery.SystemDialogHelper
import com.numify.callerid.lookup.resolver.telephony.CallStateReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen caller-ID card, shown when a call rings while the device is
 * **locked**. It is declared with showWhenLocked and turnScreenOn so it surfaces
 * over the keyguard.
 *
 * [CallerOverlayService] starts this rather than the floating overlay whenever the
 * keyguard is up, and it dismisses itself when [CallStateReceiver] broadcasts the
 * end of the call.
 */
class IncomingCallActivity : AppCompatActivity() {

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }

    /** Backup to the broadcast: dismiss the moment the call leaves the active state. */
    private val callEndWatcher by lazy { CallEndWatcher(this) { finish() } }

    /** Dismisses the locked-screen card when the user leaves via Home / Recents. */
    private val systemDialogHelper by lazy {
        SystemDialogHelper(this) {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    /** Finishes the screen as soon as the call stops ringing. */
    private val endReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CallStateReceiver.ACTION_CALL_ENDED) finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        setContentView(R.layout.activity_incoming_call)

        if (number.isBlank()) {
            finish(); return
        }

        val card = findViewById<View>(R.id.incallCard)
        card.findViewById<View>(R.id.buttonIncallClose).setOnClickListener { finish() }

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                CallerLabel.resolve(
                    this@IncomingCallActivity,
                    number
                )
            }
            CallerLabel.bind(this@IncomingCallActivity, card, number, info)

            // Same two-step as the floating overlay: the card shows immediately
            // from local data, and the caller-ID API fills in the name, carrier
            // and spam verdict if it answers in time. A saved contact keeps the
            // user's own name.
            if (!info.known) {
                val facts = withContext(Dispatchers.IO) {
                    CallerLabel.lookupNetworkFacts(this@IncomingCallActivity, number)
                }
                CallerLabel.applyNetworkFacts(this@IncomingCallActivity, card, info, facts)
            }
        }

        val filter = IntentFilter(CallStateReceiver.ACTION_CALL_ENDED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(endReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(endReceiver, filter)
        }
        callEndWatcher.start()
        lifecycle.addObserver(systemDialogHelper)
    }

    @Suppress("DEPRECATION")
    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        isActive = true
        NotificationManagerCompat.from(this).cancelAll()
    }

    override fun onPause() {
        super.onPause()
        isActive = false
    }

    override fun onDestroy() {
        isActive = false
        callEndWatcher.stop()
        runCatching { unregisterReceiver(endReceiver) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_NUMBER = "extra_number"

        /**
         * True while the locked-screen incoming card is in the foreground —
         * CallStateReceiver checks it to suppress a duplicate post-call notification (B2).
         */
        @Volatile
        var isActive = false

        fun newIntent(context: Context, number: String): Intent =
            Intent(context, IncomingCallActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
