package com.callerid.numberlookup.home.entity

import java.util.Locale

/**
 * `GET /similar-phone-number?phone=…`, as the contact-saver API actually sends it:
 *
 * ```
 * { "success": true, "count": 8, "country": "IN",
 *   "location": { "city": "Surat", "state": "Gujarat", "pincode": "394107" },
 *   "spam": false, "spamCount": 0,
 *   "data": [ { "name": "MR GOPALBHAI" }, { "name": "Pooja" }, … ] }
 * ```
 *
 * The verdict about the number - country, location, spam - is at the top level,
 * once. `data` is only the names other people saved it under, up to 100 of them,
 * unfiltered. The app used to read all of it from inside the `data` rows, where
 * the server never put it, so a reported spammer was never flagged and the city
 * and country never came from the network. Read the number through [toFacts],
 * [primaryName] and [meaningfulNames] rather than the raw fields.
 *
 * All fields nullable or defaulted: Gson fills absent keys with null / false / 0.
 */
data class LookupPayload(
    val success: Boolean,
    val count: Int = 0,
    val data: List<CallerFacts>?,
    /** ISO 3166-1 alpha-2, e.g. "IN". See [countryName]. */
    val country: String? = null,
    val location: LookupLocation? = null,
    val spam: Boolean = false,
    val spamCount: Int = 0,
) {
    private val rows: List<CallerFacts> get() = data.orEmpty()

    /** Spam per the server's verdict, or any older per-row flag. */
    val isSpam: Boolean
        get() = spam || rows.any { it.is_spam || it.is_user_spam }

    /** How many reports, from the top-level count or the largest per-row one. */
    val reportCount: Int
        get() = maxOf(spamCount, rows.maxOfOrNull { it.spamReportCounter } ?: 0)

    val city: String?
        get() = location?.city?.takeIf { it.isNotBlank() }
            ?: rows.firstNotNullOfOrNull { it.city?.takeIf { c -> c.isNotBlank() } }

    /** Country display name in the device language ("IN" → "India"), else a row's own. */
    val countryName: String?
        get() = country?.trim()?.takeIf { it.length == 2 }
            ?.let { Locale("", it.uppercase(Locale.ROOT)).displayCountry.takeIf(String::isNotBlank) }
            ?: rows.firstNotNullOfOrNull { it.country?.takeIf { c -> c.isNotBlank() } }

    /**
     * The names worth showing, best first, without the junk the endpoint returns
     * alongside them: entries that are only the number or a fragment of it
     * ("+919876543210", "98"), a single repeated letter ("Llllll"), and the same
     * name again with different case or punctuation ("MR DEEPAK" / "MR. DEEPAK").
     * Order is the server's, which puts the most common name first.
     */
    fun meaningfulNames(): List<String> {
        val seen = HashSet<String>()
        return rows.mapNotNull { row ->
            val name = row.name?.trim()?.replace(Regex("\\s+"), " ")
            if (name.isNullOrEmpty()) return@mapNotNull null
            val letters = name.filter(Char::isLetter)
            if (letters.isEmpty()) return@mapNotNull null
            if (letters.lowercase().toSet().size < 2) return@mapNotNull null
            val key = name.lowercase().filter(Char::isLetterOrDigit)
            if (seen.add(key)) name else null
        }
    }

    /** The name to show for the number, or null when the network has none worth showing. */
    fun primaryName(): String? = meaningfulNames().firstOrNull()

    /**
     * The whole answer folded into one [CallerFacts] - the shape the caller-ID card
     * and the post-call screen already consume - with the top-level verdict
     * merged in. Null when the network knows nothing at all about the number.
     */
    fun toFacts(): CallerFacts? {
        val name = primaryName()
        val row = rows.firstOrNull()
        if (name == null && !isSpam && city == null && row == null) return null
        return (row ?: CallerFacts()).copy(
            name = name,
            is_spam = isSpam,
            spamReportCounter = reportCount,
            city = city,
            country = countryName,
        )
    }
}

/** The `location` block. Any field may be null (e.g. for numbers outside India). */
data class LookupLocation(
    val city: String? = null,
    val state: String? = null,
    val pincode: String? = null,
)

data class CallerFacts(
    val is_spam: Boolean = false,
    val is_user_spam: Boolean = false,
    val spamReportCounter: Int = 0,
    val spamType: String? = null,
    val name: String? = null,
    val profile: String? = null,
    val city: String? = null,
    val country: String? = null,
    val carrier: String? = null,
    // Line type may arrive under different keys depending on the backend.
    val line_type: String? = null,
    val lineType: String? = null,
    val type: String? = null
) {
    /** Carrier if present and non-blank. */
    val carrierOrNull: String? get() = carrier?.takeIf { it.isNotBlank() }

    /** Best-effort line type from whichever field the server populated. */
    val lineTypeOrNull: String?
        get() = (line_type ?: lineType ?: type)?.takeIf { it.isNotBlank() }
}
