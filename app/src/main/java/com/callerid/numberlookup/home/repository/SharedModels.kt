package com.callerid.numberlookup.home.repository

enum class CallType { INCOMING, OUTGOING, MISSED, SPAM }

data class CallLogEntry(
    val name: String,
    val time: String,
    val info: String,
    val initials: String,
    val type: CallType,
    val number: String = "",
    /** True when a contact name resolved; false for unknown/unsaved numbers (→ "Identify"). */
    val identified: Boolean = true
)

data class ContactRecord(
    val name: String,
    val detail: String,
    val initials: String,
    val photoUri: String? = null,
    val starred: Boolean = false,
    val lastContacted: Long = 0L,
    val inGroup: Boolean = false,
    /**
     * The contact's first email address, or null when they have none.
     *
     * Carried so search can match on it: people look each other up by address as
     * readily as by name, and a contact saved from a mail app may have nothing
     * else worth typing. It is not part of [detail] — that is the row's phone
     * number and the thing the row dials.
     */
    val email: String? = null,
    /**
     * The account this contact is stored under — a Google address, "SIM", the
     * device itself — or null when the provider reports none (a local-only
     * contact). Drives the "All contacts" account filter in the header.
     */
    val accountName: String? = null,
)

/**
 * One row in the account picker: a store of contacts and how many of them it
 * holds. [name] is null for the "All contacts" entry, which is the whole pool.
 */
data class ContactAccount(
    val name: String?,
    val count: Int,
)

/** Demo data used until real CallLog / Contacts providers are wired in. */
object SampleDataSeed {

    val recents: List<CallLogEntry> = listOf(
        CallLogEntry("Sarah Khan", "9:24", "Incoming · 4m 12s", "SK", CallType.INCOMING),
        CallLogEntry("+1 (800) 244-0199", "8:50", "Spam · Telemarketer", "!", CallType.SPAM),
        CallLogEntry("Dad Mobile", "7:32", "Missed call", "DM", CallType.MISSED),
        CallLogEntry("+44 20 7946 0321", "Tue", "Outgoing · London, UK", "+9", CallType.OUTGOING),
        CallLogEntry("Aisha Lawson", "Tue", "Incoming · 1m 03s", "AL", CallType.INCOMING)
    )

    val homeRecent: List<CallLogEntry> = recents.take(2)

    val contacts: List<ContactRecord> = listOf(
        ContactRecord("Aisha Lawson", "+1 (415) 555-0178", "AL"),
        ContactRecord("Amir Raza", "Acme Corp", "AR"),
        ContactRecord("Dad Mobile", "+1 (415) 555-0143", "DM"),
        ContactRecord("Sarah Khan", "Brightline Bank", "SK")
    )
}
