package com.numify.callerid.lookup.common

import android.view.animation.Interpolator
import androidx.core.view.animation.PathInterpolatorCompat

/**
 * The easing curves the Claude Design compositions are authored against.
 *
 * These are exact ports of the `Easing` table in the design's `animations-v3.jsx`
 * runtime, not the usual cubic-bezier approximations. A design that says
 * `ease: Easing.easeOutBack` is describing the closed-form penner curve below, so
 * reproducing it here means the Android build and the design preview agree frame
 * for frame rather than "close enough".
 *
 * [easeOutBack] deliberately overshoots past 1.0 — only feed it properties that
 * tolerate that (translation, rotation, scale), never `View.ALPHA`.
 */
object DesignEasing {

    /** `(--t) * t * t + 1` — decelerates into place, no overshoot. */
    val easeOutCubic = Interpolator { t ->
        val u = t - 1f
        u * u * u + 1f
    }

    /** `t < 0.5 ? 4t³ : (t-1)(2t-2)² + 1` — the design's default when `ease` is omitted. */
    val easeInOutCubic = Interpolator { t ->
        if (t < 0.5f) {
            4f * t * t * t
        } else {
            val u = 2f * t - 2f
            (t - 1f) * u * u + 1f
        }
    }

    /**
     * `1 + c3(t-1)³ + c1(t-1)²` with c1 = 1.70158 — settles past the target and
     * eases back, which is what gives the splash's cards their landing snap.
     *
     * Android's own `OvershootInterpolator(1.70158f)` expands to the identical
     * polynomial; this spelling is kept so the constant reads as the design's.
     */
    val easeOutBack = Interpolator { t ->
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val u = t - 1f
        1f + c3 * u * u * u + c1 * u * u
    }

    // ── The handoff's CSS curves ────────────────────────────────────────────
    // The static screens are authored in CSS rather than against the composition
    // runtime above, so they name their easing as cubic-beziers. These two carry
    // every entrance in the handoff between them.

    /** `cubic-bezier(.2,.7,.2,1)` — fadeUp, popIn, and the list row cascade. */
    val entrance: Interpolator = PathInterpolatorCompat.create(0.2f, 0.7f, 0.2f, 1f)

    /**
     * `cubic-bezier(.34,1.4,.64,1)` — badgePop and avatarPop.
     *
     * The 1.4 control point is deliberate and legal: a PathInterpolator's output
     * may leave 0..1 even though its input may not, which is what lets this
     * overshoot its target and settle back the way the design's badges do.
     */
    val overshoot: Interpolator = PathInterpolatorCompat.create(0.34f, 1.4f, 0.64f, 1f)
}
