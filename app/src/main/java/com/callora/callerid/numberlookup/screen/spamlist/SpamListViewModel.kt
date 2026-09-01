package com.callora.callerid.numberlookup.screen.spamlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.store.BlockedNumber
import com.callora.callerid.numberlookup.store.BlockRegistry
import com.callora.callerid.numberlookup.store.PhonebookSource

class SpamListViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BlockRegistry(app)
    private val contacts = PhonebookSource(app)

    private val _rows = MutableLiveData<List<BlockedRowState>>(emptyList())
    val rows: LiveData<List<BlockedRowState>> = _rows

    private val _count = MutableLiveData(0)
    val count: LiveData<Int> = _count

    init {
        refresh()
    }

    fun add(number: String) {
        manager.add(number)
        refresh()
    }

    fun remove(number: String) {
        manager.remove(number)
        refresh()
    }

    fun isBlocked(number: String): Boolean = manager.isBlocked(number)

    private fun refresh() {
        val entries = manager.getEntries()
        _count.value = entries.size
        _rows.value = entries.map { it.toRow() }
    }

    /**
     * A blocked number the user added has no spam classification, so it renders
     * with the neutral treatment; the label is the contact name when we can
     * resolve one, otherwise a friendly fallback.
     */
    private fun BlockedNumber.toRow(): BlockedRowState {
        val name = contacts.lookupNameByNumber(number)?.takeIf { it.isNotBlank() }
        return BlockedRowState(
            entry = this,
            label = name ?: getApplication<Application>().getString(R.string.blocklist_unknown_caller),
            isSpam = false,
        )
    }
}
