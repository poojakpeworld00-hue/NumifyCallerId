package com.callora.callerid.numberlookup.schema

import com.callora.callerid.numberlookup.store.PhonebookEntry

data class ContactSyncItem(
    val contactId: String,
    val firstNameOriginal: String? = "",
    val surName: String? = "",
    val jobPosition: String? = "",
    val websites: String? = "",
    var contactEmail: String? = "",
    var contactNumber: MutableList<NumberSyncItem>,
    var contactCreationTime: Long? = null,
)

data class NumberSyncItem(
    val normalizedNumber: String,
    val type: String,
)

/** Maps a device contact into the server upload shape. */
fun PhonebookEntry.toUploadModel(): ContactSyncItem {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    val firstName = parts.firstOrNull() ?: name
    val surName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

    return ContactSyncItem(
        contactId = detail.ifBlank { name },
        firstNameOriginal = firstName,
        surName = surName,
        jobPosition = "",
        websites = "",
        contactEmail = "",
        contactNumber = mutableListOf(
            NumberSyncItem(normalizedNumber = detail, type = "mobile")
        ),
        contactCreationTime = System.currentTimeMillis()
    )
}

fun List<PhonebookEntry>.toUploadList(): List<ContactSyncItem> = map { it.toUploadModel() }
