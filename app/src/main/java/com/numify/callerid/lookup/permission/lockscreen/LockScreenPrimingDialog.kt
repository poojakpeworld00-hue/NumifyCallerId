package com.numify.callerid.lookup.permission.lockscreen

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.permission.PermissionCoordinator
import com.numify.callerid.lookup.feature.MainShellActivity

/**
 * The MainShellActivity Full-Screen-Intent priming dialog. Same visual language as the
 * Screen (incoming-call preview + remote-driven copy). "Enable" opens the system
 * FSI page in-task (via MainShellActivity's launcher) and arms [LockScreenWatchService]; the
 * dialog dismisses so the auto-return lands on a clean MainShellActivity.
 *
 * A single live dialog is tracked so the host can [dismissIfShowing] on return.
 */
object LockScreenPrimingDialog {

    private var current: Dialog? = null

    fun isShowing(): Boolean = current?.isShowing == true

    /**
     * Shows the priming dialog. [onFinished] fires once when it closes, with
     * `enabled = true` if the user tapped **Enable** (and is being taken to the
     * system FSI page) or `false` for **Not now** / cancel / outside-tap — so the
     * host can sequence what comes next (e.g. a follow-up permission sheet).
     */
    fun show(
        activity: Activity,
        config: LockScreenConfig,
        onFinished: ((enabled: Boolean) -> Unit)? = null,
    ) {
        if (activity.isFinishing || isShowing()) return

        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_fsi_permission, null, false)
        view.findViewById<TextView>(R.id.fsiDialogTitle).text = config.dialog.title
        view.findViewById<TextView>(R.id.fsiDialogDesc).text = config.dialog.desc
        view.findViewById<TextView>(R.id.fsiDialogButton).text = config.dialog.button

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCancelable(true)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
        }

        // True only when the user chose Enable (we're navigating to the FSI page),
        // so the dismiss listener can tell Enable apart from Not now / cancel.
        var enableTapped = false

        view.findViewById<TextView>(R.id.fsiDialogButton).setOnClickListener {
            activity.logKeyEvent("FSI_Dialog_Enable")
            // Close the dialog first, then ask notification (targeted request), and
            // only after that launch FSI settings in-task via MainShellActivity's
            // launcher. The watcher + MainShellActivity.onResume handle the return.
            enableTapped = true
            dialog.dismiss()
            PermissionCoordinator.request(activity, "notification") {
                (activity as? MainShellActivity)?.openFsiSettings()
            }
        }
        view.findViewById<TextView>(R.id.fsiDialogLater).setOnClickListener {
            activity.logKeyEvent("FSI_Dialog_NotNow")
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            current = null
            onFinished?.invoke(enableTapped)
        }
        current = dialog
        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        animateIn(view)
        activity.logKeyEvent("FSI_Dialog_Show")
    }

    /**
     * Card springs up from the bottom; the glow breathes, the orbit rings and the
     * avatar rings pulse outward, and the CTA breathes — the same halo motion as
     * the intro Screen, at dialog scale.
     */
    private fun animateIn(root: View) {
        val dy = 40f * root.resources.displayMetrics.density
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.translationY = dy
        root.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setInterpolator(OvershootInterpolator(1.2f))
            .setDuration(560)
            .start()

        loopGlow(root.findViewById(R.id.fsiDialogGlow))
        loopRing(root.findViewById(R.id.fsiDialogOrbit1), 0L, 0.85f, 1.7f, 0.55f, 2600L)
        loopRing(root.findViewById(R.id.fsiDialogOrbit2), 900L, 0.85f, 1.7f, 0.55f, 2600L)
        loopRing(root.findViewById(R.id.fsiAvatarRing1), 0L, 0.9f, 1.4f, 0.7f, 2200L)
        loopRing(root.findViewById(R.id.fsiAvatarRing2), 700L, 0.9f, 1.4f, 0.7f, 2200L)
        root.findViewById<View>(R.id.fsiDialogButton)?.let { loopCta(it) }
    }

    /** Expanding ring pulse (scale up + fade out), repeating while the dialog is showing. */
    private fun loopRing(v: View?, delay: Long, from: Float, to: Float, alpha: Float, dur: Long) {
        v ?: return
        v.postDelayed({
            fun cycle() {
                if (!isShowing()) return
                v.scaleX = from; v.scaleY = from; v.alpha = alpha
                v.animate().scaleX(to).scaleY(to).alpha(0f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(dur)
                    .withEndAction { cycle() }
                    .start()
            }
            cycle()
        }, delay)
    }

    /** Ambient glow alpha breathe. */
    private fun loopGlow(v: View?) {
        v ?: return
        if (!isShowing()) return
        v.alpha = 0.5f
        v.animate().alpha(0.85f).setDuration(1300)
            .withEndAction {
                if (!isShowing()) return@withEndAction
                v.animate().alpha(0.5f).setDuration(1300)
                    .withEndAction { loopGlow(v) }
                    .start()
            }.start()
    }

    /** Subtle idle breathe on the CTA to pull the tap. */
    private fun loopCta(v: View) {
        if (!isShowing()) return
        v.animate().scaleX(1.02f).scaleY(1.05f).setDuration(1300)
            .withEndAction {
                if (!isShowing()) return@withEndAction
                v.animate().scaleX(1f).scaleY(1f).setDuration(1300)
                    .withEndAction { loopCta(v) }
                    .start()
            }.start()
    }

    /** Dismiss the live dialog if any (e.g. after the FSI grant auto-returns the app). */
    fun dismissIfShowing() {
        runCatching { current?.dismiss() }
        current = null
    }
}
