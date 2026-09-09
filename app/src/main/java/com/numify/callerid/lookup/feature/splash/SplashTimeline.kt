package com.numify.callerid.lookup.feature.splash

/**
 * The splash composition's clock, transcribed from the design.
 *
 * The design file declares its scenes as
 * `Enter 1.1s · Sort 1.3s · Verify 0.8s · Reveal 1.0s · Hold 1.3s · Exit 0.6s`,
 * and its runtime turns that list into cues by running the durations up — so a
 * `CUES.Verify` in the design source is 1.1 + 1.3 = 2.4s. Every cue below is that
 * running sum in milliseconds, and every offset is the literal `start`/`end` pair
 * from the design's own `animate({…})` calls.
 *
 * Keeping them in one place means [SplashActivity] and [CallCardStackView] cannot
 * drift apart: both read the same numbers the design was authored against.
 */
internal object SplashTimeline {

    // ── Scene cues (running sum of the authored durations) ───────────────────
    const val CUE_ENTER = 0L
    const val CUE_SORT = 1_100L
    const val CUE_VERIFY = 2_400L
    const val CUE_REVEAL = 3_200L
    const val CUE_HOLD = 4_200L
    const val CUE_EXIT = 5_500L

    // ── Backdrop ────────────────────────────────────────────────────────────
    /** `animate({from:0, to:1, start:0, end:0.5})` — the gradient blooming in. */
    const val BACKDROP_FADE_DURATION = 500L

    // ── Call cards ──────────────────────────────────────────────────────────
    /** Each card's fly-in runs `start:delay → delay + 0.6` on easeOutBack. */
    const val CARD_TRAVEL_DURATION = 600L

    /** …while its opacity runs the shorter `delay → delay + 0.35`. */
    const val CARD_FADE_DURATION = 350L

    /** The three staggered `delay` values, in card order (back to front). */
    val CARD_DELAYS = longArrayOf(100L, 280L, 460L)

    /** `start: CUES.Verify, end: CUES.Verify + 0.55` — the top card's Y flip. */
    const val FLIP_START = CUE_VERIFY
    const val FLIP_DURATION = 550L

    /** The flip's full sweep, in degrees; the back face takes over past 90. */
    const val FLIP_DEGREES = 180f

    // ── Wordmark and tagline ────────────────────────────────────────────────
    const val WORDMARK_START = CUE_REVEAL
    const val WORDMARK_RISE_DURATION = 500L
    const val WORDMARK_FADE_DURATION = 400L

    /** The tagline is offset a further `+0.15` behind the wordmark. */
    const val TAGLINE_START = CUE_REVEAL + 150L
    const val TAGLINE_RISE_DURATION = 400L
    const val TAGLINE_FADE_DURATION = 350L

    // ── Footer ──────────────────────────────────────────────────────────────
    /**
     * The design fades the footer up late, at `CUES.Hold + 0.1 → +0.4`. The app
     * does not: the ad disclosure, the loader and the build stamp are on screen
     * from the first frame and stay there for as long as the splash is up.
     *
     * That is a deliberate departure. "Ads may appear in this app" is a disclosure,
     * and a disclosure that arrives four seconds in — on a screen that may leave
     * as soon as getData() resolves — can be missed entirely. Showing it from the
     * start also means nothing about the splash's length is pinned to it.
     */
    const val FOOTER_START = 0L
    const val FOOTER_FADE_DURATION = 0L

    /**
     * The loader pill sweeps 0 → 100% on the design's own curve and duration, then
     * repeats for as long as the splash is up, so the bar reads as work still in
     * progress rather than as a bar that filled once and stopped.
     */
    const val PROGRESS_START = 0L
    const val PROGRESS_DURATION = 1_050L

    // ── Exit ────────────────────────────────────────────────────────────────
    /**
     * `start: CUES.Exit + 0.05, end: CUES.Exit + 0.55`.
     *
     * In the design this fade exists to hide the loop seam. The app has no loop,
     * so [SplashActivity] spends it as the hand-off to the next screen instead —
     * same curve, same 500ms, played once when navigation actually happens.
     *
     * The design's 50ms lead-in beat is dropped: the splash leaves the moment the
     * footer is up, so the fade starts on that frame rather than idling first.
     * Nothing waits after the disclosure and loader are on screen.
     */
    const val EXIT_DELAY = 0L
    const val EXIT_DURATION = 500L
}
