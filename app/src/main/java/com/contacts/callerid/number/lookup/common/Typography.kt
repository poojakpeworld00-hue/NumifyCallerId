package com.contacts.callerid.number.lookup.common

import android.content.Context
import android.content.res.Configuration

/**
 * The app's one text-size control.
 *
 * Text sizes in this project come from two places — about 110 literal `sp` values
 * in layouts and about 175 `ssp` references into the sdp library's per-bucket
 * dimens — and neither is edit-once. The `ssp` values in particular live in a
 * dependency's `values-sw<N>dp` folders, so raising them means either overriding
 * a generated scale (and breaking the bucketing it exists for) or touching every
 * layout and hoping the sweep missed nothing.
 *
 * [scaled] sidesteps both. `fontScale` is the platform's own multiplier on every
 * `sp` in the process, so one number here moves literal `sp` and `ssp` alike, on
 * every screen, with nothing to keep in sync.
 *
 * **It raises the floor, never lowers a ceiling.** The factor multiplies whatever
 * the user has already chosen in system settings, so someone reading at 1.3x
 * still gets proportionally larger text here, not our number instead of theirs.
 * [MAX_EFFECTIVE_SCALE] caps how far the multiplication can push a layout, and
 * the [coerceAtLeast] below makes the cap a ceiling on *our* contribution only:
 * a user already past it keeps their own size untouched rather than being
 * silently shrunk to it.
 *
 * `dp` is unaffected, by design. Icons, avatars, the language screen's flag chip
 * and every fixed row height stay put while the type inside them grows, which is
 * what keeps the increase from turning into clipped text.
 */
object Typography {

    /**
     * How much larger than the system size this app draws text.
     *
     * Chosen to be clearly felt on the small grey secondary lines — the 11-13sp
     * labels that read as fine print on a phone — while staying inside the slack
     * that fixed-height rows and buttons already have. Raising it further is a
     * one-line change; check the dialer keypad and the call-detail hero first,
     * since those have the least vertical room.
     */
    private const val TEXT_SCALE = 1.08f

    /** How large our multiplication is allowed to make the effective scale. */
    private const val MAX_EFFECTIVE_SCALE = 1.5f

    /**
     * Wraps [base] in a context whose text is [TEXT_SCALE] larger, for an
     * Activity's `attachBaseContext`.
     *
     * Returns [base] itself when there is nothing to change, so a device already
     * above the cap pays nothing and keeps its own configuration object.
     */
    fun scaled(base: Context): Context {
        val current = base.resources.configuration.fontScale
        val target = (current * TEXT_SCALE)
            .coerceAtMost(MAX_EFFECTIVE_SCALE)
            .coerceAtLeast(current)
        if (target == current) return base

        val config = Configuration(base.resources.configuration)
        config.fontScale = target
        return base.createConfigurationContext(config)
    }
}
