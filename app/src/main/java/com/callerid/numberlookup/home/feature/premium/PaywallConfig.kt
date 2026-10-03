package com.callerid.numberlookup.home.feature.premium

import android.content.Context
import com.callerid.numberlookup.home.monetize.billing.PremiumStore
import com.callerid.numberlookup.home.monetize.strategy.AudienceConfig

/**
 * Remote Config for the paywall: the `paywall` block inside each audience segment.
 *
 * ```
 * "organic":   { ..., "paywall": { "enabled": true,  "close_delay": 5 } },
 * "marketing": { ..., "paywall": { "enabled": true,  "close_delay": 30, "close_opacity": 50 } }
 * ```
 *
 * `enabled: false` turns selling off for that audience. Every offer disappears -
 * the Premium chips on Recents, Contacts and Tools, the Settings banner, the
 * uninstall funnel's Premium tip - and every feature Premium used to hold back
 * falls back to its free, ad-backed form: hidden names can always be revealed
 * with an ad, and the call timeline shows every call. Nothing points at a
 * paywall that is not there.
 *
 * It never takes anything from someone who already owns Premium: their
 * entitlement, their status card and their manage-subscription path all stay.
 *
 * Missing block or key: on, with the design's 5 s close delay (at most 30) and
 * a fully solid close cross (`close_opacity`, 20–100).
 */
object PaywallConfig {

    private const val RC_KEY = "paywall"

    /** The design's `closeDelay`: 5 s unless Remote Config says otherwise. */
    private const val DEFAULT_CLOSE_DELAY_SECONDS = 5

    /**
     * Held to this at most, so a typo cannot trap anyone on the page. Even at
     * the cap, "Continue without Premium" is there from the first frame.
     */
    private const val MAX_CLOSE_DELAY_SECONDS = 30

    /** The close cross at full strength unless Remote Config fades it. */
    private const val DEFAULT_CLOSE_OPACITY = 100

    /** Never fainter than this: a cross nobody can see is not a way out. */
    private const val MIN_CLOSE_OPACITY = 20

    /** Whether the app sells Premium to this audience at all. */
    fun isEnabled(): Boolean = AudienceConfig.block(RC_KEY)?.optBoolean("enabled", true) ?: true

    /**
     * Whether to put an offer in front of this user: selling is on and they do
     * not already have Premium. Every Premium chip, banner, lock and tip asks this.
     */
    fun isOffered(context: Context): Boolean = isEnabled() && !PremiumStore.isPremium(context)

    /** Seconds before the paywall's close cross appears; 0 shows it at once. */
    fun closeDelaySeconds(): Int =
        (AudienceConfig.block(RC_KEY)?.optInt("close_delay", DEFAULT_CLOSE_DELAY_SECONDS)
            ?: DEFAULT_CLOSE_DELAY_SECONDS).coerceIn(0, MAX_CLOSE_DELAY_SECONDS)

    /**
     * `close_opacity` - how solid the close cross is once it appears, in percent:
     * 100 is full, 50 half-faded. Held to [MIN_CLOSE_OPACITY]–100.
     */
    fun closeOpacity(): Float =
        (AudienceConfig.block(RC_KEY)?.optInt("close_opacity", DEFAULT_CLOSE_OPACITY)
            ?: DEFAULT_CLOSE_OPACITY).coerceIn(MIN_CLOSE_OPACITY, 100) / 100f
}
