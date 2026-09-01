package com.callora.callerid.numberlookup.screen.phonebook

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.callora.callerid.numberlookup.store.PhonebookEntry
import com.callora.callerid.numberlookup.store.PhonebookSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PhonebookViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PhonebookSource(app)
    private var allContacts: List<PhonebookEntry> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<ContactRowUi>>(emptyList())
    val rows: LiveData<List<ContactRowUi>> = _rows

    private val _filter = MutableLiveData(ContactScope.ALL)
    val filter: LiveData<ContactScope> = _filter

    /** Starred contacts, for the strip above the list. Deliberately NOT filtered
     *  by the search query: the strip is a shortcut to people you always call,
     *  and emptying it while typing would just make the list jump. */
    private val _favorites = MutableLiveData<List<PhonebookEntry>>(emptyList())
    val favorites: LiveData<List<PhonebookEntry>> = _favorites

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

    fun setFilter(filter: ContactScope) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    private fun rebuild() {
        // Tab filter (keeps name order so alpha grouping / fast-scroll stay valid).
        val byTab = when (_filter.value ?: ContactScope.ALL) {
            ContactScope.ALL -> allContacts
            ContactScope.FAVORITES -> allContacts.filter { it.starred }
            ContactScope.RECENTS -> allContacts.filter { it.lastContacted > 0L }
            ContactScope.GROUPS -> allContacts.filter { it.inGroup }
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

    private fun group(contacts: List<PhonebookEntry>): List<ContactRowUi> {
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
