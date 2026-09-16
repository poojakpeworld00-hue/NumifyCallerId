package com.callerid.numberlookup.home.feature.contacts

import com.callerid.numberlookup.home.repository.ContactRecord

/** Top filter tabs for the contacts list. */
enum class ContactFilter { ALL, FAVORITES, RECENTS, GROUPS }

/** A row in the contacts list: an alphabetical section letter or a contact. */
sealed interface ContactRowUi {
    data class Header(val letter: String) : ContactRowUi
    data class Item(val contact: ContactRecord) : ContactRowUi
}
