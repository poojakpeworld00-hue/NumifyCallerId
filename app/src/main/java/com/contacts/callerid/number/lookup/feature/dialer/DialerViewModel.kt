package com.contacts.callerid.number.lookup.feature.dialer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.contacts.callerid.number.lookup.repository.CallLogRepository
import com.contacts.callerid.number.lookup.repository.ContactRepository
import com.contacts.callerid.number.lookup.repository.FavoriteNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs [DialerActivity]: loads the top-used numbers and filters them by the typed query. */
class DialerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogRepository(app)
    private val contacts = ContactRepository(app)

    /** Full pool searched while typing: called numbers first, then saved ones. */
    private val all = mutableListOf<FavoriteNumber>()
    private var query = ""

    private val _frequent = MutableLiveData<List<FavoriteNumber>>(emptyList())
    val frequent: LiveData<List<FavoriteNumber>> = _frequent

    /**
     * Rebuilds the searchable pool from the call log **and** the address book.
     *
     * Contacts are in here because the pool used to be the call log alone: a
     * number you had saved but never called did not exist as far as the dialer
     * was concerned, so adding a contact and searching for it found nothing.
     * There was no cache to refresh — the data simply was not a source.
     *
     * Order matters. Call-log entries come first, busiest first, so the people
     * you actually ring stay at the top of an empty-query list; saved numbers
     * follow, and any already present from the call log are skipped so a contact
     * you call often does not appear twice.
     *
     * Safe without READ_CONTACTS or READ_CALL_LOG: each side is read inside
     * runCatching / a null-checked cursor and contributes nothing when denied.
     */
    fun load() {
        viewModelScope.launch {
            val merged = withContext(Dispatchers.IO) {
                val called = repository.getMostUsed(limit = 200)
                val seen = called.mapTo(HashSet()) { it.number.digitsKey() }
                val saved = contacts.phoneEntries()
                    .filter { seen.add(it.number.digitsKey()) }
                    // count = 0: never called, so it sorts below everything in
                    // the call log and carries no false "frequently used" weight.
                    .map { FavoriteNumber(name = it.name, number = it.number, count = 0) }
                called + saved
            }
            all.clear()
            all.addAll(merged)
            applyFilter()
        }
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    private fun applyFilter() {
        // No query → show only the 20 most-used. Typing searches the whole pool.
        if (query.isEmpty()) {
            // Only numbers actually called: a plain address book dumped under an
            // untouched keypad is a contact list, not a shortlist.
            _frequent.value = all.filter { it.count > 0 }.take(TOP_LIMIT)
            return
        }
        val digits = query.filter { it.isDigit() }
        _frequent.value = all.filter { item ->
            (digits.isNotEmpty() && item.number.filter { it.isDigit() }.contains(digits)) ||
                item.number.contains(query) ||
                item.name?.contains(query, ignoreCase = true) == true
        }
    }

    /** Last ten digits — the key both sides of the merge agree on. */
    private fun String.digitsKey(): String = filter(Char::isDigit).takeLast(MATCH_DIGITS)

    private companion object {
        const val TOP_LIMIT = 20

        /** Matches ContactRepository.MATCH_DIGITS — same rule, same answers. */
        const val MATCH_DIGITS = 10
    }
}
