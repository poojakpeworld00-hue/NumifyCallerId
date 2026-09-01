package com.numify.callerid.numberlookup.screen.welcome

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import com.numify.callerid.adkit.policy.logKeyEvent
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.adkit.runtime.interstitial.StandardInterstitial
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.store.SettingsVault
import com.numify.callerid.numberlookup.databinding.ScreenOnboardingBinding
import com.numify.callerid.numberlookup.access.AccessEngine
import com.numify.callerid.numberlookup.screen.HomeShellActivity
import com.numify.callerid.numberlookup.screen.gate.FlowFooterAd
import com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig
import com.numify.callerid.numberlookup.kit.followAdContainer

class WelcomeActivity : CoreActivity<ScreenOnboardingBinding>() {

    override val layoutId: Int = R.layout.screen_onboarding

    private val prefs by lazy { SettingsVault(this) }
    private val pages = WelcomeSlides.all
    private val dots = mutableListOf<View>()

    // Spring settle (dampingRatio 0.8 / stiffness 380 ≈ this overshoot) — the design's
    // default motion, used for the dot pill stretch and the CTA morph.
    private val spring = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)
    private var wasLast = false

    /** One-shot guard so Skip/Next/back/autonext can't fire the forward flow twice. */
    private var forwarding = false

    private val autonextHandler = Handler(Looper.getMainLooper())

    override fun initView() {
        // Record this intro show for the once/count frequency gate.
        OnboardingFlowConfig.markShown(this, OnboardingFlowConfig.ONBOARDING_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Big native ad pinned at the bottom of the onboarding flow.
        FlowFooterAd.render(
            activity = this,
            screenKey = OnboardingFlowConfig.ONBOARDING_KEY,
            container = binding.adNativeFrameVw,
            shimmer = binding.adShimmerVw,
            divider = binding.adNativeDividerVw,
            fallbackType = "MediumNativeAlt",
        )

        binding.vuPager.adapter = WelcomeAdapter(pages)
        binding.vuPager.offscreenPageLimit = 1
        buildDots()
        updateDots(0)

        // Parallax: the illustration tracks the swipe fully; the title (0.85×) and
        // body (0.7×) lag behind it as they scroll.
        binding.vuPager.setPageTransformer { page, position ->
            val w = page.width.toFloat()
            page.findViewById<View?>(R.id.lblTitle)?.translationX = position * w * 0.15f
            page.findViewById<View?>(R.id.lblDesc)?.translationX = position * w * 0.30f
        }

        binding.vuPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })

        binding.padSkip.setOnClickListener { finishOnboarding() }
        binding.padNext.setOnClickListener {
            val current = binding.vuPager.currentItem
            if (current < pages.lastIndex) {
                binding.vuPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        // Back walks FORWARD through the pages (1 → 2 → 3) with the pager's smooth
        // scroll, so every page is seen before the user can leave. Only once the
        // last page is showing does back skip out — same path as the Skip button.
        // The callback stays enabled so back never falls through to CoreActivity's
        // exit handler; `finishOnboarding()` itself guards against a double finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.vuPager.currentItem
                if (current < pages.lastIndex) {
                    binding.vuPager.setCurrentItem(current + 1, true)
                } else {
                    finishOnboarding()
                }
            }
        })

        // `screen.onboarding.autonext` (seconds, 0 = disabled): auto-advance exactly
        // like Skip/Next-on-last-page if the user hasn't interacted by then.
        val autonextSec = OnboardingFlowConfig.stepConfig(this, OnboardingFlowConfig.ONBOARDING_KEY)
            ?.autonextSec ?: 0
        if (autonextSec > 0) {
            autonextHandler.postDelayed({ finishOnboarding() }, autonextSec * 1000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autonextHandler.removeCallbacksAndMessages(null)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildDots() {
        val size = dp(8)
        val gap = dp(5)
        pages.indices.forEach { _ ->
            val dot = View(this)
            val lp = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
            dot.layoutParams = lp
            binding.dotsVw.addView(dot)
            dots.add(dot)
        }
    }

    private fun updateDots(active: Int) {
        val activeWidth = dp(24)
        val size = dp(8)
        dots.forEachIndexed { i, dot ->
            // Active dot stretches into a pill; the others shrink back — spring settle.
            animateDotWidth(dot, if (i == active) activeWidth else size)
            dot.setBackgroundResource(
                if (i == active) R.drawable.shape_dot_active else R.drawable.shape_dot
            )
        }
        // Final step: the CTA morphs to "Get Started" on the hero gradient — the
        // one place onboarding uses the gradient (Visual System rule).
        val last = active == pages.lastIndex
        binding.padNext.setText(if (last) R.string.onboarding_get_started else R.string.onboarding_next)
        binding.padNext.setBackgroundResource(
            if (last) R.drawable.shape_btn_gradient else R.drawable.shape_btn_primary
        )
        if (last != wasLast) {
            wasLast = last
            popCta()
        }
    }

    /** Springs a dot's width to [target] (the pill-stretch shared-bounds effect). */
    private fun animateDotWidth(dot: View, target: Int) {
        val lp = dot.layoutParams as LinearLayout.LayoutParams
        if (lp.width == target) return
        (dot.getTag(R.id.tag_dot_anim) as? ValueAnimator)?.cancel()
        ValueAnimator.ofInt(lp.width, target).apply {
            duration = 320L
            interpolator = spring
            addUpdateListener {
                lp.width = it.animatedValue as Int
                dot.layoutParams = lp
            }
            dot.setTag(R.id.tag_dot_anim, this)
            start()
        }
    }

    /** Width/scale spring when the CTA morphs between Next and Get Started. */
    private fun popCta() {
        binding.padNext.animate().cancel()
        binding.padNext.scaleX = 0.94f
        binding.padNext.scaleY = 0.94f
        binding.padNext.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(spring)
            .setDuration(340L)
            .start()
    }

    private fun finishOnboarding() {
        if (forwarding) return
        forwarding = true
        prefs.isOnboardingDone = true
        logKeyEvent("onboarding_completed")
        AccessEngine.checkScreenPermissions(this, OnboardingFlowConfig.ONBOARDING_KEY) {
            val nextKey = OnboardingFlowConfig.nextEligibleAfter(this, OnboardingFlowConfig.ONBOARDING_KEY)
            val nextClass = nextKey?.let { OnboardingFlowConfig.classFor(it) } ?: HomeShellActivity::class.java
            val proceed: () -> Unit = {
                startActivity(Intent(this, nextClass))
                finish()
            }
            // Permission done → show the interstitial (only when
            // `screen.onboarding.isInterShow` is on) → THEN navigate.
            val isInterShow = OnboardingFlowConfig.stepConfig(this, OnboardingFlowConfig.ONBOARDING_KEY)
                ?.isInterShow ?: false
            if (isInterShow) {
                StandardInterstitial().presentInterstitial(this) { proceed() }
            } else {
                proceed()
            }
        }
    }
}
