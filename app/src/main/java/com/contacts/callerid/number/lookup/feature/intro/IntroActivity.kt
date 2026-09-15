package com.contacts.callerid.number.lookup.feature.intro

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
import com.contacts.callerid.number.lookup.monetize.strategy.recordEvent
import com.contacts.callerid.number.lookup.monetize.delivery.NativeAdPresenter
import com.contacts.callerid.number.lookup.monetize.delivery.fullpage.TransitionInterstitialAd
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.databinding.ActivityOnboardingBinding
import com.contacts.callerid.number.lookup.permission.PermissionCoordinator
import com.contacts.callerid.number.lookup.feature.MainShellActivity
import com.contacts.callerid.number.lookup.feature.onboarding.OnboardingFooterAd
import com.contacts.callerid.number.lookup.feature.onboarding.OnboardingStepConfig
import com.contacts.callerid.number.lookup.common.followAdContainer

class IntroActivity : BaseActivity<ActivityOnboardingBinding>() {

    override val layoutId: Int = R.layout.activity_onboarding

    private val prefs by lazy { SettingsRepository(this) }
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
        OnboardingStepConfig.markShown(this, OnboardingStepConfig.ONBOARDING_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Big native ad pinned at the bottom of the onboarding flow.
        OnboardingFooterAd.render(
            activity = this,
            screenKey = OnboardingStepConfig.ONBOARDING_KEY,
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
            divider = binding.adNativeDivider,
            fallbackType = "MediumNativeAlt",
        )

        val pagerAdapter = IntroPagerAdapter(pages)
        binding.viewPager.adapter = pagerAdapter

        // offscreenPageLimit is deliberately left at its default. Forcing it to 1
        // binds both neighbours up front, which meant every page but the first
        // played its entrance while it was still off screen and was sitting
        // finished by the time the user swiped to it. On the default, a page is
        // bound as the drag towards it starts, so its cascade runs as it arrives.

        // Open the entrance gate once there is a frame on screen; the first page is
        // bound during layout, well before the user can see anything.
        binding.viewPager.post { pagerAdapter.enableEntrances() }

        buildDots()
        updateDots(0)

        // Parallax: the illustration tracks the swipe fully; the title (0.85×) and
        // body (0.7×) lag behind it as they scroll.
        binding.viewPager.setPageTransformer { page, position ->
            val w = page.width.toFloat()
            page.findViewById<View?>(R.id.textTitle)?.translationX = position * w * 0.15f
            page.findViewById<View?>(R.id.textDesc)?.translationX = position * w * 0.30f
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = updateDots(position)
        })

        binding.buttonSkip.setOnClickListener { finishOnboarding() }
        binding.buttonNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.lastIndex) {
                binding.viewPager.currentItem = current + 1
            } else {
                finishOnboarding()
            }
        }

        // Back walks FORWARD through the pages (1 → 2 → 3) with the pager's smooth
        // scroll, so every page is seen before the user can leave. Only once the
        // last page is showing does back skip out — same path as the Skip button.
        // The callback stays enabled so back never falls through to BaseActivity's
        // exit handler; `finishOnboarding()` itself guards against a double finish.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.viewPager.currentItem
                if (current < pages.lastIndex) {
                    binding.viewPager.setCurrentItem(current + 1, true)
                } else {
                    finishOnboarding()
                }
            }
        })

        // `screen.onboarding.autonext` (seconds, 0 = disabled): auto-advance exactly
        // like Skip/Next-on-last-page if the user hasn't interacted by then.
        // `.r4` and `.r5` — the ad and the CTA fade up once, with the first page.
        // They live outside the pager and do not change between pages, so they are
        // not part of the per-page cascade.
        IntroAnimations.footerIn(binding.adNativeFrame, binding.buttonNext)

        val autonextSec = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.ONBOARDING_KEY)
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

    /**
     * Builds the step track: one equal-width segment per page.
     *
     * The design replaces the previous dot row with a progress bar — three 4dp
     * bars, 6dp apart, each taking an equal share of the row. They are weighted
     * rather than fixed so the track spans whatever width is left beside "Skip",
     * on any screen.
     */
    private fun buildDots() {
        val height = dp(4)
        val gap = dp(6)
        pages.indices.forEach { index ->
            val segment = View(this)
            segment.layoutParams = LinearLayout.LayoutParams(0, height, 1f).apply {
                if (index < pages.lastIndex) marginEnd = gap
            }
            binding.dots.addView(segment)
            dots.add(segment)
        }
    }

    private fun updateDots(active: Int) {
        // Only the current step is filled — the design does not accumulate them.
        dots.forEachIndexed { i, segment ->
            segment.setBackgroundResource(
                if (i == active) R.drawable.bg_ds_step_active else R.drawable.bg_ds_step_idle
            )
        }
        // Final step: the CTA reads "Get started" and trades its chevron for a
        // tick. The pill itself does not change — in this design it is the same
        // gradient the whole way through.
        val last = active == pages.lastIndex
        binding.buttonNext.setText(
            if (last) R.string.onboarding_get_started else R.string.onboarding_next
        )
        binding.buttonNext.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (last) R.drawable.ic_check else R.drawable.ic_ds_chevron_right, 0
        )
        if (last != wasLast) {
            wasLast = last
            popCta()
        }
    }


    /** Width/scale spring when the CTA morphs between Next and Get Started. */
    private fun popCta() {
        binding.buttonNext.animate().cancel()
        binding.buttonNext.scaleX = 0.94f
        binding.buttonNext.scaleY = 0.94f
        binding.buttonNext.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(spring)
            .setDuration(340L)
            .start()
    }

    private fun finishOnboarding() {
        if (forwarding) return
        forwarding = true
        prefs.isOnboardingDone = true
        recordEvent("onboarding_completed")
        PermissionCoordinator.checkScreenPermissions(this, OnboardingStepConfig.ONBOARDING_KEY) {
            val nextKey = OnboardingStepConfig.nextEligibleAfter(this, OnboardingStepConfig.ONBOARDING_KEY)
            val nextClass = nextKey?.let { OnboardingStepConfig.classFor(it) } ?: MainShellActivity::class.java
            val proceed: () -> Unit = {
                startActivity(Intent(this, nextClass))
                finish()
            }
            // Permission done → show the interstitial (only when
            // `screen.onboarding.isInterShow` is on) → THEN navigate.
            val isInterShow = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.ONBOARDING_KEY)
                ?.isInterShow ?: false
            if (isInterShow) {
                TransitionInterstitialAd().showInterstitial(this) { proceed() }
            } else {
                proceed()
            }
        }
    }
}
