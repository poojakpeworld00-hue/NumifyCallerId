package com.contacts.callerid.number.lookup.feature.overlay

import android.content.Context
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore

/**
 * The app-wide switch for *asking* about the "display over other apps"
 * permission.
 *
 * The app asked in six places — after the call-log grant, after the contacts
 * grant, from the permission sheet, from the Recents protection strip, from the
 * overlay banner, and (newest) before a tool opens — and only the last was
 * controllable without a release. This is the one switch over all of them.
 *
 * **Asking only.** It never touches what the app does with a permission it
 * already holds: someone who granted the overlay and relies on the caller-ID
 * card keeps it. Turning off a prompt must not remove a feature.
 *
 * Where the ask is something the user taps — the protection strip's "Turn on",
 * the overlay banner's Enable — the control is *hidden* rather than left inert.
 * A button that visibly does nothing is worse than no button.
 */
object OverlayAskPolicy {

    /**
     * Remote Config key. **Defaults to true**, unlike the rest of the boolean
     * config: this switch exists to take something away, so a config that has
     * not been published yet, or that fails to fetch, has to leave the app as it
     * already was.
     */
    const val RC_KEY = "overlay_ask_enabled"

    /** Whether the app may ask for the overlay permission at all. */
    fun isAskingAllowed(context: Context): Boolean =
        AdPreferenceStore.getInstance(context).getBoolean(RC_KEY, true)
}
