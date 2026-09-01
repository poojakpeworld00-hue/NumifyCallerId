package com.numify.callerid.lookup.feature.blocklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.BlockedNumber
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.ContactRepository

class BlocklistViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BlocklistRepository(app)
    private val contacts = ContactRepository(app)

    private val _rows = MutableLiveData<List<BlockedNumberState>>(emptyList())
    val rows: LiveData<List<BlockedNumberState>> = _rows

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

    fun isNumberBlocked(number: String): Boolean = manager.isNumberBlocked(number)

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
    private fun BlockedNumber.toRow(): BlockedNumberState {
        val name = contacts.lookupNameByNumber(number)?.takeIf { it.isNotBlank() }
        return BlockedNumberState(
            entry = this,
            label = name ?: getApplication<Application>().getString(R.string.blocklist_unknown_caller),
            isSpam = false,
        )
    }
}
