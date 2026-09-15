package com.contacts.callerid.number.lookup.feature.calldetails

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.repository.CallRecord
import com.contacts.callerid.number.lookup.repository.CallLogRepository
import com.contacts.callerid.number.lookup.repository.CallType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Aggregated data for one number, shown on [CallDetailsActivity]. */
data class CallInsightUi(
    val name: String,
    val number: String,
    val verified: Boolean,
    val totalDuration: String,
    val totalCalls: String,
    val callsSubtitle: String,
    val history: List<CallRecord>
)

class CallDetailsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogRepository(app)

    private val _ui = MutableLiveData<CallInsightUi>()
    val ui: LiveData<CallInsightUi> = _ui

    fun load(number: String, fallbackName: String?) {
        viewModelScope.launch {
            val calls = withContext(Dispatchers.IO) { repository.getCalls(limit = 1000) }
            val target = normalize(number)
            val mine = calls
                .filter { normalize(it.number) == target }
                .sortedByDescending { it.date }

            val name = mine.firstOrNull { !it.name.isNullOrBlank() }?.name
                ?: fallbackName?.takeIf { it.isNotBlank() }
                ?: number
            val verified = mine.any { !it.name.isNullOrBlank() } || !fallbackName.isNullOrBlank()

            // Both stat cards are scoped to the last 30 days (matches the subtitle).
            val recent = lastThirtyDays(mine)

            _ui.value = CallInsightUi(
                name = name,
                number = number,
                verified = verified,
                totalDuration = formatDuration(recent.sumOf { it.durationSec }),
                totalCalls = recent.size.toString(),
                callsSubtitle = callsSubtitle(recent),
                history = mine
            )
        }
    }

    /** Calls from this number within the trailing 30-day window. */
    private fun lastThirtyDays(calls: List<CallRecord>): List<CallRecord> {
        val cutoff = System.currentTimeMillis() - THIRTY_DAYS_MS
        return calls.filter { it.date >= cutoff }
    }

    /** Describes the composition of [calls] for the Total Calls subtitle. */
    private fun callsSubtitle(calls: List<CallRecord>): String {
        val app = getApplication<Application>()
        if (calls.isEmpty()) return app.getString(R.string.detail_calls_none)

        val types = calls.mapTo(HashSet()) { it.type }
        val onlyMissed = types.all { it == CallType.MISSED || it == CallType.SPAM }
        return when {
            types == setOf(CallType.OUTGOING) -> app.getString(R.string.detail_calls_outgoing)
            types == setOf(CallType.INCOMING) -> app.getString(R.string.detail_calls_incoming)
            onlyMissed -> app.getString(R.string.detail_calls_missed)
            else -> app.getString(R.string.detail_calls_mixed)
        }
    }

    /**
     * Short enough for a third of the hero's width. The zero case reads "None"
     * rather than "No talk time" — the chip is already captioned TALK TIME, so
     * the long form both repeated the caption and ellipsized to "No talk …".
     */
    private fun formatDuration(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> getApplication<Application>().getString(R.string.detail_no_talk_short)
        }
    }

    private fun normalize(number: String): String =
        number.filter { it.isDigit() }.ifEmpty { number.trim() }

    private companion object {
        const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
