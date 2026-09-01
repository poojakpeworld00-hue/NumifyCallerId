package com.numify.callerid.numberlookup.screen.search

/** Result of identifying a phone number (on-device + optional online metadata). */
data class NumberVerdict(
    val name: String?,        // contact name, or null if unknown
    val number: String,       // formatted number
    val rawNumber: String,    // original digits for dialing / saving
    val inContacts: Boolean,
    val regionCode: String,   // country calling code, e.g. "+1"
    // Online enrichment (null when no API key / offline):
    val country: String? = null,
    val carrier: String? = null,
    val lineType: String? = null,
    val valid: Boolean? = null,
    val city: String? = null,
    val isSpam: Boolean = false,
    val spamType: String? = null,
    val nicknames: List<String> = emptyList() // alternate names users saved this number under
)

/** A persisted recent lookup shown in the history list. */
data class TrailEntry(
    val rawNumber: String,
    val number: String,
    val name: String?,
    val subtitle: String?
)

/** UI state for the Lookup screen. */
sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Result(val result: NumberVerdict) : SearchState
}
