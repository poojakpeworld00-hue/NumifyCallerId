package com.callerid.numberlookup.home.feature.contacts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.callerid.numberlookup.home.repository.ContactAccount
import com.callerid.numberlookup.home.repository.ContactGroup
import com.callerid.numberlookup.home.repository.ContactRecord
import com.callerid.numberlookup.home.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class ContactListViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ContactRepository(app)
    private var allContacts: List<ContactRecord> = emptyList()

    /** Group members with no phone number: Groups tab only, never the main list. */
    private var phonelessGroupMembers: List<ContactRecord> = emptyList()

    /**
     * Whether the phone has any group with someone in it, across every account.
     * The Groups chip shows only then: a tab that can only ever say "No groups
     * yet" is one tap wasted. Across all accounts, not the selected one, so the
     * chip does not blink in and out as the account filter changes; an account
     * without groups gets the tab's empty state instead.
     */
    private val _hasGroups = MutableLiveData(false)
    val hasGroups: LiveData<Boolean> = _hasGroups
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
            withContext(Dispatchers.IO) {
                allContacts = repository.getContacts()
                phonelessGroupMembers = repository.phonelessGroupMembers()
            }
            val anyGroup = allContacts.any { it.groups.isNotEmpty() } ||
                phonelessGroupMembers.isNotEmpty()
            _hasGroups.value = anyGroup
            // The chip is going away under the user: leave its tab for All
            // rather than strand them on a tab they can no longer see.
            if (!anyGroup && _filter.value == ContactFilter.GROUPS) {
                _filter.value = ContactFilter.ALL
                _openGroup.value = null
            }
            refreshFavorites()
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
        // The strip is scoped to the account too. It used to be built once in
        // load() and never again, so switching account rebuilt the list
        // underneath a row of favourites belonging to the account you had just
        // switched away from.
        refreshFavorites()
    }

    fun applyQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    /**
     * The group opened on the Groups tab, or null while its list of groups shows.
     * The fragment watches it to decide whether Back closes the group.
     */
    private val _openGroup = MutableLiveData<String?>(null)
    val openGroup: LiveData<String?> = _openGroup

    fun applyFilter(filter: ContactFilter) {
        // Tapping Groups again while inside one goes back to the list of groups.
        if (_filter.value == filter && _openGroup.value == null) return
        _filter.value = filter
        _openGroup.value = null
        rebuild()
    }

    fun openGroup(title: String) {
        _openGroup.value = title
        rebuild()
    }

    fun closeGroup() {
        if (_openGroup.value == null) return
        _openGroup.value = null
        rebuild()
    }

    /**
     * Whether [contact] belongs to the account now selected in the header.
     *
     * One definition, used by both the list and the favourites strip. Having it
     * written out twice is what let the two disagree.
     */
    private fun inSelectedAccount(contact: ContactRecord): Boolean {
        val selected = _account.value ?: return true
        return (contact.accountName ?: UNKNOWN_ACCOUNT) == selected
    }

    /** Starred contacts within the selected account, in load order. */
    private fun refreshFavorites() {
        _favorites.value = allContacts.filter { it.starred && inSelectedAccount(it) }
    }

    private fun rebuild() {
        val tab = _filter.value ?: ContactFilter.ALL
        val needle = query.lowercase(Locale.getDefault())

        // Both passes preserve the incoming name order, so alpha grouping and
        // fast-scroll stay valid downstream.
        if (tab == ContactFilter.GROUPS) {
            _rows.value = groupRows(needle)
            return
        }

        val visible = allContacts
            .filter { inSelectedAccount(it) }
            .filter { tab.accepts(it) }
            .filter { it.matches(needle) }

        // Recents reads newest first, like a call log; letter headers over a
        // list that is not alphabetical would only mislabel it.
        _rows.value = if (tab == ContactFilter.RECENTS) {
            visible.sortedByDescending { it.lastContacted }.map { ContactRowUi.Item(it) }
        } else {
            withInitialHeaders(visible)
        }
    }

    private companion object {
        /** Label for contacts the provider reports no account for (device-local). */
        const val UNKNOWN_ACCOUNT = "Device"
    }

    private fun ContactFilter.accepts(contact: ContactRecord): Boolean = when (this) {
        ContactFilter.ALL -> true
        ContactFilter.FAVORITES -> contact.starred
        ContactFilter.RECENTS -> contact.lastContacted > 0L
        ContactFilter.GROUPS -> contact.groups.isNotEmpty()
    }

    /**
     * The Groups tab: its groups with a count each, or the open group's members.
     *
     * Counted over the members opening it produces — this account's, with or
     * without a phone number — so the count and the list agree. A group with
     * nobody in it is left off rather than shown as "0 contacts".
     */
    private fun groupRows(needle: String): List<ContactRowUi> {
        // Phoneless members count too: a group of people saved by name or email
        // is still a group. Re-sorted, since the two lists arrive separately and
        // the members' letter headers need one name order.
        val pool = (allContacts + phonelessGroupMembers)
            .filter { inSelectedAccount(it) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
        val groups = pool
            .flatMap { it.groups }
            .groupingBy { it }
            .eachCount()
            .map { (title, count) -> ContactGroup(title, count) }
            .sortedBy { it.title.lowercase(Locale.getDefault()) }

        val open = _openGroup.value?.let { title -> groups.firstOrNull { it.title == title } }
        if (open == null) {
            // Gone since it was opened (emptied, or the account switched away
            // from it): fall back to the list rather than an empty page.
            if (_openGroup.value != null) _openGroup.value = null
            return groups.map { ContactRowUi.Group(it) }
        }
        val members = pool.filter { open.title in it.groups && it.matches(needle) }
        return listOf(ContactRowUi.GroupHead(open)) + withInitialHeaders(members)
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
