package com.callerid.numberlookup.home.feature.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.callerid.numberlookup.home.repository.ContactAccount
import com.callerid.numberlookup.home.repository.ContactRecord
import com.callerid.numberlookup.home.repository.ContactRepository
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

    /** Starred contacts, for the strip above the list. Deliberately left unfiltered
     *  by the search query: the strip is a shortcut to the people you always call,
     *  and emptying it as the user types would only make the list jump. */
    private val _favorites = MutableLiveData<List<ContactRecord>>(emptyList())
    val favorites: LiveData<List<ContactRecord>> = _favorites

    /**
     * The stores the address book is spread across, "All contacts" first, each
     * with the number of contacts it holds. Derived from the loaded pool rather
     * than queried separately, so a count can never promise more rows than
     * selecting it produces.
     */
    private val _accounts = MutableLiveData<List<ContactAccount>>(emptyList())
    val accounts: LiveData<List<ContactAccount>> = _accounts

    /** The selected account, or null for all of them. */
    private val _account = MutableLiveData<String?>(null)
    val account: LiveData<String?> = _account

    fun load() {
        viewModelScope.launch {
            allContacts = withContext(Dispatchers.IO) { repository.getContacts() }
            _favorites.value = allContacts.filter { it.starred }
            _accounts.value = buildAccounts()
            rebuild()
        }
    }

    /**
     * Accounts in descending size. A phone typically has one account holding
     * nearly everything and a tail of near-empty ones (a SIM, a stale sign-in),
     * and the picker is a list someone scans for their own address — the big one
     * should not be third.
     */
    private fun buildAccounts(): List<ContactAccount> {
        val byAccount = allContacts
            .groupingBy { it.accountName ?: UNKNOWN_ACCOUNT }
            .eachCount()
            .map { (name, count) -> ContactAccount(name, count) }
            .sortedByDescending { it.count }
        return listOf(ContactAccount(null, allContacts.size)) + byAccount
    }

    fun applyAccount(name: String?) {
        if (_account.value == name) return
        _account.value = name
        rebuild()
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
        val selected = _account.value
        val visible = allContacts
            .filter { selected == null || (it.accountName ?: UNKNOWN_ACCOUNT) == selected }
            .filter { tab.accepts(it) }
            .filter { it.matches(needle) }

        _rows.value = withInitialHeaders(visible)
    }

    private companion object {
        /** Label for contacts the provider reports no account for (device-local). */
        const val UNKNOWN_ACCOUNT = "Device"
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
