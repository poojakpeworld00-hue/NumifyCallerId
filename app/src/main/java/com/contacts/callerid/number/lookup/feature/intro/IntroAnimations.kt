package com.contacts.callerid.number.lookup.feature.intro

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.common.DesignEasing

/**
 * The onboarding entrances, transcribed from the handoff's CSS keyframes.
 *
 * Each design page declares a short cascade — `.r2` the hero card, then its parts,
 * then `.r3` the headline, `.r4` the ad and `.r5` the CTA — as `animation-delay`
 * values on three keyframes:
 *
 *  - `fadeUp`   — opacity 0 → 1 with `translateY(12px)` → 0, on [DesignEasing.entrance]
 *  - `popIn`    — opacity 0 → 1 with `scale(0.85)` → 1, on the same curve
 *  - `badgePop` — opacity 0 → 1 with `scale(0.4) rotate(-20deg)` → identity, on
 *                 [DesignEasing.overshoot], so it settles past its size and back
 *
 * These are one-shot entrances, not the loops the previous illustration set used:
 * the design plays each page in as it arrives and then leaves it still.
 *
 * [attachHero] dispatches on the ids present in the inflated art, which is what
 * keeps the three heroes' different cascades out of the pager adapter.
 */
object IntroAnimations {

    // Durations, in ms, exactly as the keyframes declare them.
    private const val FADE_UP_MS = 450L
    private const val POP_IN_MS = 450L
    private const val BADGE_POP_MS = 400L
    private const val RING_DRAW_MS = 900L

    /** `translateY(12px)` — the fadeUp travel, in design units. */
    private const val RISE_UNITS = 12f

    // ── Idle loops ──────────────────────────────────────────────────────────
    // Not in the handoff; see idleLoops() for why they are here. Deliberately
    // slow and shallow — this is ambient motion under a block of body copy.

    /** After the slowest entrance (the lookup ring, which ends at 1280ms). */
    private const val IDLE_START_MS = 1_400L

    private const val FLOAT_UNITS = 4f
    private const val FLOAT_HALF_CYCLE_MS = 2_600L

    private const val RING_PULSE_SCALE = 1.08f
    private const val RING_PULSE_HALF_CYCLE_MS = 900L

    private const val EYEBROW_DIM_ALPHA = 0.55f
    private const val EYEBROW_HALF_CYCLE_MS = 1_100L

    private const val BADGE_PULSE_SCALE = 1.12f
    private const val BADGE_PULSE_HALF_CYCLE_MS = 1_000L

    private const val RING_ORBIT_START_MS = 1_400L
    private const val RING_ORBIT_MS = 3_600L

    /** The design frame's content box, which design units are measured against. */
    private const val DESIGN_WIDTH = 396f

    // ── Public entrances ────────────────────────────────────────────────────

    /**
     * Builds the entrance for whichever hero was inflated into [container].
     *
     * Returns the animators already started, so the caller can cancel them when
     * the page is recycled mid-flight.
     */
    fun attachHero(container: ViewGroup): List<Animator> {
        val card = container.findViewById<View>(R.id.heroCard) ?: return emptyList()
        val animators = mutableListOf<Animator>()

        // Every hero opens the same way: the card itself pops in at 120ms.
        animators += popIn(card, delay = 120L)

        // Hero 1 — the identified incoming call.
        container.findViewById<View>(R.id.heroAvatar)?.let { avatar ->
            // Hero 3 reuses this id for the initials disc inside the ring, where
            // the design pops it later and without the rotation. The ring is what
            // tells the two apart.
            val isLookup = container.findViewById<View>(R.id.heroRing) != null
            animators += if (isLookup) {
                scalePop(avatar, delay = 500L, fromScale = 0.5f, rotate = false)
            } else {
                scalePop(avatar, delay = 160L, fromScale = 0.4f, rotate = true)
            }
        }
        container.findViewById<View>(R.id.heroAccept)
            ?.let { animators += scalePop(it, delay = 360L, fromScale = 0.4f, rotate = true) }
        container.findViewById<View>(R.id.heroDecline)
            ?.let { animators += scalePop(it, delay = 440L, fromScale = 0.4f, rotate = true) }

        // Hero 2 — the blocked call. The shield lands first, its badge much later,
        // so the badge reads as being stamped onto a tile that is already there.
        container.findViewById<View>(R.id.heroShield)
            ?.let { animators += scalePop(it, delay = 200L, fromScale = 0.4f, rotate = true) }
        container.findViewById<View>(R.id.heroBadge)
            ?.let { animators += scalePop(it, delay = 420L, fromScale = 0.4f, rotate = true) }

        // Hero 3 — the lookup. Field, then result row, then the ring drawing.
        container.findViewById<View>(R.id.heroSearchField)
            ?.let { animators += fadeUp(it, delay = 140L) }
        container.findViewById<LookupRingView>(R.id.heroRing)?.let { ring ->
            animators += ringDraw(ring, delay = 380L)
        }

        // The text block moves as one. Which views make it up differs per hero,
        // and so does its delay: 260ms on the call page, 300ms on the blocked one,
        // 280ms for the lookup's whole result row.
        val textDelay = when {
            container.findViewById<View>(R.id.heroRing) != null -> 280L
            container.findViewById<View>(R.id.heroBadge) != null -> 300L
            else -> 260L
        }
        textBlock(container).forEach { animators += fadeUp(it, delay = textDelay) }

        animators += idleLoops(container)

        animators.forEach { it.start() }
        return animators
    }

    /**
     * The motion the hero keeps once its entrance has settled.
     *
     * The handoff only specifies entrances — every page lands and then stops dead,
     * which leaves the card looking like a screenshot for however long the user
     * reads the headline. These loops keep it alive without inventing new content:
     * each one is the card's own subject continuing to do what it depicts, and each
     * is small enough (a few dp, a few percent of scale) that it reads as breathing
     * rather than as a second animation competing with the copy.
     *
     * They all start after the last entrance has finished, so nothing overlaps the
     * authored cascade.
     */
    private fun idleLoops(container: ViewGroup): List<Animator> {
        val loops = mutableListOf<Animator>()

        // Every hero floats, very slightly. This is the whole card, so it carries
        // its contents with it and costs one animator rather than one per element.
        container.findViewById<View>(R.id.heroCard)?.let { card ->
            loops += pingPong(
                ObjectAnimator.ofFloat(card, View.TRANSLATION_Y, 0f, -designPx(card, FLOAT_UNITS)),
                halfCycleMs = FLOAT_HALF_CYCLE_MS,
                startDelayMs = IDLE_START_MS,
            )
        }

        // Hero 1 — the call is still ringing: the answer button pulses, and the
        // "INCOMING CALL" line pulses with it, a half-beat apart.
        container.findViewById<View>(R.id.heroAccept)?.let { accept ->
            loops += pingPong(
                ObjectAnimator.ofFloat(accept, View.SCALE_X, 1f, RING_PULSE_SCALE),
                RING_PULSE_HALF_CYCLE_MS, IDLE_START_MS,
            )
            loops += pingPong(
                ObjectAnimator.ofFloat(accept, View.SCALE_Y, 1f, RING_PULSE_SCALE),
                RING_PULSE_HALF_CYCLE_MS, IDLE_START_MS,
            )
        }
        container.findViewById<View>(R.id.heroVerifiedRow)?.let { _ ->
            container.findViewById<View>(R.id.heroEyebrow)?.let { eyebrow ->
                loops += pingPong(
                    ObjectAnimator.ofFloat(eyebrow, View.ALPHA, 1f, EYEBROW_DIM_ALPHA),
                    EYEBROW_HALF_CYCLE_MS, IDLE_START_MS,
                )
            }
        }

        // Hero 2 — the block keeps asserting itself: the badge beats.
        container.findViewById<View>(R.id.heroBadge)?.let { badge ->
            loops += pingPong(
                ObjectAnimator.ofFloat(badge, View.SCALE_X, 1f, BADGE_PULSE_SCALE),
                BADGE_PULSE_HALF_CYCLE_MS, IDLE_START_MS,
            )
            loops += pingPong(
                ObjectAnimator.ofFloat(badge, View.SCALE_Y, 1f, BADGE_PULSE_SCALE),
                BADGE_PULSE_HALF_CYCLE_MS, IDLE_START_MS,
            )
        }

        // Hero 3 — the lookup is still running: the drawn arc orbits. Rotating the
        // view spins the arc about its own centre while the initials disc, which is
        // a sibling rather than a child, stays upright.
        container.findViewById<LookupRingView>(R.id.heroRing)?.let { ring ->
            loops += ObjectAnimator.ofFloat(ring, View.ROTATION, 0f, 360f).apply {
                startDelay = RING_ORBIT_START_MS
                duration = RING_ORBIT_MS
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
            }
        }

        return loops
    }

    /** Runs [anim] forever, easing back and forth — one leg per [halfCycleMs]. */
    private fun pingPong(
        anim: ObjectAnimator,
        halfCycleMs: Long,
        startDelayMs: Long,
    ): Animator = anim.apply {
        startDelay = startDelayMs
        duration = halfCycleMs
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }

    /**
     * Parks every view a page's cascade will touch at the start of its keyframe.
     *
     * Called at bind, separately from playing, so a page that has been bound but is
     * not yet allowed to animate sits blank rather than appearing fully composed
     * and then snapping back to animate.
     */
    fun prepare(container: ViewGroup, vararg pageViews: View) {
        (animatedIds.mapNotNull { container.findViewById<View>(it) } + pageViews)
            .forEach { it.alpha = 0f }
        container.findViewById<LookupRingView>(R.id.heroRing)?.sweepFraction = 0f
    }

    /** `.r3` — the headline and its supporting copy, as one block at 220ms. */
    fun headlineIn(vararg views: View): List<Animator> =
        views.map { fadeUp(it, delay = 220L) }.onEach { it.start() }

    /** `.r4` and `.r5` — the ad at 320ms and the CTA at 400ms. */
    fun footerIn(ad: View, cta: View): List<Animator> =
        listOf(fadeUp(ad, delay = 320L), fadeUp(cta, delay = 400L)).onEach { it.start() }

    /** Returns [views] to their resting state, cancelling anything in flight. */
    fun reset(vararg views: View) {
        views.forEach { view ->
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.rotation = 0f
        }
    }

    // ── Keyframes ───────────────────────────────────────────────────────────

    /** `@keyframes fadeUp`. */
    private fun fadeUp(view: View, delay: Long): Animator {
        view.alpha = 0f
        view.translationY = designPx(view, RISE_UNITS)
        return AnimatorSet().apply {
            startDelay = delay
            duration = FADE_UP_MS
            interpolator = DesignEasing.entrance
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, view.translationY, 0f),
            )
        }
    }

    /** `@keyframes popIn`. */
    private fun popIn(view: View, delay: Long): Animator {
        view.alpha = 0f
        view.scaleX = 0.85f
        view.scaleY = 0.85f
        return AnimatorSet().apply {
            startDelay = delay
            duration = POP_IN_MS
            interpolator = DesignEasing.entrance
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.85f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.85f, 1f),
            )
        }
    }

    /**
     * `@keyframes badgePop` / `avatarPop` — the same shape, differing only in
     * whether the design also unwinds a -20deg rotation.
     *
     * Alpha runs on its own linear leg rather than the overshoot curve: an
     * overshooting alpha would drive past 1, and a badge that briefly over-fades
     * has no visual meaning while an over-scaled one is the whole point.
     */
    private fun scalePop(
        view: View,
        delay: Long,
        fromScale: Float,
        rotate: Boolean,
    ): Animator {
        view.alpha = 0f
        view.scaleX = fromScale
        view.scaleY = fromScale
        if (rotate) view.rotation = -20f

        val scaleAndSpin = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(view, View.SCALE_X, fromScale, 1f),
            ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScale, 1f),
        )
        if (rotate) scaleAndSpin += ObjectAnimator.ofFloat(view, View.ROTATION, -20f, 0f)

        return AnimatorSet().apply {
            startDelay = delay
            playTogether(
                AnimatorSet().apply {
                    duration = BADGE_POP_MS
                    interpolator = DesignEasing.overshoot
                    playTogether(scaleAndSpin)
                },
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).apply {
                    duration = BADGE_POP_MS
                    interpolator = DesignEasing.entrance
                },
            )
        }
    }

    /** `@keyframes ringDraw` — the lookup ring sweeping out to its resting arc. */
    private fun ringDraw(ring: LookupRingView, delay: Long): Animator {
        ring.sweepFraction = 0f
        return ValueAnimator.ofFloat(0f, ring.targetFraction).apply {
            startDelay = delay
            duration = RING_DRAW_MS
            interpolator = DesignEasing.entrance
            addUpdateListener { ring.sweepFraction = it.animatedValue as Float }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Every id any of the three heroes animates; missing ones are simply skipped. */
    private val animatedIds = listOf(
        R.id.heroCard, R.id.heroAvatar, R.id.heroAccept, R.id.heroDecline,
        R.id.heroShield, R.id.heroBadge, R.id.heroSearchField,
        R.id.heroEyebrow, R.id.heroName, R.id.heroVerifiedRow,
        R.id.heroSpamPill, R.id.heroRegion, R.id.heroResultBox,
    )

    /** The views the design animates together as one `.text-in` / `.r2b` block. */
    private fun textBlock(container: ViewGroup): List<View> = listOfNotNull(
        container.findViewById(R.id.heroEyebrow),
        container.findViewById(R.id.heroName),
        container.findViewById(R.id.heroVerifiedRow),
        container.findViewById(R.id.heroSpamPill),
        container.findViewById(R.id.heroRegion),
        container.findViewById(R.id.heroResultBox),
    )

    private fun designPx(view: View, units: Float): Float =
        units * (view.resources.displayMetrics.widthPixels / DESIGN_WIDTH)
}
