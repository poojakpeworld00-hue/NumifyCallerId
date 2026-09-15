package com.contacts.callerid.number.lookup.feature.contacts

import com.contacts.callerid.number.lookup.repository.ContactRecord

/** Top filter tabs for the contacts list. */
enum class ContactFilter { ALL, FAVORITES, RECENTS, GROUPS }

/** A row in the contacts list: an alphabetical section letter or a contact. */
sealed interface ContactRowUi {
    data class Header(val letter: String) : ContactRowUi
    data class Item(val contact: ContactRecord) : ContactRowUi
}
