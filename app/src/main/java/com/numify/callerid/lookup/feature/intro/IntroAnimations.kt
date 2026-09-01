package com.numify.callerid.lookup.feature.intro

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.util.Property
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import com.numify.callerid.lookup.R

/**
 * Exact port of the onboarding design's CSS keyframe animations.
 *
 * Each element loops on its own timeline; the animation-timing-function is applied
 * per keyframe segment (as CSS does) by attaching the easing to each [Keyframe] and
 * driving the [ObjectAnimator] linearly. Easing curves mirror the design's
 * cubic-beziers via [PathInterpolator].
 *
 * [attach] wires an inflated illustration to its loops by view id and returns the
 * started animators so the caller can cancel them on recycle.
 */
object IntroAnimations {

    // Easing curves — identical control points to the design's CSS.
    private val SPRING = PathInterpolator(0.34f, 1.56f, 0.64f, 1f)   // overshoot pops/slides
    private val SWEEP = PathInterpolator(0.45f, 0f, 0.2f, 1f)        // radar sweep
    private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)      // CSS "ease"
    private val EASE_IN_OUT = PathInterpolator(0.42f, 0f, 0.58f, 1f) // CSS "ease-in-out"
    private val EASE_OUT = PathInterpolator(0f, 0f, 0.58f, 1f)       // CSS "ease-out"

    fun attach(root: View): List<Animator> {
        val out = ArrayList<Animator>(6)
        val d = root.resources.displayMetrics.density
        fun px(dp: Float) = dp * d

        root.findViewById<View?>(R.id.obCardVw)?.let { out += float(it, px(6f)) }
        root.findViewById<View?>(R.id.obChipVw)?.let { out += pop(it) }
        root.findViewById<View?>(R.id.obShieldVw)?.let { out += shield(it) }
        root.findViewById<View?>(R.id.obStampVw)?.let { out += stamp(it) }
        root.findViewById<View?>(R.id.obSweepVw)?.let { out += sweep(it) }
        root.findViewById<View?>(R.id.obBlipVw)?.let { out += blip(it) }
        root.findViewById<View?>(R.id.obRing1Vw)?.let { out += pulse(it, 0L) }
        root.findViewById<View?>(R.id.obRing2Vw)?.let { out += pulse(it, 900L) }
        return out
    }

    // ── keyframe / animator builders ──

    private fun pvh(
        prop: Property<View, Float>,
        easing: TimeInterpolator,
        vararg stops: Pair<Float, Float>
    ): PropertyValuesHolder {
        val kfs = Array(stops.size) { i ->
            Keyframe.ofFloat(stops[i].first, stops[i].second).apply {
                if (i > 0) interpolator = easing // segment ending at this keyframe
            }
        }
        return PropertyValuesHolder.ofKeyframe(prop, *kfs)
    }

    private fun loop(v: View, dur: Long, delay: Long, vararg h: PropertyValuesHolder): ObjectAnimator =
        ObjectAnimator.ofPropertyValuesHolder(v, *h).apply {
            duration = dur
            startDelay = delay
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator() // keyframe interpolators do the easing
            start()
        }

    /** obFloat: card bobs up 6px and back over 4s, ease-in-out. */
    private fun float(v: View, amp: Float) =
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -amp).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = EASE_IN_OUT
            start()
        }

    /** obPop: chip springs in (scale 0.6→1.08→1, rotate -6→2→0), holds, fades. */
    private fun pop(v: View) = loop(
        v, 5000L, 0L,
        pvh(View.ALPHA, SPRING, 0f to 0f, .20f to 0f, .32f to 1f, .88f to 1f, .96f to 0f, 1f to 0f),
        pvh(View.SCALE_X, SPRING, 0f to .6f, .20f to .6f, .32f to 1.08f, .38f to 1f, .88f to 1f, .96f to .9f, 1f to .9f),
        pvh(View.SCALE_Y, SPRING, 0f to .6f, .20f to .6f, .32f to 1.08f, .38f to 1f, .88f to 1f, .96f to .9f, 1f to .9f),
        pvh(View.ROTATION, SPRING, 0f to -6f, .20f to -6f, .32f to 2f, .38f to 0f, 1f to 0f)
    )

    /** obPulse: incoming/alert ring expands 0.5→1.6 and fades, over 2.6s, ease-out. */
    private fun pulse(v: View, delay: Long) = loop(
        v, 2600L, delay,
        pvh(View.SCALE_X, EASE_OUT, 0f to .5f, .70f to 1.6f, 1f to 1.6f),
        pvh(View.SCALE_Y, EASE_OUT, 0f to .5f, .70f to 1.6f, 1f to 1.6f),
        pvh(View.ALPHA, EASE_OUT, 0f to .5f, .70f to 0f, 1f to 0f)
    )

    /** obShield: gentle breathing scale 1→1.07→1, 2.4s ease-in-out. */
    private fun shield(v: View) = loop(
        v, 2400L, 0L,
        pvh(View.SCALE_X, EASE_IN_OUT, 0f to 1f, .5f to 1.07f, 1f to 1f),
        pvh(View.SCALE_Y, EASE_IN_OUT, 0f to 1f, .5f to 1.07f, 1f to 1f)
    )

    /** obStamp: "SPAM DETECTED" stamps in from scale 1.6, holds, fades. */
    private fun stamp(v: View) = loop(
        v, 5000L, 0L,
        pvh(View.ALPHA, EASE, 0f to 0f, .40f to 0f, .50f to 1f, .88f to 1f, .96f to 0f, 1f to 0f),
        pvh(View.SCALE_X, EASE, 0f to 1.6f, .40f to 1.6f, .50f to 1f, 1f to 1f),
        pvh(View.SCALE_Y, EASE, 0f to 1.6f, .40f to 1.6f, .50f to 1f, 1f to 1f)
    )

    /** obSweep: radar arm holds, rotates a full turn (25%→55%), holds. */
    private fun sweep(v: View) = loop(
        v, 5000L, 0L,
        pvh(View.ROTATION, SWEEP, 0f to 0f, .25f to 0f, .55f to 360f, 1f to 360f)
    )

    /** obBlip: match dot pops (scale 0→1.3→1) after the sweep passes, fades. */
    private fun blip(v: View) = loop(
        v, 5000L, 0L,
        pvh(View.ALPHA, EASE, 0f to 0f, .55f to 0f, .62f to 1f, .88f to 1f, .96f to 0f, 1f to 0f),
        pvh(View.SCALE_X, EASE, 0f to 0f, .55f to 0f, .62f to 1.3f, .68f to 1f, 1f to 1f),
        pvh(View.SCALE_Y, EASE, 0f to 0f, .55f to 0f, .62f to 1.3f, .68f to 1f, 1f to 1f)
    )
}
