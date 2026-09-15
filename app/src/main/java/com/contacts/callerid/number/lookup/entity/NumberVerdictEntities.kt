package com.contacts.callerid.number.lookup.entity

data class LookupPayload(
    val success: Boolean,
    val count: Int = 0,
    val data: List<CallerFacts>?
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
