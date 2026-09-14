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
 * Keeping them in one place means [SplashActivity] and the splash views cannot
 * drift apart: both read the same numbers the design was authored against.
 */
internal object SplashTimeline {

    // ── Backdrop ────────────────────────────────────────────────────────────
    /** `animate({from:0, to:1, start:0, end:0.5})` — the wash blooming in. */
    const val BACKDROP_FADE_DURATION = 500L

    // ── Wordmark and tagline ────────────────────────────────────────────────
    /**
     * `fadeUp … delay: 420ms`.
     *
     * The cues this used to hang off — Enter, Sort, Verify, Reveal at 3.2s —
     * belonged to the composition Splash v2 replaced, where three cards flew in
     * and one turned over before the name could appear. Nothing takes that long
     * now: the mark is up in 0.7s, so a 3.2s reveal would be the app holding a
     * finished screen in front of the user for two and a half seconds.
     *
     * ANIM_MIN_MS is derived from these, so the splash itself got shorter too.
     */
    const val WORDMARK_START = 420L
    const val WORDMARK_RISE_DURATION = 500L
    const val WORDMARK_FADE_DURATION = 400L

    /** `f2` — the tagline is a further 140ms behind the wordmark. */
    const val TAGLINE_START = WORDMARK_START + 140L
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
