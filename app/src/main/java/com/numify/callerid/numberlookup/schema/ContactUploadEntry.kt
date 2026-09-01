package com.numify.callerid.numberlookup.schema

import com.numify.callerid.numberlookup.store.ContactRecord

data class ContactUploadEntry(
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
fun ContactRecord.toUploadModel(): ContactUploadEntry {
    val parts = name.trim().split(" ").filter { it.isNotEmpty() }
    val firstName = parts.firstOrNull() ?: name
    val surName = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

    return ContactUploadEntry(
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

fun List<ContactRecord>.toUploadList(): List<ContactUploadEntry> = map { it.toUploadModel() }
