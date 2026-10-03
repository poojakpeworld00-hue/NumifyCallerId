package com.callerid.numberlookup.home.feature.blocklist

import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.CallerIdCoordinator
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.databinding.DialogEnableCallerIdBinding
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager

/**
 * Blocking needs Caller ID (the CallScreening role). This is the one gate every
 * block entry point goes through - the Blocklist tab and the dialer's Block -
 * so both ask the same way: the animated "Enable Caller ID" sheet, the system
 * role request behind its button, and the blocked action carried out by itself
 * once the role comes back granted.
 *
 * Create it as a field of the fragment that owns it: it registers an activity
 * result, which has to happen before the fragment is started. Call [refresh]
 * from onResume (and from onHiddenChanged for a tab), and [release] from
 * onDestroyView.
 */
class CallerIdGate(private val fragment: Fragment) {

    private var dialog: Dialog? = null

    /** The action that asked for Caller ID, run once it is granted. */
    private var pending: (() -> Unit)? = null

    /** Every looping animator the open sheet started, so it can stop them all. */
    private val animators = mutableListOf<Animator>()

    /**
     * The system role request; whatever it returns, re-check the gate.
     *
     * Granted from here, Caller ID was turned on to *block*, so the ringing
     * pop-up starts off - the user asked for blocking, not a card on every call.
     * Settings' "Incoming call pop-up" turns it on; enabling Caller ID from
     * Settings itself starts it on.
     */
    private val roleLauncher = fragment.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        fragment.context?.let { ctx ->
            if (CallerIdCoordinator.isCallerIdEnabled(ctx)) {
                SettingsRepository(ctx).isIncomingPopupEnabled = false
            }
        }
        refresh()
    }

    /** Runs [action] now when Caller ID is on; otherwise asks first and runs it after. */
    fun require(action: () -> Unit) {
        val ctx = fragment.context ?: return
        if (CallerIdCoordinator.isCallerIdEnabled(ctx)) {
            action()
        } else {
            pending = action
            showDialog()
        }
    }

    /**
     * Re-evaluates the gate. Once Caller ID is on, the prompt is dismissed and
     * the action that asked for it carries on. Called after the role round-trip
     * and on every resume.
     */
    fun refresh() {
        val ctx = fragment.context ?: return
        if (fragment.view == null) return
        if (!CallerIdCoordinator.isCallerIdEnabled(ctx)) return
        dialog?.dismiss()
        dialog = null

        val resume = pending ?: return
        pending = null
        fragment.requireView().post { if (fragment.isAdded && fragment.view != null) resume() }
    }

    /** Tears the sheet down with the view: no leaked window or animators. */
    fun release() {
        cancelAnimators()
        dialog?.dismiss()
        dialog = null
        pending = null
    }

    /**
     * Animated bottom-sheet prompt shown when a block action is attempted while
     * Caller ID is off. Guarded so rapid taps can't stack copies.
     */
    private fun showDialog() {
        if (dialog?.isShowing == true) return

        val view = DialogEnableCallerIdBinding.inflate(fragment.layoutInflater)
        val sheet = Dialog(fragment.requireContext()).apply {
            setContentView(view.root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(R.style.SheetSlideAnimation)
                setDimAmount(0.55f)
            }
        }

        view.buttonNotNow.setOnClickListener {
            pending = null
            sheet.dismiss()
        }
        view.buttonEnable.setOnClickListener {
            sheet.dismiss()
            requestEnable()
        }
        sheet.setOnDismissListener {
            cancelAnimators()
            if (dialog === sheet) dialog = null
        }
        dialog = sheet
        sheet.show()
        animate(view)
    }

    /** Launches the system CallScreening role request (no-op if already held/unavailable). */
    private fun requestEnable() {
        val intent = CallerIdCoordinator.buildEnableIntent(fragment.requireContext()) ?: run {
            refresh(); return
        }
        // We're leaving to a system page — don't let the return trip trigger an App Open ad.
        AppOpenAdManager.skipNextAppOpenAd = true
        runCatching { roleLauncher.launch(intent) }
    }

    /**
     * Drives the dialog's motion once it is on screen: the shield pops in with an
     * overshoot, a ring pulses outward, the checkmark draws itself as an AVD, the
     * hint strip reveals, the CTA breathes, and the demo toggle loops off to on.
     */
    private fun animate(v: DialogEnableCallerIdBinding) {
        v.shieldTile.alpha = 0f
        v.shieldTile.scaleX = 0.4f
        v.shieldTile.scaleY = 0.4f
        v.shieldTile.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(80L).setDuration(440L)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()

        AnimatedVectorDrawableCompat.create(fragment.requireContext(), R.drawable.motion_shield)?.let { avd ->
            v.shieldIcon.setImageDrawable(avd)
            avd.start()
        }

        v.shieldRing.alpha = 0f
        loop(v.shieldRing, View.SCALE_X, 0.7f, 1.5f, 1500L, 220L, DecelerateInterpolator())
        loop(v.shieldRing, View.SCALE_Y, 0.7f, 1.5f, 1500L, 220L, DecelerateInterpolator())
        loop(v.shieldRing, View.ALPHA, 0.7f, 0f, 1500L, 220L, DecelerateInterpolator())

        v.hintStrip.alpha = 0f
        v.hintStrip.translationY = 10f * fragment.resources.displayMetrics.density
        v.hintStrip.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(620L).setDuration(340L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        loop(
            v.buttonEnable, View.SCALE_X, 1f, 1.03f, 1300L, 900L,
            AccelerateDecelerateInterpolator(), ValueAnimator.REVERSE
        )
        loop(
            v.buttonEnable, View.SCALE_Y, 1f, 1.03f, 1300L, 900L,
            AccelerateDecelerateInterpolator(), ValueAnimator.REVERSE
        )

        v.flipTrack.post { if (fragment.isAdded && fragment.view != null) startToggleDemo(v) }
    }

    /** Starts one infinite [ObjectAnimator], registering it for later cancellation. */
    private fun loop(
        target: View,
        property: android.util.Property<View, Float>,
        from: Float,
        to: Float,
        duration: Long,
        startDelay: Long,
        interpolator: android.view.animation.Interpolator,
        repeatMode: Int = ValueAnimator.RESTART
    ) {
        ObjectAnimator.ofFloat(target, property, from, to).apply {
            this.duration = duration
            this.startDelay = startDelay
            this.interpolator = interpolator
            this.repeatCount = ValueAnimator.INFINITE
            this.repeatMode = repeatMode
            animators.add(this)
            start()
        }
    }

    /**
     * Loops the illustrative toggle in the hint strip: track fades grey→green, the
     * thumb slides across with a tap ripple, holds, then resets.
     */
    private fun startToggleDemo(v: DialogEnableCallerIdBinding) {
        val marginStart = (v.flipThumb.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val travel = (v.flipTrack.width - v.flipThumb.width - 2 * marginStart).toFloat()
        if (travel <= 0f) return

        val ctx = fragment.requireContext()
        val offColor = ContextCompat.getColor(ctx, R.color.cid_toggle_off)
        val onColor = ContextCompat.getColor(ctx, R.color.online_green)
        val argb = ArgbEvaluator()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = null // linear; phase timing is handled below
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float

                val on = when {
                    t < 0.30f -> 0f
                    t < 0.40f -> ease((t - 0.30f) / 0.10f)
                    t < 0.86f -> 1f
                    t < 0.96f -> 1f - ease((t - 0.86f) / 0.10f)
                    else -> 0f
                }
                v.flipTrack.backgroundTintList =
                    ColorStateList.valueOf(argb.evaluate(on, offColor, onColor) as Int)
                v.flipThumb.translationX = on * travel

                val r = when {
                    t < 0.30f -> -1f
                    t < 0.52f -> (t - 0.30f) / 0.22f
                    else -> -1f
                }
                if (r in 0f..1f) {
                    v.flipRipple.alpha = (1f - r) * 0.8f
                    val s = 0.4f + r * 1.7f
                    v.flipRipple.scaleX = s
                    v.flipRipple.scaleY = s
                } else {
                    v.flipRipple.alpha = 0f
                }
            }
            animators.add(this)
            start()
        }
    }

    /** Decelerating ease for the toggle demo (1-(1-x)^2). */
    private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x)

    private fun cancelAnimators() {
        animators.forEach { it.cancel() }
        animators.clear()
    }
}
