package com.callora.callerid.numberlookup.store

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.callora.callerid.numberlookup.screen.shared.CallPresenter

/** Reads device contacts via the [ContactsContract] provider. */
class PhonebookSource(private val context: Context) {

    /**
     * Reverse-lookup a phone number against the device contacts.
     * Returns the contact display name, or null if not found / not permitted.
     */
    fun lookupNameByNumber(number: String): String? {
        if (number.isBlank()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }

    fun getContacts(): List<PhonebookEntry> {
        val groupContactIds = queryGroupContactIds()

        // Keyed by display name to collapse multiple numbers of the same contact.
        val byName = LinkedHashMap<String, PhonebookEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED,
            ContactsContract.Contacts.LAST_TIME_CONTACTED
        )

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
            val starredIdx = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
            val lastIdx = cursor.getColumnIndex(ContactsContract.Contacts.LAST_TIME_CONTACTED)

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                if (name.isEmpty() || byName.containsKey(name)) continue
                val number = cursor.getString(numberIdx)?.trim().orEmpty()
                val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                byName[name] = PhonebookEntry(
                    name = name,
                    detail = number,
                    initials = CallPresenter.initials(name, number),
                    photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null,
                    starred = starredIdx >= 0 && cursor.getInt(starredIdx) == 1,
                    lastContacted = if (lastIdx >= 0) cursor.getLong(lastIdx) else 0L,
                    inGroup = groupContactIds.contains(contactId)
                )
            }
        }
        return byName.values.toList()
    }

    /** Contact IDs that belong to at least one contact group. */
    private fun queryGroupContactIds(): Set<Long> {
        val ids = HashSet<Long>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                while (cursor.moveToNext()) {
                    if (idIdx >= 0) ids.add(cursor.getLong(idIdx))
                }
            }
        }
        return ids
    }
}
