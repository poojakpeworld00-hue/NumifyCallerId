package com.contacts.callerid.number.lookup.feature.dialer

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.contacts.callerid.number.lookup.repository.CallLogRepository
import com.contacts.callerid.number.lookup.repository.ContactRepository
import com.contacts.callerid.number.lookup.repository.FavoriteNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Backs the dialer: the searchable pool of numbers, and the filter over it. */
class DialerViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogRepository(app)
    private val contacts = ContactRepository(app)

    /**
     * One searchable number, with everything the filter needs already computed.
     *
     * The keys are precomputed because the filter runs on **every keystroke** over
     * the whole pool. Stripping non-digits inside the predicate allocated a fresh
     * String per entry per keypress — on a phone with five thousand saved numbers
     * that is five thousand allocations for one digit, which is what made typing
     * lag and kept the collector busy.
     */
    private class Entry(
        val item: FavoriteNumber,
        val digits: String,
        val nameLower: String?,
    )

    private var all: List<Entry> = emptyList()
    private var query = ""

    private val _frequent = MutableLiveData<List<FavoriteNumber>>(emptyList())
    val frequent: LiveData<List<FavoriteNumber>> = _frequent

    /** Digits of every saved contact, so asking whether a number is saved costs no query. */
    private var savedDigits: Set<String> = emptySet()

    private var loadJob: Job? = null

    /**
     * Whether [number] belongs to a saved contact.
     *
     * Answered from the pool rather than a PhoneLookup. The dialer asked this on
     * every keystroke to decide whether to offer "Add to contacts", and a
     * content-provider round trip per keypress is not something to do on a screen
     * whose whole job is to respond to keypresses.
     */
    fun isSavedContact(number: String): Boolean {
        val key = number.digitsKey()
        return key.isNotEmpty() && key in savedDigits
    }

    /**
     * Rebuilds the searchable pool from the call log **and** the address book.
     *
     * Contacts are in here because the pool used to be the call log alone: a
     * number you had saved but never called did not exist as far as the dialer
     * was concerned, so adding a contact and searching for it found nothing.
     *
     * Order matters. Call-log entries come first, busiest first, so the people
     * you actually ring stay at the top of an empty-query list; saved numbers
     * follow, and any already present from the call log are skipped so a contact
     * you call often does not appear twice.
     *
     * One load at a time: a second call while one is in flight is dropped rather
     * than queued, so flicking between tabs cannot stack reads of two content
     * providers on top of each other.
     *
     * Safe without READ_CONTACTS or READ_CALL_LOG: each side is read inside
     * runCatching / a null-checked cursor and contributes nothing when denied.
     */
    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val called = repository.getMostUsed(limit = 200)
                val seen = called.mapTo(HashSet()) { it.number.digitsKey() }
                val phones = contacts.phoneEntries()
                val savedKeys = HashSet<String>(phones.size)
                phones.forEach { phone ->
                    phone.number.digitsKey().takeIf(String::isNotEmpty)?.let { savedKeys.add(it) }
                }
                val fromContacts = phones
                    .filter { seen.add(it.number.digitsKey()) }
                    // count = 0: never called, so it sorts below everything in
                    // the call log and carries no false "frequently used" weight.
                    .map { FavoriteNumber(name = it.name, number = it.number, count = 0) }
                Loaded((called + fromContacts).map { it.toEntry() }, savedKeys)
            }
            all = loaded.entries
            savedDigits = loaded.savedDigits
            applyFilter()
        }
    }

    private class Loaded(val entries: List<Entry>, val savedDigits: Set<String>)

    fun filter(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        applyFilter()
    }

    /**
     * Narrows the pool to [MATCH_LIMIT] rows.
     *
     * The cap is the point. A one-digit query matches most of the address book,
     * and the adapter behind this is a plain notifyDataSetChanged — so without it
     * the first keypress built and bound a list thousands of rows long that
     * nobody could ever scroll to the end of.
     */
    private fun applyFilter() {
        if (query.isEmpty()) {
            // Only numbers actually called: a plain address book dumped under an
            // untouched keypad is a contact list, not a shortlist.
            _frequent.value = all.asSequence()
                .filter { it.item.count > 0 }
                .take(TOP_LIMIT)
                .map { it.item }
                .toList()
            return
        }
        val digits = query.filter(Char::isDigit)
        val needle = query.lowercase(Locale.getDefault())
        _frequent.value = all.asSequence()
            .filter { entry ->
                (digits.isNotEmpty() && entry.digits.contains(digits)) ||
                    entry.nameLower?.contains(needle) == true
            }
            .take(MATCH_LIMIT)
            .map { it.item }
            .toList()
    }

    private fun FavoriteNumber.toEntry() = Entry(
        item = this,
        digits = number.filter(Char::isDigit),
        nameLower = name?.takeIf { it.isNotBlank() }?.lowercase(Locale.getDefault()),
    )

    /** Last ten digits — the key both sides of the merge agree on. */
    private fun String.digitsKey(): String = filter(Char::isDigit).takeLast(MATCH_DIGITS)

    /**
     * Reloads when the call log or the address book actually changes.
     *
     * The fragment used to reload on every resume and every tab switch, so
     * walking along the bar re-read two content providers each time — thousands
     * of rows, for data that had not moved. This fires only when something did,
     * which is also what keeps "add a contact, come back, search for it" working
     * without paying for it on every other visit.
     */
    private val dataObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            load()
        }
    }

    init {
        runCatching {
            val resolver = app.contentResolver
            resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, dataObserver)
            resolver.registerContentObserver(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, true, dataObserver
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching {
            getApplication<Application>().contentResolver.unregisterContentObserver(dataObserver)
        }
    }

    private companion object {
        const val TOP_LIMIT = 20

        /** Matches ContactRepository.MATCH_DIGITS — same rule, same answers. */
        const val MATCH_DIGITS = 10

        /** Most matches worth drawing; see [applyFilter]. */
        const val MATCH_LIMIT = 50
    }
}
