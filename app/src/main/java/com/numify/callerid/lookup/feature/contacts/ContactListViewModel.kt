package com.numify.callerid.lookup.feature.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.numify.callerid.lookup.repository.ContactRecord
import com.numify.callerid.lookup.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ContactListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ContactRepository(app)
    private var allContacts: List<ContactRecord> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<ContactRowUi>>(emptyList())
    val rows: LiveData<List<ContactRowUi>> = _rows

    private val _filter = MutableLiveData(ContactFilter.ALL)
    val filter: LiveData<ContactFilter> = _filter

    /** Starred contacts, for the strip above the list. Deliberately NOT filtered
     *  by the search query: the strip is a shortcut to people you always call,
     *  and emptying it while typing would just make the list jump. */
    private val _favorites = MutableLiveData<List<ContactRecord>>(emptyList())
    val favorites: LiveData<List<ContactRecord>> = _favorites

    fun load() {
        viewModelScope.launch {
            allContacts = withContext(Dispatchers.IO) { repository.getContacts() }
            _favorites.value = allContacts.filter { it.starred }
            rebuild()
        }
    }

    fun applyQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    fun applyFilter(filter: ContactFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    private fun rebuild() {
        val tab = _filter.value ?: ContactFilter.ALL
        val needle = query.lowercase(Locale.getDefault())

        // Both passes preserve the incoming name order, so alpha grouping and
        // fast-scroll stay valid downstream.
        val visible = allContacts
            .filter { tab.accepts(it) }
            .filter { it.matches(needle) }

        _rows.value = withInitialHeaders(visible)
    }

    private fun ContactFilter.accepts(contact: ContactRecord): Boolean = when (this) {
        ContactFilter.ALL -> true
        ContactFilter.FAVORITES -> contact.starred
        ContactFilter.RECENTS -> contact.lastContacted > 0L
        ContactFilter.GROUPS -> contact.inGroup
    }

    private fun ContactRecord.matches(needle: String): Boolean =
        needle.isEmpty() ||
            name.lowercase(Locale.getDefault()).contains(needle) ||
            detail.contains(needle)

    /** Non-letter names (numbers, symbols, emoji) all land under the "#" section. */
    private val ContactRecord.initial: String
        get() = name.firstOrNull()
            ?.uppercaseChar()
            ?.takeIf(Char::isLetter)
            ?.toString()
            ?: "#"

    /**
     * Injects an alphabet header each time the initial changes, so exactly one
     * header precedes each run of contacts sharing a letter.
     */
    private fun withInitialHeaders(contacts: List<ContactRecord>): List<ContactRowUi> =
        contacts.flatMapIndexed { index, contact ->
            val letter = contact.initial
            if (index == 0 || letter != contacts[index - 1].initial) {
                listOf(ContactRowUi.Header(letter), ContactRowUi.Item(contact))
            } else {
                listOf(ContactRowUi.Item(contact))
            }
        }
}
