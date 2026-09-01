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

    fun setFilter(filter: CallLogFilter) {
        if (_filter.value == filter) return
        _filter.value = filter
        rebuild()
    }

    fun setSort(sort: CallLogSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        rebuild()
    }

    fun setQuery(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rebuild()
    }

    private fun rebuild() {
        val active = _filter.value ?: CallLogFilter.ALL
        val order = _sort.value ?: CallLogSort.NEWEST
        val q = query.lowercase(Locale.getDefault())
        val filtered = allCalls.filter { call ->
            matches(active, call.type) && (q.isEmpty() ||
                call.name?.lowercase(Locale.getDefault())?.contains(q) == true ||
                call.number.lowercase(Locale.getDefault()).contains(q))
        }
        _rows.value = when (order) {
            // Date sorts keep the Today/Yesterday/… section headers.
            CallLogSort.NEWEST -> group(filtered.sortedByDescending { it.date })
            CallLogSort.OLDEST -> group(filtered.sortedBy { it.date })
            // Name sorts flatten the list — date headers no longer apply.
            CallLogSort.NAME_ASC ->
                filtered.sortedBy { sortName(it) }.map { HistoryRowUi.Call(it) }
            CallLogSort.NAME_DESC ->
                filtered.sortedByDescending { sortName(it) }.map { HistoryRowUi.Call(it) }
        }
    }

    /** Key used for name sorting: caller name when present, otherwise the number. */
    private fun sortName(call: CallRecord): String =
        (call.name?.takeIf { it.isNotBlank() } ?: call.number).lowercase(Locale.getDefault())

    private fun matches(filter: CallLogFilter, type: CallType): Boolean = when (filter) {
        CallLogFilter.ALL -> true
        CallLogFilter.INCOMING -> type == CallType.INCOMING
        CallLogFilter.OUTGOING -> type == CallType.OUTGOING
        CallLogFilter.MISSED -> type == CallType.MISSED
    }

    private fun group(calls: List<CallRecord>): List<HistoryRowUi> {
        if (calls.isEmpty()) return emptyList()

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = cal.timeInMillis
        val yesterdayStart = todayStart - DAY_MS
        val weekStart = todayStart - 6 * DAY_MS

        val rows = mutableListOf<HistoryRowUi>()
        var lastBucket = -1
        for (call in calls) {
            val bucket = when {
                call.date >= todayStart -> 0
                call.date >= yesterdayStart -> 1
                call.date >= weekStart -> 2
                else -> 3
            }
            if (bucket != lastBucket) {
                rows.add(HistoryRowUi.Header(bucketTitle(bucket)))
                lastBucket = bucket
            }
            rows.add(HistoryRowUi.Call(call))
        }
        return rows
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
