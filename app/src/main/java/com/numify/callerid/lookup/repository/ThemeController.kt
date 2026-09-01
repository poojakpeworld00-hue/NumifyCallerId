package com.numify.callerid.lookup.repository

import androidx.appcompat.app.AppCompatDelegate

/**
 * App-wide light/dark theme control. Modes are ordered to match the Settings
 * theme dialog (Light, Dark, System default).
 */
object ThemeController {

    /** Ordered to match the theme dialog items. */
    val modes = intArrayOf(
        AppCompatDelegate.MODE_NIGHT_NO,            // Light (default)
        AppCompatDelegate.MODE_NIGHT_YES,           // Dark
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM  // System default
    )

    /** Applies [mode] globally; recreates running activities to reflect it. */
    fun apply(mode: Int) = AppCompatDelegate.setDefaultNightMode(mode)

    /** Dialog index for the stored [mode] (defaults to Light if unknown). */
    fun indexOf(mode: Int): Int = modes.indexOf(mode).coerceAtLeast(0)
}
