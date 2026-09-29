package com.callerid.numberlookup.home.feature.intro

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.callerid.numberlookup.home.R

/**
 * One intro page: a feature, the real screen it lives on, and how to get there.
 *
 * The headline arrives in two pieces. The design sets it over two lines with the
 * second in the page's accent, so [titleLeadRes] and [titleAccentRes] hold the two
 * halves and [accentColorRes] colours the second. Keeping them apart means the
 * break stays where the design puts it in every language, instead of depending on
 * where a translated string happens to wrap.
 *
 * [heroRes] is the page's hero - a cropped miniature of the screen the feature is
 * on, with the control spotlighted. [path] is the "Find it in" row under it: the
 * steps to that control, the last one highlighted, and [pathNoteRes] the grey
 * aside that trails them ("or Recents › Identify").
 */
data class IntroSlide(
    @param:StringRes val titleLeadRes: Int,
    @param:StringRes val titleAccentRes: Int,
    @param:StringRes val descRes: Int,
    @param:ColorRes val accentColorRes: Int,
    @param:LayoutRes val heroRes: Int,
    val path: List<PathStep>,
    @param:StringRes val pathNoteRes: Int = 0,
    /** The highlighted step's fill, and the ink its label takes on that fill. */
    @param:ColorRes val chipTintRes: Int,
    @param:ColorRes val chipInkRes: Int,
)

/** One step of a "Find it in" path; [highlighted] is the control the page is about. */
data class PathStep(
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
    val highlighted: Boolean,
)

/**
 * The three pages, in the order the design gives them: Lookup first (the app's
 * main promise), Block second (what you do after a lookup), Contacts last (daily
 * use).
 */
object IntroSlides {
    val all: List<IntroSlide> = listOf(
        IntroSlide(
            titleLeadRes = R.string.intro_lookup_lead,
            titleAccentRes = R.string.intro_lookup_accent,
            descRes = R.string.intro_lookup_desc,
            accentColorRes = R.color.ds_accent,
            heroRes = R.layout.illustration_intro_lookup,
            path = listOf(
                PathStep(R.drawable.ic_ds_nav_dialer, R.string.nav_dialer, highlighted = false),
                PathStep(R.drawable.ic_ds_nav_lookup, R.string.dialer_grid_lookup, highlighted = true),
            ),
            pathNoteRes = R.string.intro_lookup_note,
            chipTintRes = R.color.ds_selected_tint,
            chipInkRes = R.color.ds_accent,
        ),
        IntroSlide(
            titleLeadRes = R.string.intro_block_lead,
            titleAccentRes = R.string.intro_block_accent,
            descRes = R.string.intro_block_desc,
            accentColorRes = R.color.ds_danger,
            heroRes = R.layout.illustration_intro_block,
            path = listOf(
                PathStep(R.drawable.ic_ds_nav_recents, R.string.intro_block_path_any, highlighted = false),
                PathStep(R.drawable.ic_ds_block_slash, R.string.action_block, highlighted = true),
            ),
            pathNoteRes = R.string.intro_block_note,
            chipTintRes = R.color.ds_danger_tint,
            chipInkRes = R.color.intro_chip_ink_block,
        ),
        IntroSlide(
            titleLeadRes = R.string.intro_contacts_lead,
            titleAccentRes = R.string.intro_contacts_accent,
            descRes = R.string.intro_contacts_desc,
            accentColorRes = R.color.ds_violet,
            heroRes = R.layout.illustration_intro_contacts,
            path = listOf(
                PathStep(R.drawable.ic_ds_nav_contacts, R.string.intro_contacts_path, highlighted = true),
            ),
            pathNoteRes = R.string.intro_contacts_note,
            chipTintRes = R.color.ds_violet_wash,
            chipInkRes = R.color.intro_chip_ink_contacts,
        ),
    )
}
