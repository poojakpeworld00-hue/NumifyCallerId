package com.numify.callerid.numberlookup.screen.welcome

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.numify.callerid.numberlookup.R

/**
 * A page shows EITHER a composed [customArtRes] layout (when non-zero) or a
 * simple [artRes] drawable.
 */
data class IntroSlide(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    @param:DrawableRes val artRes: Int = 0,
    @param:LayoutRes val customArtRes: Int = 0
)

object WelcomeSlides {
    val all: List<IntroSlide> = listOf(
        IntroSlide(R.string.onboarding_title, R.string.onboarding_desc, customArtRes = R.layout.art_welcome_caller),
        IntroSlide(R.string.onboarding_title_2, R.string.onboarding_desc_2, customArtRes = R.layout.art_welcome_spam),
        IntroSlide(R.string.onboarding_title_3, R.string.onboarding_desc_3, customArtRes = R.layout.art_welcome_spam_alt)
    )
}
