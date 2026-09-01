package com.callora.callerid.numberlookup.screen.ringing

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
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.engine.CallCloseWatcher
import com.callora.callerid.numberlookup.engine.CallerBadge
import com.callora.callerid.adkit.runtime.SystemPromptHelper
import com.callora.callerid.numberlookup.engine.incoming.PhoneStateReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen caller-ID card shown when a call rings while the device is **locked**.
 * Declared with showWhenLocked + turnScreenOn so it appears over the keyguard.
 *
 * [CallerBubbleService] launches this instead of the floating overlay when the
 * keyguard is up; it self-dismisses when [PhoneStateReceiver] broadcasts call end.
 */
class RingScreenActivity : AppCompatActivity() {

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }

    /** Backup to the broadcast: dismiss the moment the call leaves the active state. */
    private val callEndWatcher by lazy { CallCloseWatcher(this) { finish() } }

    /** Dismisses the locked-screen card when the user leaves via Home / Recents. */
    private val systemDialogHelper by lazy {
        SystemPromptHelper(this) {
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    /** Finishes the screen as soon as the call stops ringing. */
    private val endReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PhoneStateReceiver.ACTION_CALL_ENDED) finish()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockscreen()
        setContentView(R.layout.screen_incoming_call)

        if (number.isBlank()) {
            finish(); return
        }

        val card = findViewById<View>(R.id.incallCardVw)
        card.findViewById<View>(R.id.padIncallClose).setOnClickListener { finish() }

        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) {
                CallerBadge.resolve(
                    this@RingScreenActivity,
                    number
                )
            }
            CallerBadge.bind(this@RingScreenActivity, card, number, info)
        }

        val filter = IntentFilter(PhoneStateReceiver.ACTION_CALL_ENDED)
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
         * PhoneStateReceiver checks it to suppress a duplicate post-call notification (B2).
         */
        @Volatile
        var isActive = false

        fun newIntent(context: Context, number: String): Intent =
            Intent(context, RingScreenActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
