package com.numify.callerid.lookup.feature.exitflow

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.monetize.delivery.BottomSheetNativeAds
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.facebook.shimmer.ShimmerFrameLayout

/**
 * The custom exit confirmation dialog, replacing the plain
 * `MaterialAlertDialogBuilder` that MainShellActivity used to build inline.
 *
 * Two things the old one couldn't do:
 *  - **Ads.** `exit.dialog.isNativeAdShow` / `isBottomAdsType` were in the
 *    Remote Config schema but nothing parsed or rendered them, so a config
 *    asking for a MediumNative in the exit dialog silently got a plain dialog.
 *  - **Localisation.** Copy fell back to English whenever Remote Config carried
 *    a value — which it always does. Now RC text is used when present and the
 *    translated string resource otherwise, per field, so a blank RC field falls
 *    back in the user's own language instead of dropping to English.
 *
 * One live dialog at a time, same as [com.numify.callerid.lookup.permission.lockscreen.LockScreenPrimingDialog].
 */
object ExitConfirmDialog {

    private var current: Dialog? = null

    fun isShowing(): Boolean = current?.isShowing == true

    fun dismissIfShowing() {
        current?.takeIf { it.isShowing }?.dismiss()
        current = null
    }

    /**
     * Shows the dialog. [onExit] fires only when the user confirms — Cancel,
     * back and outside-tap all just dismiss.
     */
    fun show(
        activity: Activity,
        cfg: OnboardingStepConfig.ExitConfig,
        onExit: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed || isShowing()) return

        val view = LayoutInflater.from(activity)
            .inflate(R.layout.sheet_exit_confirm, null, false)

        // RC value when set, else this app's own translated string.
        fun bind(id: Int, remote: String, fallback: Int) {
            view.findViewById<TextView>(id).text =
                remote.ifBlank { activity.getString(fallback) }
        }
        bind(R.id.exitDialogTitleVw, cfg.dialogTitle, R.string.exit_dialog_title)
        bind(R.id.exitDialogDescVw, cfg.dialogDesc, R.string.exit_dialog_desc)
        bind(R.id.exitDialogExitVw, cfg.dialogPositive, R.string.exit_dialog_positive)
        bind(R.id.exitDialogCancelVw, cfg.dialogNegative, R.string.exit_dialog_negative)

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCancelable(true)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }

        var exitTapped = false

        view.findViewById<TextView>(R.id.exitDialogExitVw).setOnClickListener {
            activity.logKeyEvent("Exit_Dialog_Exit")
            exitTapped = true
            dialog.dismiss()
        }
        view.findViewById<TextView>(R.id.exitDialogCancelVw).setOnClickListener {
            activity.logKeyEvent("Exit_Dialog_Cancel")
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            current = null
            if (exitTapped) onExit()
        }

        current = dialog
        dialog.show()

        // After show(), so the container is attached before the loader measures it.
        renderAd(activity, view, cfg)
    }

    /**
     * Fills the dialog's ad slot per `dialog.isBottomAdsType`. The slot stays
     * `GONE` unless an ad is actually requested — an empty framed gap under the
     * message would look broken.
     */
    private fun renderAd(activity: Activity, view: View, cfg: OnboardingStepConfig.ExitConfig) {
        if (!cfg.dialogAdShow) return
        if (!AdPreferenceStore.getInstance(activity).getBoolean("IsAdsON")) return

        val container = view.findViewById<FrameLayout>(R.id.adNativeFrameVw)
        val shimmer = view.findViewById<ShimmerFrameLayout>(R.id.adShimmerVw)

        // The format is remote-driven, so the shimmer placeholder is inflated to
        // match rather than baked into the layout.
        val placeholder = when (cfg.dialogAdType.lowercase()) {
            "bignative" -> R.layout.gads_big_native
            "banner" -> null
            else -> R.layout.gads_mid_native
        }
        placeholder?.let { LayoutInflater.from(activity).inflate(it, shimmer, true) }

        container.visibility = View.VISIBLE

        when (cfg.dialogAdType.lowercase()) {
            "bignative" -> NativeAdPresenter().renderBigNative(activity, container, shimmer)
            "banner" -> BottomSheetNativeAds().renderBannerAd(activity, container)
            else -> NativeAdPresenter().renderMidNative(activity, container, shimmer)
        }
    }
}
