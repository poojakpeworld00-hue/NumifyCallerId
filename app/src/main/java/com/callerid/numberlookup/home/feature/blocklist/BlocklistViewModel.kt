package com.callerid.numberlookup.home.feature.blocklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.BlockedNumber
import com.callerid.numberlookup.home.repository.BlocklistRepository
import com.callerid.numberlookup.home.repository.CallLogRepository
import com.callerid.numberlookup.home.repository.ContactRepository
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlocklistViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = BlocklistRepository(app)
    private val contacts = ContactRepository(app)
    private val callLog = CallLogRepository(app)

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

    /**
     * Rebuilds the rows, including each number's attempts since it was blocked.
     *
     * Off the main thread: the count comes from the call-log content provider,
     * which is not a main-thread read, and the blocklist can hold enough entries
     * that doing it per row would stutter the list.
     */
    private fun refresh() {
        val entries = manager.getEntries()
        _count.value = entries.size

        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                // One pass over the log for the whole list rather than one query
                // per blocked number.
                val calls = runCatching { callLog.getCalls(CALL_LOG_WINDOW) }
                    .getOrDefault(emptyList())
                    .groupBy { it.number.filter(Char::isDigit).takeLast(DIGIT_MATCH) }

                entries.map { entry ->
                    val tail = entry.number.filter(Char::isDigit).takeLast(DIGIT_MATCH)
                    val history = calls[tail].orEmpty()
                        .sortedByDescending { it.date }
                        .map {
                            BlockedCall(
                                at = it.date,
                                type = it.type,
                                blockedAttempt = it.date >= entry.addedAt,
                            )
                        }
                    entry.toRow(history)
                }
            }
            _rows.value = rows
        }
    }

    /**
     * A number the user blocked by hand carries no spam classification, so it
     * renders with the neutral treatment. The label is the contact name wherever
     * one can be resolved, and a friendly fallback otherwise.
     */
    private fun BlockedNumber.toRow(history: List<BlockedCall>): BlockedNumberState {
        // The number itself is the fallback title, not "Unknown caller": the row's
        // subtitle now carries when it was blocked, so a placeholder on top would
        // leave the entry showing nothing that identifies it.
        val name = contacts.lookupNameByNumber(number)?.takeIf { it.isNotBlank() }
        return BlockedNumberState(
            entry = this,
            label = name ?: number,
            isSpam = false,
            history = history,
        )
    }

    private companion object {
        /** Matched on the last N digits, the rule the whole app compares by. */
        const val DIGIT_MATCH = 10

        /**
         * Rows to read from the call log. Deep enough to cover the attempts on a
         * long-standing block, bounded so the read stays cheap.
         */
        const val CALL_LOG_WINDOW = 2000
    }
}
