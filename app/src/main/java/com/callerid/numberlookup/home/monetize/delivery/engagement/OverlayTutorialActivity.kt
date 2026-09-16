package com.callerid.numberlookup.home.monetize.delivery.engagement

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.callerid.numberlookup.home.R

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Transparent hint screen shown next to the system "Appear on top" Settings page.
 * It lives in the caller's task and puts up a bottom card pointing at the toggle.
 * The moment overlay permission is granted it finishes itself, so the user drops
 * cleanly back to the caller without an extra tap.
 *
 * Tapping anywhere outside the card dismisses the hint too.
 */
class OverlayTutorialActivity : AppCompatActivity() {

    companion object {
        private const val AUTO_DISMISS_MS = 3_000L
    }

    private var pollJob: Job? = null
    private var autoDismissJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_overlay_tutorial)

        val root = findViewById<View>(R.id.columnMain)

        // Edge-to-edge is forced on Android 15+/16 (targetSdk 37), so the bottom
        // hint card would otherwise draw behind the navigation bar. Pad the root
        // by the system-bar insets so the card floats above the nav bar (and
        // clears side/gesture insets in landscape).
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Translucent windows can miss the initial inset pass — force one.
        ViewCompat.requestApplyInsets(root)

        root?.setOnClickListener {
            finish()
        }

        // Slide the card up on entry. The window itself is translucent and the dim
        // fades in on its own, so animating the card is what makes it read as a
        // sheet rising over the Settings page rather than a frame-one pop-in.
        findViewById<View>(R.id.overlayCard)?.apply {
            alpha = 0f
            post {
                translationY = height.toFloat()
                animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }

        // Auto-dismiss after 3s. Lives on lifecycleScope so it cancels on
        // destroy, and runs once per activity instance — pausing (e.g. user
        // pulled down the notification shade) does not reset the timer.
        autoDismissJob = lifecycleScope.launch {
            delay(AUTO_DISMISS_MS)
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user toggled overlay ON while we were paused (because Settings
        // was on top), close ourselves so the caller's UI is fully visible.
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (Settings.canDrawOverlays(this@OverlayTutorialActivity)) {
                    finish()
                    return@launch
                }
                delay(500L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }
}
