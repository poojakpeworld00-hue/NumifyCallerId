package com.callerid.numberlookup.home.feature.uninstall

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.addCallback
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityUninstallSorryBinding
import com.callerid.numberlookup.home.feature.MainShellActivity
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent

/**
 * "Sorry for the trouble": three quick-fix cards that lead back into the app, a
 * primary "Don't uninstall yet" and a quiet "Still want to uninstall" link.
 *
 * Back behaves like the link. The user asked to uninstall, so back moves the
 * funnel forward, not out of it.
 */
class UninstallSorryActivity : BaseActivity<ActivityUninstallSorryBinding>() {

    override val screenKey: String get() = "UninstallSorryActivity"

    override val layoutId: Int = R.layout.activity_uninstall_sorry

    private val advanced get() = intent.getBooleanExtra(UninstallFlow.EXTRA_ADVANCED, false)
    private val loops = mutableListOf<ValueAnimator>()

    override fun initView() {
        UninstallFlow.applyInsets(binding.root)
        animateHeader()
        listOf(binding.fixCard1, binding.fixCard2, binding.fixCard3)
            .forEachIndexed { i, card -> enterUp(card, 80L + i * 70L) }
        ScreenPlacementPlan.showAd(screenKey, this, binding.adNativeFrame, binding.adShimmer)

        // Each fix lands on the thing that fixes it.
        binding.btnFix1.setOnClickListener { keep("fix_caller_id", MainShellActivity.TARGET_OVERLAY) }
        binding.btnFix2.setOnClickListener { keep("fix_lookup", MainShellActivity.TARGET_LOOKUP) }
        binding.btnFix3.setOnClickListener { keep("fix_contacts", MainShellActivity.TARGET_CONTACTS) }
        binding.btnDontUninstall.setOnClickListener { keep("dont_uninstall", null) }
        binding.tvStillUninstall.setOnClickListener { next() }
        onBackPressedDispatcher.addCallback(this) { next() }
    }

    /** The tile breathes and the face tilts side to side, both on a 3s loop. */
    private fun animateHeader() {
        loops += ObjectAnimator.ofFloat(binding.headerTile, View.SCALE_X, 1f, 1.08f).loop(1500)
        loops += ObjectAnimator.ofFloat(binding.headerTile, View.SCALE_Y, 1f, 1.08f).loop(1500)
        loops += ObjectAnimator.ofFloat(binding.headerIcon, View.ROTATION, -6f, 6f).loop(1500)
        loops.forEach { it.start() }
    }

    private fun ObjectAnimator.loop(half: Long) = apply {
        duration = half
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }

    private fun enterUp(view: View, delay: Long) {
        view.alpha = 0f
        view.translationY = 10f * resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(400)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun keep(event: String, target: String?) {
        recordEvent("uninstall_sorry_$event")
        UninstallFlow.keepApp(this, target)
    }

    private fun next() {
        recordEvent("uninstall_sorry_still")
        startActivity(UninstallReasonActivity.newIntent(this, advanced))
    }

    override fun onDestroy() {
        loops.forEach { it.cancel() }
        super.onDestroy()
    }

    companion object {
        fun newIntent(ctx: Context, advanced: Boolean): Intent =
            Intent(ctx, UninstallSorryActivity::class.java)
                .putExtra(UninstallFlow.EXTRA_ADVANCED, advanced)
    }
}
