package com.numify.callerid.lookup.feature.calllog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallLogTotals
import com.numify.callerid.lookup.repository.CallType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

class CallLogViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogRepository(app)
    private var allCalls: List<CallRecord> = emptyList()
    private var query: String = ""

    private val _rows = MutableLiveData<List<HistoryRowUi>>(emptyList())
    val rows: LiveData<List<HistoryRowUi>> = _rows

    private val _filter = MutableLiveData(CallLogFilter.ALL)
    val filter: LiveData<CallLogFilter> = _filter

    private val _sort = MutableLiveData(CallLogSort.NEWEST)
    val sort: LiveData<CallLogSort> = _sort

    /**
     * Whole-log counts, for the header. [allCalls] is capped at a row window, so
     * counting it reports the cap once the device has more calls than that.
     */
    private val _totals = MutableLiveData(CallLogTotals(0, 0))
    val totals: LiveData<CallLogTotals> = _totals

    fun load() {
        viewModelScope.launch {
            val (calls, totals) = withContext(Dispatchers.IO) {
                repository.getCalls() to repository.getTotals()
            }
            allCalls = calls
            _totals.value = totals
            rebuild()
        }
    }

    fun applyFilter(filter: CallLogFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    fun applySort(sort: CallLogSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        rebuild()
    }

    fun applyQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    private fun rebuild() {
        val active = _filter.value ?: CallLogFilter.ALL
        val order = _sort.value ?: CallLogSort.NEWEST
        val needle = query.lowercase(Locale.getDefault())

        val visible = allCalls
            .filter { active.accepts(it.type) }
            .filter { it.matches(needle) }
            .sortedWith(order.comparator)

        // Date sorts keep the Today/Yesterday/… section headers; name sorts
        // flatten the list, where those headers no longer mean anything.
        _rows.value = if (order.isChronological) {
            withDateHeaders(visible)
        } else {
            visible.map { HistoryRowUi.Call(it) }
        }
    }

    /** Key used for name sorting: caller name when present, otherwise the number. */
    private val CallRecord.sortKey: String
        get() = (name?.takeIf { it.isNotBlank() } ?: number).lowercase(Locale.getDefault())

    private fun CallRecord.matches(needle: String): Boolean {
        if (needle.isEmpty()) return true
        val locale = Locale.getDefault()
        return name?.lowercase(locale)?.contains(needle) == true ||
            number.lowercase(locale).contains(needle)
    }

    private fun CallLogFilter.accepts(type: CallType): Boolean = when (this) {
        CallLogFilter.ALL -> true
        CallLogFilter.INCOMING -> type == CallType.INCOMING
        CallLogFilter.OUTGOING -> type == CallType.OUTGOING
        CallLogFilter.MISSED -> type == CallType.MISSED
    }

    /** Date-ordered sorts are the only ones the section headers make sense under. */
    private val CallLogSort.isChronological: Boolean
        get() = this == CallLogSort.NEWEST || this == CallLogSort.OLDEST

    private val CallLogSort.comparator: Comparator<CallRecord>
        get() = when (this) {
            CallLogSort.NEWEST -> compareByDescending { it.date }
            CallLogSort.OLDEST -> compareBy { it.date }
            CallLogSort.NAME_ASC -> compareBy { it.sortKey }
            CallLogSort.NAME_DESC -> compareByDescending { it.sortKey }
        }

    /**
     * Walks the already-ordered list and injects a header row each time the day
     * bucket changes, so a header is emitted exactly once per run of rows.
     */
    private fun withDateHeaders(calls: List<CallRecord>): List<HistoryRowUi> {
        if (calls.isEmpty()) return emptyList()

        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val bucketOf: (Long) -> Int = { at ->
            when {
                at >= midnight -> 0
                at >= midnight - DAY_MS -> 1
                at >= midnight - 6 * DAY_MS -> 2
                else -> 3
            }
        }

        return calls.flatMapIndexed { index, call ->
            val bucket = bucketOf(call.date)
            val opensSection = index == 0 || bucket != bucketOf(calls[index - 1].date)
            if (opensSection) {
                listOf(HistoryRowUi.Header(bucketTitle(bucket)), HistoryRowUi.Call(call))
            } else {
                listOf(HistoryRowUi.Call(call))
            }
        }
    }

    private fun bucketTitle(bucket: Int): Int = when (bucket) {
        0 -> R.string.recents_today
        1 -> R.string.recents_yesterday
        2 -> R.string.recents_this_week
        else -> R.string.recents_earlier
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
