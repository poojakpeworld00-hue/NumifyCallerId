package com.contacts.callerid.number.lookup.monetize.delivery.fullpage

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.contacts.callerid.number.lookup.R

object InterstitialAdCache {

    private var dialog: Dialog? = null

    /**
     * Shows a full-screen transparent loader.
     * Checks if activity is alive before showing.
     */
    @Suppress("DEPRECATION")
    fun show(activity: Activity, isLoader: Boolean) {
        if (!isLoader) return

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            // Dismiss existing one if still active
            dismissSafely()

            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setContentView(R.layout.include_fullscreen_loader)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                // FLAG_NOT_FOCUSABLE before show() prevents status-bar flicker
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                // Immersive — hide status bar + nav bar for the loader's life.
                //
                // Through WindowInsetsControllerCompat rather than the
                // SYSTEM_UI_FLAG_* constants this used to set: those are
                // deprecated from API 30, and they were also the last thing in
                // the app still fighting BaseActivity's own controller, which
                // hides the navigation bar for every screen. Two mechanisms
                // aiming at the same bars is how a bar comes back and stays back.
                window?.let { w ->
                    WindowCompat.setDecorFitsSystemWindows(w, false)
                    WindowCompat.getInsetsController(w, w.decorView).apply {
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }

                show()

                // Restore focusability after show so touches register
                window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }

        } catch (e: Exception) {
            Log.e("InterstitialAdCache", "Error showing loader", e)
        }
    }

    fun hide() {
        dismissSafely()
    }

    private fun dismissSafely() {
        try {
            dialog?.let {
                if (it.isShowing) {
                    val context = it.context
                    if (context is Activity) {
                        if (!context.isFinishing && !context.isDestroyed) {
                            it.dismiss()
                        }
                    } else {
                        it.dismiss()
                    }
                }
            }
        } catch (e: Exception) {
            // Log.e("InterstitialAdCache", "Error dismissing loader", e)
        } finally {
            dialog = null
        }
    }
}


