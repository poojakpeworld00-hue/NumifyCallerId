package com.contacts.callerid.number.lookup.feature.language

import androidx.annotation.ColorRes
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.feature.finder.CountryCatalog

/**
 * A language the user can pick. [name], in English, and [nativeName] are proper
 * nouns shown identically whatever the current locale is, so they are constants
 * here. [flagIso] is the ISO-3166 alpha-2 code of the country whose flag stands
 * for this language, rendered as an emoji flag by [CountryCatalog.flag].
 *
 * [chipColor] is the flat colour behind the row's two-letter [code]. The redesign
 * leads each row with that chip rather than the flag: a language is not a country
 * (Spanish, Portuguese and Arabic all span many), and emoji flags render
 * differently or not at all depending on the OEM font. [flagIso] and [flag] are
 * kept because the finder screens still show a real country.
 */
data class LanguageOption(
    val tag: String,        // BCP-47 tag, e.g. "en", "es"
    val code: String,       // short badge, e.g. "EN"
    val name: String,       // English name
    val nativeName: String, // endonym
    val flagIso: String,    // country ISO-2 for the flag, e.g. "US", "IN"
    @ColorRes val chipColor: Int = R.color.ds_accent,
) {
    /** Emoji flag for the row's leading edge. */
    val flag: String get() = CountryCatalog.flag(flagIso)
}

object LocaleCatalog {
    val all: List<LanguageOption> = listOf(
        LanguageOption("en", "EN", "English", "English", "US", R.color.ds_lang_en),
        LanguageOption("hi", "HI", "Hindi", "हिन्दी", "IN", R.color.ds_lang_hi),
        LanguageOption("es", "ES", "Spanish", "Español", "ES", R.color.ds_lang_es),
        LanguageOption("fr", "FR", "French", "Français", "FR", R.color.ds_lang_fr),
        LanguageOption("pt", "PT", "Portuguese", "Português", "PT", R.color.ds_lang_pt),
        LanguageOption("th", "TH", "Thai", "ไทย", "TH", R.color.ds_lang_th),
        LanguageOption("zh", "ZH", "Chinese", "中文", "CN", R.color.ds_lang_zh),
        LanguageOption("ja", "JA", "Japanese", "日本語", "JP", R.color.ds_lang_ja),
        LanguageOption("ru", "RU", "Russian", "Русский", "RU", R.color.ds_lang_ru),
        LanguageOption("vi", "VI", "Vietnamese", "Tiếng Việt", "VN", R.color.ds_lang_vi),
        LanguageOption("tr", "TR", "Turkish", "Türkçe", "TR", R.color.ds_lang_tr)
    )

    private fun byTag(tag: String): LanguageOption? = all.firstOrNull { it.tag == tag }

    /** Always appended so every region gets English as a fallback language. */
    private const val ENGLISH = "en"

    /** Shown when we can't resolve a country: Hindi (English is appended below). */
    private val DEFAULT_SUGGESTED = listOf("hi")

    /**
     * Ordered *native* suggested-language tags per country, keyed by ISO-3166
     * alpha-2. [suggestedFor] appends English itself, so list only the local
     * language or languages here; any country not listed falls back to
     * [DEFAULT_SUGGESTED]. Only tags that also appear in [all] reach the screen.
     */
    private val SUGGESTED_BY_COUNTRY: Map<String, List<String>> = mapOf(
        // Americas
        "US" to listOf("es", "fr"),
        "CA" to listOf("fr"),
        "MX" to listOf("es"),
        "BR" to listOf("pt"),
        "AR" to listOf("es"),
        "CO" to listOf("es"),
        "CL" to listOf("es"),
        "PE" to listOf("es"),
        // South Asia — India and neighbours
        "IN" to listOf("hi"),
        "PK" to listOf("hi"),
        "BD" to listOf("hi"),
        "NP" to listOf("hi"),
        "LK" to listOf("hi"),
        // Europe
        "GB" to listOf(),
        "IE" to listOf(),
        "ES" to listOf("es"),
        "FR" to listOf("fr"),
        "BE" to listOf("fr"),
        "CH" to listOf("fr"),
        "PT" to listOf("pt"),
        "RU" to listOf("ru"),
        "UA" to listOf("ru"),
        "TR" to listOf("tr"),
        // Asia-Pacific
        "CN" to listOf("zh"),
        "HK" to listOf("zh"),
        "TW" to listOf("zh"),
        "SG" to listOf("zh"),
        "JP" to listOf("ja"),
        "TH" to listOf("th"),
        "VN" to listOf("vi"),
        "AU" to listOf(),
        "NZ" to listOf()
    )

    /**
     * The languages to feature under "Suggested" for a given country: the
     * region's native language or languages, plus English, always. It falls back
     * to Hindi and English when [iso2] is null or unrecognised. Order is preserved
     * and duplicates dropped, so an English-speaking country lists English once.
     */
    fun suggestedFor(iso2: String?): List<LanguageOption> {
        val base = SUGGESTED_BY_COUNTRY[iso2?.uppercase()] ?: DEFAULT_SUGGESTED
        val tags = (base + ENGLISH).distinct()
        return tags.mapNotNull { byTag(it) }
    }
}
