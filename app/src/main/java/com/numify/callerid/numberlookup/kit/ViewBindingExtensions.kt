package com.numify.callerid.numberlookup.kit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Outline
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.numify.callerid.adkit.runtime.interstitial.TransitionInterstitialAd

inline fun <reified T : Activity> Context.openActivity(
    clearTop: Boolean = false,
    isShowAd: Boolean = true,
    extras: Bundle? = null
) {
    val intent = Intent(this, T::class.java)
    extras?.let { intent.putExtras(it) }
    if (clearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val activity = this as? Activity
    if (isShowAd && activity != null) {
        try {
            TransitionInterstitialAd().presentInterstitial(activity) {
                activity.startActivity(intent)
            }
        } catch (_: Exception) {
            startActivity(intent)
        }
    } else {
        startActivity(intent)
    }
}

fun Context.openActivity(intent: Intent, isShowAd: Boolean = true) {
    val activity = this as? Activity
    if (isShowAd && activity != null) {
        try {
            TransitionInterstitialAd().presentInterstitial(activity) {
                activity.startActivity(intent)
            }
        } catch (_: Exception) {
            startActivity(intent)
        }
    } else {
        startActivity(intent)
    }
}

/**
 * Configure-lambda overload — drop-in replacement for the old `launch<T> {}`
 * helper. Build the Intent inline, then route through the standard
 * interstitial-aware launcher.
 */
inline fun <reified T : Activity> Context.openActivity(
    clearTop: Boolean = false,
    isShowAd: Boolean = true,
    crossinline configure: Intent.() -> Unit
) {
    val intent = Intent(this, T::class.java).apply(configure)
    if (clearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    openActivity(intent, isShowAd)
}

fun View.triggerClick(onClick: (View?) -> Unit) {
    setOnClickListener { view ->
        // Haptic feedback
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        // Click animation: shrink and restore
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()

                // Call your click listener after animation
                onClick.invoke(view)
            }.start()
    }
}

/**
 * Crops this view — and everything inside it — to the largest circle that fits
 * its bounds, so a rectangular child (an emoji flag, a photo) reads as round.
 *
 * Done in code rather than with `android:clipToOutline` because that attribute
 * is only honoured from API 31; `setClipToOutline` itself goes back to API 21.
 * Re-measures with the view, so it survives rows being recycled at a new size.
 */
/**
 * Clips a view to its own bounds via the outline (hardware) clip.
 *
 * Needed when an ancestor sets `clipChildren="false"`: that switches clipping
 * off for the whole subtree below it, so setting `android:clipChildren="true"`
 * back on this view has no effect. The outline clip is applied by the renderer
 * regardless of what any ancestor asked for.
 */
fun View.clipToRect() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRect(0, 0, view.width, view.height)
        }
    }
    clipToOutline = true
}

fun View.clipToCircle() {
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val size = minOf(view.width, view.height)
            val left = (view.width - size) / 2
            val top = (view.height - size) / 2
            outline.setOval(left, top, left + size, top + size)
        }
    }
    clipToOutline = true
}

fun EditText.onDone(callback: () -> Unit) {
    this.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            callback()
            true
        } else {
            false
        }
    }
}
