package com.callerid.numberlookup.home.common

import android.content.Context
import android.content.res.ColorStateList
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * Picks the colour behind a contact's initials.
 *
 * The design gives every avatar its own flat colour rather than one shared grey,
 * which is what lets a list of initials be scanned at a glance. It supplies those
 * colours as data; here they are derived instead, from a stable hash of whatever
 * identifies the row — so the same person keeps the same colour between launches,
 * across sorting changes, and on both themes.
 *
 * The hash is deliberately not [String.hashCode]: that is only stable within a JVM
 * run for some types and reads unevenly across short strings. A small FNV-style
 * fold over the characters spreads short names — which is nearly all of them —
 * evenly across the palette.
 */
object AvatarPalette {

    /** The handoff's accent family. Ordered so adjacent entries stay distinguishable. */
    @ColorRes
    private val swatches = intArrayOf(
        R.color.ds_lang_en,
        R.color.ds_lang_ja,
        R.color.ds_lang_pt,
        R.color.ds_lang_th,
        R.color.ds_lang_es,
        R.color.ds_lang_hi,
        R.color.ds_lang_vi,
        R.color.ds_lang_fr,
        R.color.ds_lang_zh,
        R.color.ds_lang_tr,
        R.color.ds_lang_ru,
    )

    /** The swatch for [key], as a colour int. */
    fun colorFor(context: Context, key: String): Int =
        ContextCompat.getColor(context, resFor(key))

    /** The swatch for [key], ready for `backgroundTintList`. */
    fun tintFor(context: Context, key: String): ColorStateList =
        ColorStateList.valueOf(colorFor(context, key))

    @ColorRes
    fun resFor(key: String): Int {
        if (key.isEmpty()) return swatches[0]
        var hash = 2166136261L.toInt()
        for (ch in key) {
            hash = hash xor ch.code
            hash *= 16777619
        }
        val index = (hash % swatches.size + swatches.size) % swatches.size
        return swatches[index]
    }
}
