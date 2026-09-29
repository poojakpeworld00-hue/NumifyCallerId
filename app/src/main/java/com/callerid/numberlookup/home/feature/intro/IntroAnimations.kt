package com.callerid.numberlookup.home.feature.intro

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import androidx.core.view.animation.PathInterpolatorCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.DesignEasing

/**
 * The intro pages' motion, transcribed from the design's keyframes.
 *
 * Each page plays one entrance as it arrives, then settles into slow loops:
 *
 *  | element                          | motion                          | timing          |
 *  |----------------------------------|---------------------------------|-----------------|
 *  | hero card (with its mini screen) | popIn: scale .92 → 1, fade in   | 450ms @120ms    |
 *  | path, title, body                | fadeUp 12dp, staggered          | 450ms @350/450/550 |
 *  | floating result card             | obSlide: +18/+8dp, scale .94 → 1 | 600ms @500ms    |
 *  | result card, idle                | obFloat: 0 → -5dp → 0           | 5200ms loop @1400ms |
 *  | spotlight ring                   | obPulse: scale 1 → 1.85, .55 → 0 | 1800ms loop     |
 *  | tap marker                       | obTap: appear, press to .72, fade | 3000ms loop    |
 *  | "Number blocked" toast           | obToast: rise 10dp and fade      | 3000ms loop, same clock |
 *
 * Every delay is measured from the page's arrival, as the design's are from page
 * load, so the loops and entrances keep the relationship the design gives them.
 *
 * When the system's animator scale is 0 nothing plays and every view is left at
 * rest, which is the design's own `motion: off` frame.
 */
object IntroAnimations {

    /** `cubic-bezier(.2,.8,.2,1)` - fadeUp and obSlide. */
    private val EASE_ENTRANCE: Interpolator = PathInterpolatorCompat.create(0.2f, 0.8f, 0.2f, 1f)

    /** CSS `ease-in-out`: obFloat, obTap and obToast, applied per keyframe segment. */
    private val EASE_IN_OUT: Interpolator = PathInterpolatorCompat.create(0.42f, 0f, 0.58f, 1f)

    /** CSS `ease-out`: obPulse. */
    private val EASE_OUT: Interpolator = PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f)

    private const val POP_IN_MS = 450L
    private const val POP_IN_DELAY_MS = 120L
    private const val POP_IN_FROM = 0.92f

    private const val FADE_UP_MS = 450L
    private const val FADE_UP_DP = 12f
    private const val PATH_DELAY_MS = 350L
    private const val TITLE_DELAY_MS = 450L
    private const val BODY_DELAY_MS = 550L

    private const val SLIDE_MS = 600L
    private const val SLIDE_DELAY_MS = 500L
    private const val SLIDE_X_DP = 18f
    private const val SLIDE_Y_DP = 8f
    private const val SLIDE_FROM_SCALE = 0.94f

    private const val FLOAT_HALF_CYCLE_MS = 2_600L
    private const val FLOAT_DELAY_MS = 1_400L
    private const val FLOAT_DP = 5f

    private const val PULSE_MS = 1_800L
    private const val PULSE_TO_SCALE = 1.85f
    private const val PULSE_FROM_ALPHA = 0.55f

    private const val TAP_CLOCK_MS = 3_000L
    private const val TOAST_RISE_DP = 10f
    private const val TOAST_EXIT_DP = 4f

    /** Whether motion is on: false when the user has turned animations off. */
    fun motionEnabled(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) != 0f
        }

    /**
     * Parks every view a page animates at the first frame of its keyframe, so a
     * page that is bound but not yet playing sits blank rather than appearing whole
     * and then snapping back to animate. With motion off it does the opposite and
     * leaves everything at rest.
     */
    fun prepare(hero: ViewGroup, vararg copy: View) {
        val views = heroViews(hero)
        reset(*views.all(), *copy)
        if (!motionEnabled(hero.context)) return
        views.card?.apply { alpha = 0f; scaleX = POP_IN_FROM; scaleY = POP_IN_FROM }
        views.result?.alpha = 0f
        views.tap?.alpha = 0f
        views.toast?.alpha = 0f
        copy.forEach { it.alpha = 0f }
    }

    /**
     * Plays the page: the entrance, then the loops. Returns everything it started
     * so the caller can pause, resume or cancel it with the page.
     */
    fun play(hero: ViewGroup, path: View, title: View, body: View): List<Animator> {
        val views = heroViews(hero)
        if (!motionEnabled(hero.context)) {
            reset(*views.all(), path, title, body)
            return emptyList()
        }
        val animators = mutableListOf<Animator>()
        views.card?.let { animators += popIn(it) }
        animators += fadeUp(path, PATH_DELAY_MS)
        animators += fadeUp(title, TITLE_DELAY_MS)
        animators += fadeUp(body, BODY_DELAY_MS)
        views.result?.let { animators += slideIn(it) }
        views.resultCard?.let { animators += float(it) }
        views.pulse?.let { animators += pulse(it) }
        views.tap?.let { animators += tap(it) }
        views.toast?.let { animators += toast(it) }
        animators.forEach { it.start() }
        return animators
    }

    /** Returns [views] to rest - fully visible, untransformed. */
    fun reset(vararg views: View?) {
        views.forEach { view ->
            view ?: return@forEach
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    // ── Entrances ───────────────────────────────────────────────────────────

    /** popIn: scale .92 → 1 with a fade, 450ms at 120ms, on the entrance curve. */
    private fun popIn(view: View): Animator = AnimatorSet().apply {
        startDelay = POP_IN_DELAY_MS
        duration = POP_IN_MS
        interpolator = DesignEasing.entrance
        playTogether(
            ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
            ObjectAnimator.ofFloat(view, View.SCALE_X, POP_IN_FROM, 1f),
            ObjectAnimator.ofFloat(view, View.SCALE_Y, POP_IN_FROM, 1f),
        )
    }

    /** `@keyframes obFadeUp`: translateY(12px) → 0 with a fade, 450ms. */
    private fun fadeUp(view: View, delay: Long): Animator {
        val rise = dp(view, FADE_UP_DP)
        view.translationY = rise
        return AnimatorSet().apply {
            startDelay = delay
            duration = FADE_UP_MS
            interpolator = EASE_ENTRANCE
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, rise, 0f),
            )
        }
    }

    /**
     * `@keyframes obSlide`: from `translate(18px, 8px) scale(.94)` and transparent
     * to rest, 600ms at 500ms.
     */
    private fun slideIn(view: View): Animator {
        // translate() is in screen space; mirror it so the card slides in from the
        // trailing edge in RTL too.
        val dx = dp(view, SLIDE_X_DP) * if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        val dy = dp(view, SLIDE_Y_DP)
        view.translationX = dx
        view.translationY = dy
        return AnimatorSet().apply {
            startDelay = SLIDE_DELAY_MS
            duration = SLIDE_MS
            interpolator = EASE_ENTRANCE
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, dx, 0f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, dy, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, SLIDE_FROM_SCALE, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, SLIDE_FROM_SCALE, 1f),
            )
        }
    }

    // ── Loops ───────────────────────────────────────────────────────────────

    /**
     * `@keyframes obFloat`: 0 → -5px → 0 over 5.2s, from 1.4s. The easing is per
     * segment in CSS, so one reversing leg of 2.6s on ease-in-out is the same curve.
     */
    private fun float(view: View): Animator =
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, -dp(view, FLOAT_DP)).apply {
            startDelay = FLOAT_DELAY_MS
            duration = FLOAT_HALF_CYCLE_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = EASE_IN_OUT
        }

    /** `@keyframes obPulse`: scale 1 → 1.85 while fading .55 → 0, every 1.8s. */
    private fun pulse(view: View): Animator = ObjectAnimator.ofPropertyValuesHolder(
        view,
        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_TO_SCALE),
        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_TO_SCALE),
        PropertyValuesHolder.ofFloat(View.ALPHA, PULSE_FROM_ALPHA, 0f),
    ).apply {
        duration = PULSE_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = EASE_OUT
    }

    /**
     * `@keyframes obTap` over 3s:
     * `0%,55% {0, scale 1.4} 65% {1, 1} 78% {1, .72} 92%,100% {0, .72}`.
     */
    private fun tap(view: View): Animator {
        val alpha = keyframes(View.ALPHA, 0f to 0f, 0.55f to 0f, 0.65f to 1f, 0.78f to 1f, 0.92f to 0f, 1f to 0f)
        val scale = listOf(0f to 1.4f, 0.55f to 1.4f, 0.65f to 1f, 0.78f to 0.72f, 0.92f to 0.72f, 1f to 0.72f)
        return clock(
            view,
            alpha,
            keyframes(View.SCALE_X, *scale.toTypedArray()),
            keyframes(View.SCALE_Y, *scale.toTypedArray()),
        )
    }

    /**
     * `@keyframes obToast` over 3s, on the tap marker's clock so the toast rises
     * just after the press lands:
     * `0%,35% {0, +10px} 45%,88% {1, 0} 100% {0, +4px}`.
     */
    private fun toast(view: View): Animator {
        val rise = dp(view, TOAST_RISE_DP)
        val exit = dp(view, TOAST_EXIT_DP)
        return clock(
            view,
            keyframes(View.ALPHA, 0f to 0f, 0.35f to 0f, 0.45f to 1f, 0.88f to 1f, 1f to 0f),
            keyframes(View.TRANSLATION_Y, 0f to rise, 0.35f to rise, 0.45f to 0f, 0.88f to 0f, 1f to exit),
        )
    }

    /** A 3s loop over keyframes; the easing lives on the keyframes, so time runs linearly. */
    private fun clock(view: View, vararg holders: PropertyValuesHolder): Animator =
        ObjectAnimator.ofPropertyValuesHolder(view, *holders).apply {
            duration = TAP_CLOCK_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
        }

    /**
     * CSS keyframes as a PropertyValuesHolder. CSS eases each interval with the
     * timing function of the keyframe that starts it; an Android Keyframe's
     * interpolator eases the interval that ends at it. Every interval here uses
     * the same ease-in-out, so setting it on every keyframe gives the same curve.
     */
    private fun keyframes(
        property: android.util.Property<View, Float>,
        vararg frames: Pair<Float, Float>,
    ): PropertyValuesHolder = PropertyValuesHolder.ofKeyframe(
        property,
        *frames.map { (at, value) ->
            Keyframe.ofFloat(at, value).apply { interpolator = EASE_IN_OUT }
        }.toTypedArray(),
    )

    // ── Helpers ─────────────────────────────────────────────────────────────

    private class HeroViews(
        val card: View?,
        val result: View?,
        val resultCard: View?,
        val pulse: View?,
        val tap: View?,
        val toast: View?,
    ) {
        fun all(): Array<View?> = arrayOf(card, result, resultCard, pulse, tap, toast)
    }

    private fun heroViews(hero: ViewGroup) = HeroViews(
        card = hero.findViewById(R.id.heroCard),
        result = hero.findViewById(R.id.heroResult),
        resultCard = hero.findViewById(R.id.heroResultCard),
        pulse = hero.findViewById(R.id.heroPulse),
        tap = hero.findViewById(R.id.heroTap),
        toast = hero.findViewById(R.id.heroToast),
    )

    private fun dp(view: View, value: Float): Float = value * view.resources.displayMetrics.density
}
