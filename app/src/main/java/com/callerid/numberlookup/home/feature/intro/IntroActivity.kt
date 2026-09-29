package com.callerid.numberlookup.home.feature.intro

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.animation.PathInterpolatorCompat
import androidx.core.view.isVisible
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.databinding.ActivityOnboardingBinding
import com.callerid.numberlookup.home.permission.PermissionCoordinator
import com.callerid.numberlookup.home.feature.MainShellActivity
import com.callerid.numberlookup.home.feature.onboarding.OnboardingFooterAd
import com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig
import java.util.Locale

class IntroActivity : BaseActivity<ActivityOnboardingBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "IntroActivity"

    override val layoutId: Int = R.layout.activity_onboarding

    private val prefs by lazy { SettingsRepository(this) }
    private val pages = IntroSlides.all
    private val segments = mutableListOf<Segment>()
    private lateinit var pagerAdapter: IntroPagerAdapter

    /** `cubic-bezier(.2,.8,.2,1)`, the design's entrance curve, for the footer and track. */
    private val ease = PathInterpolatorCompat.create(0.2f, 0.8f, 0.2f, 1f)

    /** Null until the first page is shown, so that first showing does not animate. */
    private var wasLast: Boolean? = null
    private var shownPage = -1

    /** One-shot guard so Skip/Next/back/autonext can't fire the forward flow twice. */
    private var forwarding = false

    private val autonextHandler = Handler(Looper.getMainLooper())

    /** The autonext countdown, 0..1, drawn into the current step while it runs. */
    private var autonextFill: ValueAnimator? = null

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

        pagerAdapter = IntroPagerAdapter(pages)
        binding.viewPager.adapter = pagerAdapter

        // offscreenPageLimit is deliberately left at its default. Forcing it to 1
        // binds both neighbours up front, which meant every page but the first
        // played its entrance while it was still off screen and was sitting
        // finished by the time the user swiped to it. On the default, a page is
        // bound as the drag towards it starts, so its cascade runs as it arrives.

        // Open the entrance gate once there is a frame on screen; the first page is
        // bound during layout, well before the user can see anything.
        binding.viewPager.post { pagerAdapter.enableEntrances() }

        buildSegments()

        // Parallax: the hero tracks the swipe fully; the title (0.85x) and body
        // (0.7x) lag behind it as they scroll.
        binding.viewPager.setPageTransformer { page, position ->
            val w = page.width.toFloat()
            page.findViewById<View?>(R.id.textTitle)?.translationX = position * w * 0.15f
            page.findViewById<View?>(R.id.textDesc)?.translationX = position * w * 0.30f
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = showPage(position)

            override fun onPageScrollStateChanged(state: Int) =
                pagerAdapter.onScrollStateChanged(idle = state == ViewPager2.SCROLL_STATE_IDLE)
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

        // Back on 02 and 03 goes back a page. On 01 there is nowhere to go back to
        // inside the flow, so it finishes onboarding - the same path as Skip -
        // rather than exiting the app mid-setup. The callback stays enabled so back
        // never falls through to BaseActivity's exit handler.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = binding.viewPager.currentItem
                if (current > 0) {
                    binding.viewPager.setCurrentItem(current - 1, true)
                } else {
                    finishOnboarding()
                }
            }
        })

        // `screen.onboarding.autonext` (seconds, 0 = disabled): finish the flow
        // exactly like Skip if it runs out, and fill the current step as it counts.
        val autonextSec = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.ONBOARDING_KEY)
            ?.autonextSec ?: 0
        if (autonextSec > 0) {
            autonextHandler.postDelayed({ finishOnboarding() }, autonextSec * 1000L)
            startAutonextFill(autonextSec * 1000L)
        }

        showPage(binding.viewPager.currentItem)
    }

    override fun onDestroy() {
        super.onDestroy()
        autonextHandler.removeCallbacksAndMessages(null)
        autonextFill?.cancel()
        segments.forEach { it.animator?.cancel() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Progress track ──────────────────────────────────────────────────────

    /**
     * One step on the track: a 4dp bar whose background is the idle track with
     * the accent laid over it. [fill] is that accent layer: its alpha is the
     * reached/unreached colour change, and its clip level is how much of the bar it
     * covers - all of it, except while autonext is counting on the current step.
     */
    private class Segment(val view: View, val fill: ClipDrawable, val accent: GradientDrawable) {
        var weight = 1f
        var reached = 0f
        var animator: ValueAnimator? = null
    }

    /**
     * Builds the track: one segment per page, 6dp apart, weighted so the track
     * spans whatever width is left beside Skip on any screen. The current segment
     * weighs 2, the rest 1; every segment up to the current one is blue.
     */
    private fun buildSegments() {
        val gap = dp(6)
        val radius = dp(2).toFloat()
        pages.indices.forEach { index ->
            val idle = GradientDrawable().apply {
                cornerRadius = radius
                setColor(ContextCompat.getColor(this@IntroActivity, R.color.ds_track_idle))
            }
            val accent = GradientDrawable().apply {
                cornerRadius = radius
                setColor(ContextCompat.getColor(this@IntroActivity, R.color.ds_accent))
                alpha = 0
            }
            val fill = ClipDrawable(accent, android.view.Gravity.START, ClipDrawable.HORIZONTAL)
                .apply { level = FULL_LEVEL }
            val view = View(this).apply {
                background = LayerDrawable(arrayOf(idle, fill))
                layoutParams = LinearLayout.LayoutParams(0, dp(4), 1f).apply {
                    if (index < pages.lastIndex) marginEnd = gap
                }
            }
            binding.dots.addView(view)
            segments += Segment(view, fill, accent)
        }
    }

    /** "weight 1 → 2 + color", 300ms on page select. */
    private fun updateSegments(active: Int, animate: Boolean) {
        segments.forEachIndexed { i, segment ->
            val toWeight = if (i == active) 2f else 1f
            val toReached = if (i <= active) 1f else 0f
            segment.animator?.cancel()
            if (!animate || !IntroAnimations.motionEnabled(this)) {
                applySegment(segment, toWeight, toReached)
                return@forEachIndexed
            }
            val fromWeight = segment.weight
            val fromReached = segment.reached
            segment.animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = SEGMENT_MS
                interpolator = ease
                addUpdateListener {
                    val t = it.animatedValue as Float
                    applySegment(
                        segment,
                        fromWeight + (toWeight - fromWeight) * t,
                        fromReached + (toReached - fromReached) * t,
                    )
                }
                start()
            }
        }
        // While autonext counts, the step being read fills rather than sitting full.
        segments.forEachIndexed { i, segment ->
            segment.fill.level = if (i == active && autonextFill?.isRunning == true) {
                ((autonextFill?.animatedValue as? Float ?: 0f) * FULL_LEVEL).toInt()
            } else {
                FULL_LEVEL
            }
        }
    }

    private fun applySegment(segment: Segment, weight: Float, reached: Float) {
        segment.weight = weight
        segment.reached = reached
        segment.accent.alpha = (reached * 255).toInt()
        (segment.view.layoutParams as LinearLayout.LayoutParams).weight = weight
        segment.view.requestLayout()
    }

    private fun startAutonextFill(durationMs: Long) {
        autonextFill = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            addUpdateListener {
                val current = binding.viewPager.currentItem
                segments.getOrNull(current)?.fill?.level =
                    ((it.animatedValue as Float) * FULL_LEVEL).toInt()
            }
            start()
        }
    }

    // ── Page change ─────────────────────────────────────────────────────────

    private fun showPage(position: Int) {
        if (position == shownPage) return
        val firstShow = shownPage == -1
        shownPage = position
        pagerAdapter.onPageSelected(position)
        if (!firstShow) {
            binding.viewPager.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        updateSegments(position, animate = !firstShow)
        updatePageCount(position)
        updateFooter(last = position == pages.lastIndex)
    }

    /** "01 / 03" - the current page in ink, the separator and total in the idle grey. */
    private fun updatePageCount(position: Int) {
        val current = String.format(Locale.ROOT, "%02d", position + 1)
        val total = String.format(Locale.ROOT, "%02d", pages.size)
        binding.textPageCount.text = SpannableStringBuilder(current).apply {
            setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this@IntroActivity, R.color.ds_ink)),
                0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            append(" / ").append(total)
        }
    }

    /**
     * Next → Get started: on the last page the counter goes, the pill widens to
     * the full row (ChangeBounds, 280ms) and its label crossfades. Skip steps
     * aside there too - the design draws it in the page colour, which is to say
     * not at all.
     */
    private fun updateFooter(last: Boolean) {
        val previous = wasLast
        if (previous == last) return
        wasLast = last
        val animate = previous != null && IntroAnimations.motionEnabled(this)

        if (animate) {
            TransitionManager.beginDelayedTransition(
                binding.footerRow,
                ChangeBounds().setDuration(FOOTER_MS).setInterpolator(ease),
            )
        }
        binding.textPageCount.isVisible = !last
        (binding.buttonNext.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (last) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (last) 1f else 0f
        }
        binding.buttonNext.requestLayout()
        binding.buttonSkip.visibility = if (last) View.INVISIBLE else View.VISIBLE
        binding.buttonNext.contentDescription =
            getString(if (last) R.string.onboarding_get_started else R.string.onboarding_next)

        crossfadeLabels(toDone = last, animate = animate)
    }

    /**
     * The two labels crossfade over the same 280ms. "Get started" only exists
     * while it is showing: were it merely transparent it would still be measured,
     * and the compact pill would be as wide as the long label.
     */
    private fun crossfadeLabels(toDone: Boolean, animate: Boolean) {
        val next = binding.labelNext
        val done = binding.labelDone
        next.animate().cancel()
        done.animate().cancel()
        if (toDone) {
            done.isVisible = true
            if (animate) {
                done.animate().alpha(1f).setDuration(FOOTER_MS).setInterpolator(ease).start()
                next.animate().alpha(0f).setDuration(FOOTER_MS).setInterpolator(ease).start()
            } else {
                done.alpha = 1f
                next.alpha = 0f
            }
        } else {
            done.alpha = 0f
            done.isVisible = false
            if (animate) {
                next.animate().alpha(1f).setDuration(FOOTER_MS).setInterpolator(ease).start()
            } else {
                next.alpha = 1f
            }
        }
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

    private companion object {
        const val SEGMENT_MS = 300L
        const val FOOTER_MS = 280L
        const val FULL_LEVEL = 10_000
    }
}
