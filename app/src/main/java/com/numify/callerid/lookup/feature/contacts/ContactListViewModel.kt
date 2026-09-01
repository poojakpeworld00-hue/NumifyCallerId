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

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    fun setFilter(filter: ContactFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    private fun rebuild() {
        // Tab filter (keeps name order so alpha grouping / fast-scroll stay valid).
        val byTab = when (_filter.value ?: ContactFilter.ALL) {
            ContactFilter.ALL -> allContacts
            ContactFilter.FAVORITES -> allContacts.filter { it.starred }
            ContactFilter.RECENTS -> allContacts.filter { it.lastContacted > 0L }
            ContactFilter.GROUPS -> allContacts.filter { it.inGroup }
        }

        val filtered = if (query.isEmpty()) {
            byTab
        } else {
            val q = query.lowercase(Locale.getDefault())
            byTab.filter {
                it.name.lowercase(Locale.getDefault()).contains(q) || it.detail.contains(q)
            }
        }
        _rows.value = group(filtered)
    }

    private fun group(contacts: List<ContactRecord>): List<ContactRowUi> {
        if (contacts.isEmpty()) return emptyList()
        val rows = mutableListOf<ContactRowUi>()
        var lastLetter = ""
        for (contact in contacts) {
            val first = contact.name.firstOrNull()?.uppercaseChar()
            val letter = if (first != null && first.isLetter()) first.toString() else "#"
            if (letter != lastLetter) {
                rows.add(ContactRowUi.Header(letter))
                lastLetter = letter
            }
            rows.add(ContactRowUi.Item(contact))
        }
        return rows
    }
}
