package com.callerid.numberlookup.home.repository

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler

/** One saved phone number and the contact it belongs to. */
data class ContactPhone(val name: String?, val number: String)

/** Reads device contacts via the [ContactsContract] provider. */
class ContactRepository(private val context: Context) {

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

    /**
     * Every saved number that has a picture, keyed by its last [MATCH_DIGITS]
     * digits — the same rule the rest of the app compares numbers by.
     *
     * One query for the whole address book rather than a PhoneLookup per row:
     * the call log is bound in bulk, and a lookup per row would put a content
     * query inside onBindViewHolder.
     */
    fun photoUriByNumber(): Map<String, String> {
        val out = HashMap<String, String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                // Only rows that actually carry a picture.
                "${ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI} IS NOT NULL",
                null,
                null
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx =
                    cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                if (numberIdx < 0 || photoIdx < 0) return@use

                while (cursor.moveToNext()) {
                    val photo = cursor.getString(photoIdx)?.takeIf { it.isNotBlank() } ?: continue
                    val tail = cursor.getString(numberIdx).orEmpty()
                        .filter(Char::isDigit)
                        .takeLast(MATCH_DIGITS)
                    if (tail.isNotEmpty()) out.putIfAbsent(tail, photo)
                }
            }
        }
        return out
    }

    /**
     * Every saved phone number with the name it belongs to, one row per number.
     *
     * Deliberately not [getContacts], which collapses a contact to a single row
     * keyed by display name and so drops second and third numbers. Dialer search
     * has to match on any of them: someone who types a work number should find
     * the person, not nothing.
     */
    fun phoneEntries(): List<ContactPhone> {
        val out = mutableListOf<ContactPhone>()
        val seen = HashSet<String>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )

        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext()) {
                    val number = cursor.getString(numberIdx)?.trim().orEmpty()
                    if (number.isEmpty()) continue
                    // One contact can hold the same number twice (a merged
                    // duplicate, or the same line saved with and without a
                    // country code). Key on the digits so search shows it once.
                    val key = number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
                    if (key.isEmpty() || !seen.add(key)) continue
                    out += ContactPhone(
                        name = cursor.getString(nameIdx)?.trim().orEmpty().ifEmpty { null },
                        number = number,
                    )
                }
            }
        }
        return out
    }

    /**
     * First email address per contact id.
     *
     * One query for the whole address book, joined by id below, rather than a
     * per-contact lookup inside the cursor loop — the same bulk shape
     * [photoUriByNumber] uses, and for the same reason: a query per row turns a
     * 2,000-contact load into 2,000 content-provider round trips.
     *
     * First one wins. A contact with a work and a personal address gets one of
     * them in search; matching every address would mean a second collection and
     * a list-valued field for a case the row has no room to show anyway.
     */
    private fun emailByContactId(): Map<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val addressIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                if (idIdx < 0 || addressIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val address = cursor.getString(addressIdx)?.trim().orEmpty()
                    if (address.isNotEmpty()) out.putIfAbsent(cursor.getLong(idIdx), address)
                }
            }
        }
        return out
    }

    /**
     * The account each contact is stored under, by contact id.
     *
     * Accounts live on RawContacts, not on Contacts: a contact is the merge of
     * one or more raw contacts, each belonging to a store. Bulk-queried and
     * joined below for the same reason as the emails — one round trip, not one
     * per row.
     *
     * First raw contact wins. A contact merged across two Google accounts
     * genuinely belongs to both, and the system Contacts app counts it under
     * each; picking one keeps a contact in exactly one bucket, so the counts the
     * picker shows are the counts the filtered list actually produces. Matching
     * the list you get is worth more here than matching Google's arithmetic.
     */
    private fun accountByContactId(): Map<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.RawContacts.CONTACT_ID,
                    ContactsContract.RawContacts.ACCOUNT_NAME,
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID)
                val accountIdx = cursor.getColumnIndex(ContactsContract.RawContacts.ACCOUNT_NAME)
                if (idIdx < 0 || accountIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val account = cursor.getString(accountIdx)?.trim().orEmpty()
                    if (account.isNotEmpty()) out.putIfAbsent(cursor.getLong(idIdx), account)
                }
            }
        }
        return out
    }

    fun getContacts(): List<ContactRecord> {
        val groupContactIds = queryGroupContactIds()
        val emails = emailByContactId()
        val accounts = accountByContactId()

        // Keyed by display name to collapse multiple numbers of the same contact.
        val byName = LinkedHashMap<String, ContactRecord>()
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
                byName[name] = ContactRecord(
                    name = name,
                    detail = number,
                    initials = CallActionHandler.initials(name, number),
                    photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null,
                    starred = starredIdx >= 0 && cursor.getInt(starredIdx) == 1,
                    lastContacted = if (lastIdx >= 0) cursor.getLong(lastIdx) else 0L,
                    inGroup = groupContactIds.contains(contactId),
                    email = emails[contactId],
                    accountName = accounts[contactId],
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

    private companion object {
        /** Compare on the last N digits, the rule the whole app matches numbers by. */
        const val MATCH_DIGITS = 10
    }
}
