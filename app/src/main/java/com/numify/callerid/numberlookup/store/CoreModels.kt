package com.numify.callerid.numberlookup.store

enum class CallDirection { INCOMING, OUTGOING, MISSED, SPAM }

data class CallLogRow(
    val name: String,
    val time: String,
    val info: String,
    val initials: String,
    val type: CallDirection,
    val number: String = "",
    /** True when a contact name resolved; false for unknown/unsaved numbers (→ "Identify"). */
    val identified: Boolean = true
)

data class PhonebookEntry(
    val name: String,
    val detail: String,
    val initials: String,
    val photoUri: String? = null,
    val starred: Boolean = false,
    val lastContacted: Long = 0L,
    val inGroup: Boolean = false
)

/** Demo data used until real CallLog / Contacts providers are wired in. */
object DemoSeedData {

    val recents: List<CallLogRow> = listOf(
        CallLogRow("Sarah Khan", "9:24", "Incoming · 4m 12s", "SK", CallDirection.INCOMING),
        CallLogRow("+1 (800) 244-0199", "8:50", "Spam · Telemarketer", "!", CallDirection.SPAM),
        CallLogRow("Dad Mobile", "7:32", "Missed call", "DM", CallDirection.MISSED),
        CallLogRow("+44 20 7946 0321", "Tue", "Outgoing · London, UK", "+9", CallDirection.OUTGOING),
        CallLogRow("Aisha Lawson", "Tue", "Incoming · 1m 03s", "AL", CallDirection.INCOMING)
    )

    val homeRecent: List<CallLogRow> = recents.take(2)

    val contacts: List<PhonebookEntry> = listOf(
        PhonebookEntry("Aisha Lawson", "+1 (415) 555-0178", "AL"),
        PhonebookEntry("Amir Raza", "Acme Corp", "AR"),
        PhonebookEntry("Dad Mobile", "+1 (415) 555-0143", "DM"),
        PhonebookEntry("Sarah Khan", "Brightline Bank", "SK")
    )
}
