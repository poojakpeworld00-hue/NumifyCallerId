package com.numify.callerid.adkit.runtime.interstitial

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.numify.callerid.numberlookup.R

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
                setContentView(R.layout.fullscreen_spinner)
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

                // Immersive sticky — hide status bar + nav bar
                window?.decorView?.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

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


