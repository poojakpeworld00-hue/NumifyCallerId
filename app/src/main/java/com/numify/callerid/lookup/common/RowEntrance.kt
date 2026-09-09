package com.numify.callerid.lookup.common

import android.view.View
import android.view.animation.Interpolator

/**
 * The staggered row entrance the Claude Design handoff uses on every list screen.
 *
 * The designs express it as one CSS keyframe reused everywhere —
 * `@keyframes rowIn { from { opacity: 0; transform: translateY(8px) } }` played
 * over `0.45s cubic-bezier(.2,.7,.2,1)` with each row's `animation-delay` stepping
 * 60ms — so it lives here once rather than being re-typed per screen.
 *
 * Rows are animated with [View.animate] rather than an [android.animation.Animator]
 * the caller has to track: a ViewPropertyAnimator is cancelled automatically when
 * the same View is animated again, which is what recycling a RecyclerView row does
 * constantly. Use [reset] plus [hasPlayed] to keep a list from replaying the
 * entrance every time a row scrolls back into view.
 */
object RowEntrance {

    /** `0.45s` in the design's `.lang-row` rule and its equivalents. */
    const val DURATION_MS = 450L

    /** The `animation-delay` step between consecutive rows. */
    const val STAGGER_MS = 60L

    /** `translateY(8px)` — the distance a row travels, in design units. */
    const val RISE_UNITS = 8f

    /** `cubic-bezier(.2,.7,.2,1)` — shared with every other entrance in the handoff. */
    val curve: Interpolator get() = DesignEasing.entrance

    /**
     * Plays the entrance on [view] after [delayMs]. [riseUnits] is converted with
     * the same 396-unit design width the rest of the handoff is measured in, so
     * the rise stays proportional rather than being a fixed dp.
     */
    fun play(view: View, delayMs: Long, riseUnits: Float = RISE_UNITS) {
        view.alpha = 0f
        view.translationY = designPx(view, riseUnits)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(DURATION_MS)
            .setInterpolator(curve)
            .start()
    }

    /** Plays the entrance across [views] in order, stepping [stepMs] between them. */
    fun playStaggered(
        views: List<View>,
        startDelayMs: Long = 0L,
        stepMs: Long = STAGGER_MS,
    ) {
        views.forEachIndexed { index, view -> play(view, startDelayMs + stepMs * index) }
    }

    /** Drops [view] back to its resting state and cancels any entrance in flight. */
    fun reset(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
    }

    /** The design frame's content box is 396 units wide; scale distances by it. */
    private fun designPx(view: View, units: Float): Float =
        units * (view.resources.displayMetrics.widthPixels / 396f)
}
