package com.callerid.numberlookup.home.repository

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler

/** One saved phone number and the contact it belongs to. */
data class ContactPhone(val name: String?, val number: String)

/** Reads device contacts via the [ContactsContract] provider. */
class ContactRepository(private val context: Context) {

    /** READ_CONTACTS is held; without it every read here comes back empty. */
    fun canRead(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

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

    /** A saved contact's display name and picture, as one lookup returns them. */
    data class SavedCaller(val name: String?, val photoUri: String?)

    /**
     * The saved contact behind [number] - name and photo together.
     *
     * Callers that want both used to run [lookupNameByNumber] and then go back
     * for the picture, which is two content-provider round trips for one row of
     * the same table. PhoneLookup hands over both columns at once.
     */
    fun savedCallerByNumber(number: String): SavedCaller? {
        if (number.isBlank()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI,
                ),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    SavedCaller(cursor.getString(0), cursor.getString(1))
                } else null
            }
        }.getOrNull()
    }

    /**
     * The contact id behind [number], or null when the number is not saved.
     *
     * PhoneLookup is the only index that matches a number the way the dialer
     * does - country code, spacing and punctuation all ignored - so it is what
     * decides whether a number has a contact to star at all.
     */
    fun contactIdForNumber(number: String): Long? {
        if (number.isBlank()) return null
        return runCatching {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }

    /** Whether [number] belongs to a contact the user has starred. */
    fun isStarred(number: String): Boolean {
        val id = contactIdForNumber(number) ?: return false
        return runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.STARRED),
                ContactsContract.Contacts._ID + " = ?",
                arrayOf(id.toString()),
                null
            )?.use { c -> c.moveToFirst() && c.getInt(0) == 1 }
        }.getOrNull() ?: false
    }

    /**
     * Stars or unstars the contact behind [number]. Returns false when there is
     * no contact, or when the write is refused - the caller shows the reason
     * rather than flipping an icon that did not take.
     *
     * Writes ContactsContract.Contacts.STARRED, the same flag the system
     * Contacts app sets, so a favourite made here shows up everywhere the
     * user expects it to. Needs WRITE_CONTACTS.
     */
    fun setStarred(number: String, starred: Boolean): Boolean {
        val id = contactIdForNumber(number) ?: return false
        return runCatching {
            val values = ContentValues().apply {
                put(ContactsContract.Contacts.STARRED, if (starred) 1 else 0)
            }
            context.contentResolver.update(
                ContactsContract.Contacts.CONTENT_URI,
                values,
                ContactsContract.Contacts._ID + " = ?",
                arrayOf(id.toString())
            ) > 0
        }.getOrDefault(false)
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
     * Every email address per contact id.
     *
     * One query for the whole address book, joined by id below, rather than a
     * per-contact lookup inside the cursor loop — the same bulk shape
     * [photoUriByNumber] uses, and for the same reason: a query per row turns a
     * 2,000-contact load into 2,000 content-provider round trips.
     *
     * All of them, not the first: keeping one meant a contact with a work and a
     * personal address could only be found by whichever the provider listed
     * first, and searching the other found nothing.
     */
    private fun emailsByContactId(): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
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
                    if (address.isEmpty()) continue
                    val list = out.getOrPut(cursor.getLong(idIdx)) { ArrayList() }
                    if (list.none { it.equals(address, ignoreCase = true) }) list.add(address)
                }
            }
        }
        return out
    }

    /**
     * Contacts saved with an email address and no phone number, for search.
     *
     * [getContacts] reads the phone table, so these never reached it and could
     * not be found by anything — including the email address that is all they
     * have. The row carries the first address as its [ContactRecord.detail].
     */
    fun emailOnlyContacts(): List<ContactRecord> {
        val byId = LinkedHashMap<Long, ContactRecord>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI,
                ),
                "${ContactsContract.CommonDataKinds.Email.HAS_PHONE_NUMBER} = 0",
                null,
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME} COLLATE LOCALIZED ASC"
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.DISPLAY_NAME)
                val addressIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.PHOTO_THUMBNAIL_URI)
                if (idIdx < 0 || addressIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val address = cursor.getString(addressIdx)?.trim().orEmpty()
                    if (address.isEmpty()) continue
                    val id = cursor.getLong(idIdx)
                    val existing = byId[id]
                    if (existing != null) {
                        byId[id] = existing.copy(emails = existing.emails + address)
                        continue
                    }
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                        ?.trim().orEmpty().ifEmpty { address }
                    byId[id] = ContactRecord(
                        name = name,
                        detail = address,
                        initials = CallActionHandler.initials(name, address),
                        photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null,
                        emails = listOf(address),
                        contactId = id,
                        hasPhone = false,
                    )
                }
            }
        }
        return byId.values.toList()
    }

    /**
     * Members of the user's groups who have no phone number, for the Groups tab.
     *
     * [getContacts] reads the phone table, so a group of people saved by name or
     * email alone counted as empty and the tab said "No groups yet" over groups
     * the user had made and filled. These rows join only the Groups tab: the
     * address book list stays a list of people you can call. No call button
     * (no [ContactRecord.hasPhone]); the detail line is their first email, and a
     * contact saved with nothing at all reads as "Unknown".
     */
    fun phonelessGroupMembers(): List<ContactRecord> {
        val groups = groupsByContactId()
        if (groups.isEmpty()) return emptyList()
        val emails = emailsByContactId()
        val accounts = accountByContactId()
        val unnamed = context.getString(R.string.common_unknown)
        val out = mutableListOf<ContactRecord>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                    ContactsContract.Contacts.STARRED,
                ),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 0 AND " +
                    "${ContactsContract.Contacts._ID} IN (${groups.keys.joinToString(",")})",
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIdx = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val starredIdx = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                if (idIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val addresses = emails[id].orEmpty()
                    val name = (if (nameIdx >= 0) cursor.getString(nameIdx) else null)
                        ?.trim().orEmpty()
                        .ifEmpty { addresses.firstOrNull() ?: unnamed }
                    val detail = addresses.firstOrNull().orEmpty()
                    out += ContactRecord(
                        name = name,
                        detail = detail,
                        initials = CallActionHandler.initials(name, detail),
                        photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null,
                        starred = starredIdx >= 0 && cursor.getInt(starredIdx) == 1,
                        groups = groups[id].orEmpty(),
                        emails = addresses,
                        contactId = id,
                        accountName = accounts[id],
                        hasPhone = false,
                    )
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

    /**
     * The newest call per number, keyed by its last [MATCH_DIGITS] digits.
     *
     * This is where "last contacted" comes from. The provider's own
     * LAST_TIME_CONTACTED has been frozen at 0 since Android 10, which left the
     * Recents filter matching nobody. Empty without READ_CALL_LOG.
     */
    private fun lastCallByNumber(): Map<String, Long> {
        val out = HashMap<String, Long>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                if (numberIdx < 0 || dateIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val tail = cursor.getString(numberIdx).orEmpty()
                        .filter(Char::isDigit)
                        .takeLast(MATCH_DIGITS)
                    // Newest first, so the first date seen per number is its latest.
                    if (tail.isNotEmpty()) out.putIfAbsent(tail, cursor.getLong(dateIdx))
                }
            }
        }
        return out
    }

    fun getContacts(): List<ContactRecord> {
        val groups = groupsByContactId()
        val emails = emailsByContactId()
        val accounts = accountByContactId()
        val lastCalls = lastCallByNumber()

        // Keyed by display name to collapse multiple numbers of the same contact.
        val byName = LinkedHashMap<String, ContactRecord>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.Contacts.STARRED,
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

            while (cursor.moveToNext()) {
                val name = cursor.getString(nameIdx)?.trim().orEmpty()
                if (name.isEmpty()) continue
                val number = cursor.getString(numberIdx)?.trim().orEmpty()
                val lastCall = lastCalls[number.filter(Char::isDigit).takeLast(MATCH_DIGITS)] ?: 0L
                val contactId = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                // A contact's second or third number still counts towards when
                // they were last called, even though the row shows the first.
                // Two separate contacts under one name collapse into this row
                // too, so the second one's addresses have to come along or
                // searching for them finds nothing.
                byName[name]?.let { existing ->
                    val moreEmails = emails[contactId].orEmpty()
                        .filter { address -> existing.emails.none { it.equals(address, ignoreCase = true) } }
                    if (lastCall > existing.lastContacted || moreEmails.isNotEmpty()) {
                        byName[name] = existing.copy(
                            lastContacted = maxOf(lastCall, existing.lastContacted),
                            emails = existing.emails + moreEmails,
                        )
                    }
                    continue
                }
                byName[name] = ContactRecord(
                    name = name,
                    detail = number,
                    initials = CallActionHandler.initials(name, number),
                    photoUri = if (photoIdx >= 0) cursor.getString(photoIdx) else null,
                    starred = starredIdx >= 0 && cursor.getInt(starredIdx) == 1,
                    lastContacted = lastCall,
                    groups = groups[contactId].orEmpty(),
                    emails = emails[contactId].orEmpty(),
                    contactId = contactId,
                    accountName = accounts[contactId],
                )
            }
        }
        return byName.values.toList()
    }

    /**
     * The user's groups, by group row id, with the names people know them by.
     *
     * Automatic groups are left out. Google files every synced contact under
     * "My Contacts" (AUTO_ADD, system id "Contacts"), so counting it made the
     * Groups tab list nearly the whole address book. Google's other system
     * groups — Friends, Family, Coworkers — are real groups the user fills by
     * hand, but the provider titles them "System Group: Family"; the prefix is
     * dropped so they read like any other.
     */
    private fun userGroupTitles(): Map<Long, String> {
        val out = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Groups.CONTENT_URI,
                arrayOf(
                    ContactsContract.Groups._ID,
                    ContactsContract.Groups.TITLE,
                    ContactsContract.Groups.SYSTEM_ID,
                    ContactsContract.Groups.AUTO_ADD,
                    ContactsContract.Groups.FAVORITES,
                ),
                "${ContactsContract.Groups.DELETED} = 0",
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Groups._ID)
                val titleIdx = cursor.getColumnIndex(ContactsContract.Groups.TITLE)
                val systemIdx = cursor.getColumnIndex(ContactsContract.Groups.SYSTEM_ID)
                val autoIdx = cursor.getColumnIndex(ContactsContract.Groups.AUTO_ADD)
                val favIdx = cursor.getColumnIndex(ContactsContract.Groups.FAVORITES)
                if (idIdx < 0 || titleIdx < 0) return@use
                while (cursor.moveToNext()) {
                    if (autoIdx >= 0 && cursor.getInt(autoIdx) == 1) continue
                    // Google's "Starred in Android" mirrors the starred flag, so
                    // it is the Favourites tab again under another name. It has
                    // no system id; the provider marks it FAVORITES instead.
                    if (favIdx >= 0 && cursor.getInt(favIdx) == 1) continue
                    val systemId = if (systemIdx >= 0) cursor.getString(systemIdx) else null
                    if (systemId == SYSTEM_GROUP_ALL) continue
                    val rawTitle = cursor.getString(titleIdx)?.trim().orEmpty()
                    if (rawTitle == STARRED_GROUP_TITLE) continue
                    val title = rawTitle
                        .removePrefix(SYSTEM_GROUP_PREFIX)
                        .trim()
                        .ifEmpty { systemId.orEmpty() }
                    if (title.isNotEmpty()) out[cursor.getLong(idIdx)] = title
                }
            }
        }
        return out
    }

    /**
     * Which of the user's groups each contact is in, by contact id.
     *
     * Groups are named per account, so "Family" in two Google accounts is two
     * rows in the provider. Keyed by title, they become one group here, which is
     * how the user thinks of them.
     */
    private fun groupsByContactId(): Map<Long, Set<String>> {
        val titles = userGroupTitles()
        if (titles.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableSet<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data.CONTACT_ID,
                    ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID,
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val groupIdx = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID
                )
                if (idIdx < 0 || groupIdx < 0) return@use
                while (cursor.moveToNext()) {
                    val title = titles[cursor.getLong(groupIdx)] ?: continue
                    out.getOrPut(cursor.getLong(idIdx)) { HashSet() }.add(title)
                }
            }
        }
        return out
    }

    private companion object {
        /** Compare on the last N digits, the rule the whole app matches numbers by. */
        const val MATCH_DIGITS = 10

        /** Google's "My Contacts": every synced contact, so not a group anyone made. */
        const val SYSTEM_GROUP_ALL = "Contacts"

        /** How Google titles its built-in groups in the provider. */
        const val SYSTEM_GROUP_PREFIX = "System Group:"

        /** Title of Google's starred mirror, for providers that do not flag it FAVORITES. */
        const val STARRED_GROUP_TITLE = "Starred in Android"
    }
}
