package com.callerid.numberlookup.home.feature.intro

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.callerid.numberlookup.home.R

/**
 * A page shows EITHER a composed [customArtRes] layout (when non-zero) or a
 * simple [artRes] drawable.
 *
 * The headline arrives in two pieces. The design sets it over two lines with the
 * second in the page's accent colour — blue on the caller and lookup pages, red on
 * the scam one — so [titleLeadRes] and [titleAccentRes] hold the two halves and
 * [accentColorRes] says what colour the second takes. Keeping them apart means the
 * break stays where the design puts it in every language, instead of depending on
 * where a translated string happens to wrap.
 */
data class IntroSlide(
    @param:StringRes val titleLeadRes: Int,
    @param:StringRes val titleAccentRes: Int,
    @param:StringRes val descRes: Int,
    @param:ColorRes val accentColorRes: Int = R.color.ds_accent,
    @param:DrawableRes val artRes: Int = 0,
    @param:LayoutRes val customArtRes: Int = 0
)

object WelcomeSlides {
    val all: List<IntroSlide> = listOf(
        IntroSlide(
            titleLeadRes = R.string.onboarding_title_lead,
            titleAccentRes = R.string.onboarding_title_accent,
            descRes = R.string.onboarding_desc,
            customArtRes = R.layout.illustration_welcome_caller,
        ),
        IntroSlide(
            titleLeadRes = R.string.onboarding_title_2_lead,
            titleAccentRes = R.string.onboarding_title_2_accent,
            descRes = R.string.onboarding_desc_2,
            // The scam page swaps the accent to the danger red, which is the only
            // place in onboarding the brand blue steps aside.
            accentColorRes = R.color.ds_danger,
            customArtRes = R.layout.illustration_welcome_spam,
        ),
        IntroSlide(
            titleLeadRes = R.string.onboarding_title_3_lead,
            titleAccentRes = R.string.onboarding_title_3_accent,
            descRes = R.string.onboarding_desc_3,
            customArtRes = R.layout.illustration_welcome_spam_alt,
        )
    )
}
