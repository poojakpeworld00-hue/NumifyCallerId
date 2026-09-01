package com.numify.callerid.numberlookup.screen.phonebook

import com.numify.callerid.numberlookup.store.PhonebookEntry

/** Top filter tabs for the contacts list. */
enum class ContactScope { ALL, FAVORITES, RECENTS, GROUPS }

/** A row in the contacts list: an alphabetical section letter or a contact. */
sealed interface ContactRowUi {
    data class Header(val letter: String) : ContactRowUi
    data class Item(val contact: PhonebookEntry) : ContactRowUi
}
