package com.callerid.numberlookup.home.feature.contacts

import com.callerid.numberlookup.home.repository.ContactGroup
import com.callerid.numberlookup.home.repository.ContactRecord

/** Top filter tabs for the contacts list. */
enum class ContactFilter { ALL, FAVORITES, RECENTS, GROUPS }

/**
 * A row in the contacts list: an alphabetical section letter or a contact, or
 * on the Groups tab a group to open, and the heading of the one that is open.
 */
sealed interface ContactRowUi {
    data class Header(val letter: String) : ContactRowUi
    data class Item(val contact: ContactRecord) : ContactRowUi
    data class Group(val group: ContactGroup) : ContactRowUi
    data class GroupHead(val group: ContactGroup) : ContactRowUi
}
