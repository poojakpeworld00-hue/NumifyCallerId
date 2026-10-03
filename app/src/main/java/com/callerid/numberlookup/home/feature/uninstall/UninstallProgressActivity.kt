package com.callerid.numberlookup.home.feature.uninstall

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityUninstallProgressBinding
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan

/**
 * The advance route's step after the survey: a ring fills 0→100 around a shaking
 * bin while three steps tick off, the bin turns into a green check, a "ready"
 * toast slides up, and the thank-you screen follows, which closes the app.
 * Back is swallowed; the ring always finishes.
 *
 * The wording is "closing", never "uninstalling": nothing is removed here, and
 * the screen says uninstalling is still there in Settings.
 */
class UninstallProgressActivity : BaseActivity<ActivityUninstallProgressBinding>() {

    override val screenKey: String get() = "UninstallProgressActivity"

    override val layoutId: Int = R.layout.activity_uninstall_progress

    private val running = mutableListOf<Animator>()
    private var shake: ObjectAnimator? = null
    private val spinners = mutableMapOf<ImageView, ObjectAnimator>()
    private var ready = false
    private val handler = Handler(Looper.getMainLooper())

    private val stepViews by lazy {
        listOf(
            binding.ivStep1 to binding.tvStep1,
            binding.ivStep2 to binding.tvStep2,
            binding.ivStep3 to binding.tvStep3,
        )
    }

    override fun initView() {
        UninstallFlow.applyInsets(binding.root)
        binding.tvProgressTitle.text = getString(R.string.un_progress_title, getString(R.string.app_name))
        render(0)
        shake = ObjectAnimator.ofFloat(binding.ivBin, View.ROTATION, -10f, 10f).apply {
            duration = 300
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        ScreenPlacementPlan.showAd(screenKey, this, binding.adNativeFrame, binding.adShimmer)
        UninstallAds.preloadInter(this)
        onBackPressedDispatcher.addCallback(this) { }

        start(ValueAnimator.ofInt(0, 100).apply {
            duration = PROGRESS_MS
            interpolator = LinearInterpolator()
            addUpdateListener { render(it.animatedValue as Int) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = onReady()
            })
        })
    }

    private fun render(pct: Int) {
        binding.ring.progress = pct
        binding.tvPercent.text = getString(R.string.un_percent, pct)
        stepViews.forEachIndexed { i, (icon, label) ->
            val done = pct >= STEP_AT[i]
            val current = !done && (i == 0 || pct >= STEP_AT[i - 1])
            paintStep(icon, label, done, current)
        }
    }

    private fun paintStep(icon: ImageView, label: TextView, done: Boolean, current: Boolean) {
        val state = when {
            done -> 2
            current -> 1
            else -> 0
        }
        if (icon.tag == state) return
        icon.tag = state
        // Stop this step's spinner before resetting, or its next frame re-tilts the icon.
        spinners.remove(icon)?.cancel()
        icon.rotation = 0f
        val (drawable, tint) = when (state) {
            2 -> R.drawable.ic_un_g_check_circle to R.color.un_success
            1 -> R.drawable.ic_un_g_progress to R.color.primary
            else -> R.drawable.ic_un_g_radio_off to R.color.un_step_idle
        }
        icon.setImageResource(drawable)
        icon.imageTintList = ContextCompat.getColorStateList(this, tint)
        label.setTextColor(
            ContextCompat.getColor(this, if (state == 0) R.color.un_step_idle_text else R.color.un_ink)
        )
        if (state == 1) spin(icon)
    }

    private fun spin(icon: ImageView) {
        val spinner = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 360f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
        spinners[icon] = spinner
        start(spinner)
    }

    private fun onReady() {
        if (ready || isFinishing || isDestroyed) return
        ready = true
        shake?.cancel()
        binding.ivBin.rotation = 0f

        val blue = ContextCompat.getColor(this, R.color.primary)
        val green = ContextCompat.getColor(this, R.color.un_success)
        start(ValueAnimator.ofObject(ArgbEvaluator(), blue, green).apply {
            duration = 300
            addUpdateListener { binding.ring.arcColor = it.animatedValue as Int }
        })
        binding.binTile.backgroundTintList = ContextCompat.getColorStateList(this, R.color.un_ready_bg)
        binding.ivBin.setImageResource(R.drawable.ic_un_tick)
        binding.ivBin.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.un_ready_fg))
        binding.ivBin.scaleX = 0.4f
        binding.ivBin.scaleY = 0.4f
        binding.ivBin.animate().scaleX(1f).scaleY(1f).setDuration(400)
            .setInterpolator(OvershootInterpolator(2f)).start()
        binding.tvProgressTitle.setText(R.string.un_progress_done)
        showReadyToast()
    }

    private fun showReadyToast() {
        val toast = binding.readyToast
        toast.visibility = View.VISIBLE
        toast.alpha = 0f
        toast.translationY = 20f * resources.displayMetrics.density
        toast.animate().alpha(1f).translationY(0f).setDuration(400)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                handler.postDelayed({
                    if (isFinishing || isDestroyed) return@postDelayed
                    // On to the thank-you, which ends on App info.
                    UninstallAds.showInterThen(this, UninstallFlow.PAGE_PROGRESS) {
                        startActivity(
                            UninstallThanksActivity.newIntent(
                                this, advanced = true, reason = intent.getStringExtra(EXTRA_REASON)
                            ).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                        )
                        finish()
                    }
                }, TOAST_MS)
            }
            .start()
    }

    private fun start(animator: Animator) {
        running.add(animator)
        animator.start()
    }

    override fun onDestroy() {
        // Listeners first, so a cancelled animator cannot navigate from a dead screen.
        shake?.cancel()
        running.forEach { it.removeAllListeners(); it.cancel() }
        running.clear()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_MS = 3500L
        private const val TOAST_MS = 1200L

        /** Percent at which each step ticks off. */
        private val STEP_AT = intArrayOf(20, 60, 100)

        /** The reason picked on the survey, carried on to the thank-you screen. */
        private const val EXTRA_REASON = "uninstall_reason"

        fun newIntent(ctx: Context, reason: String?): Intent =
            Intent(ctx, UninstallProgressActivity::class.java)
                .putExtra(UninstallFlow.EXTRA_ADVANCED, true)
                .putExtra(EXTRA_REASON, reason)
    }
}
