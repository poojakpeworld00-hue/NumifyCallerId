package com.callerid.numberlookup.home.feature.uninstall

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityUninstallThanksBinding
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent

/**
 * Thank-you, echoing the picked reason - the last in-app screen on both routes
 * (the advance route reaches it after the progress ring). After `thanks_delay`
 * seconds, or on Continue or back, simple opens the system App-info page and
 * advance closes the app.
 */
class UninstallThanksActivity : BaseActivity<ActivityUninstallThanksBinding>() {

    override val screenKey: String get() = "UninstallThanksActivity"

    override val layoutId: Int = R.layout.activity_uninstall_thanks

    private val advanced get() = intent.getBooleanExtra(UninstallFlow.EXTRA_ADVANCED, false)
    private val handler = Handler(Looper.getMainLooper())
    private val loops = mutableListOf<Animator>()
    private var left = false
    private var pendingLeave = false

    override fun initView() {
        UninstallFlow.applyInsets(binding.root)
        val reason = intent.getStringExtra(EXTRA_REASON)
        if (reason.isNullOrBlank()) {
            binding.chipSaid.visibility = View.GONE
        } else {
            binding.tvSaid.text = getString(R.string.un_you_said, reason)
        }
        if (advanced) binding.tvSub.setText(R.string.un_thanks_sub_close)
        animateHeart()
        listOf(binding.tvTitle, binding.tvSub, binding.chipSaid)
            .forEachIndexed { i, v -> enterUp(v, 200L + i * 100L) }
        ScreenPlacementPlan.showAd(screenKey, this, binding.adNativeFrame, binding.adShimmer)
        UninstallAds.preloadInter(this)

        binding.btnContinue.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this) { leave() }

        handler.postDelayed({
            // An ad click may have taken the user elsewhere: wait until they are back.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) leave() else pendingLeave = true
        }, UninstallFlow.thanksDelayMs())
    }

    override fun onResume() {
        super.onResume()
        if (pendingLeave) leave()
    }

    /** The tile pops in, then the heart beats; the glow breathes and a ring pulses out. */
    private fun animateHeart() {
        val tile = binding.heartTile
        tile.scaleX = 0.4f
        tile.scaleY = 0.4f
        tile.alpha = 0f
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(600)
            .setInterpolator(OvershootInterpolator(2.2f)).start()

        loops += ObjectAnimator.ofPropertyValuesHolder(
            binding.heartGlow,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f),
        ).apply {
            duration = 1300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
        }
        loops += ObjectAnimator.ofPropertyValuesHolder(
            binding.heartRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1.35f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1.35f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f),
        ).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
        }
        loops += AnimatorSet().apply {
            val heart = binding.ivHeart
            playSequentially(
                ObjectAnimator.ofPropertyValuesHolder(heart,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f)).setDuration(420),
                ObjectAnimator.ofPropertyValuesHolder(heart,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1.18f, 0.96f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.18f, 0.96f)).setDuration(420),
                ObjectAnimator.ofPropertyValuesHolder(heart,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 0.96f, 1f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.96f, 1f)).setDuration(560),
            )
            startDelay = 600
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isFinishing && !isDestroyed) {
                        startDelay = 0
                        start()
                    }
                }
            })
        }
        loops.forEach { it.start() }
    }

    private fun enterUp(view: View, delay: Long) {
        view.alpha = 0f
        view.translationY = 10f * resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(450)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun leave() {
        if (left || isFinishing || isDestroyed) return
        left = true
        handler.removeCallbacksAndMessages(null)
        // Last screen on both routes: the advance route's progress ring has
        // already run before this one.
        UninstallAds.showInterThen(this, UninstallFlow.PAGE_THANKS) {
            if (advanced) {
                // Advance never opens App info: the app closes and leaves Recents.
                recordEvent("uninstall_app_close")
                UninstallFlow.closeApp(this)
            } else {
                recordEvent("uninstall_app_info_open")
                UninstallFlow.openAppInfo(this)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loops.forEach { it.removeAllListeners(); it.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REASON = "uninstall_reason"

        fun newIntent(ctx: Context, advanced: Boolean, reason: String? = null): Intent =
            Intent(ctx, UninstallThanksActivity::class.java)
                .putExtra(UninstallFlow.EXTRA_ADVANCED, advanced)
                .putExtra(EXTRA_REASON, reason)
    }
}
