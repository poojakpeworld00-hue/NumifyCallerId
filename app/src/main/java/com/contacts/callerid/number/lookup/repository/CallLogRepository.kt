package com.contacts.callerid.number.lookup.repository

import android.content.Context
import android.provider.CallLog

/** A single entry from the system call log. */
data class CallRecord(
    val name: String?,
    val number: String,
    val type: CallType,
    val date: Long,
    val durationSec: Long
)

/** A phone number aggregated by how often it appears in the call log. */
data class FavoriteNumber(
    val name: String?,
    val number: String,
    val count: Int
)

/** Whole-log counts, independent of the row window [CallLogRepository.getCalls] returns. */
data class CallLogTotals(val total: Int, val missed: Int)

/** Reads the device call log via the [CallLog.Calls] content provider. */
class CallLogRepository(private val context: Context) {

    /**
     * Accurate counts for the entire call log.
     *
     * [getCalls] intentionally stops at a row cap, so counting its result reports
     * the cap instead of the log, and any device holding more calls than the cap
     * always reads back exactly the cap. These figures come from the provider
     * directly: the projection is a lone id column and nothing is materialised, so
     * the cost is the cursor's row count rather than one object per call.
     *
     * `missed` spans MISSED and REJECTED, mirroring the way [getCalls] folds both
     * onto [CallType.MISSED].
     */
    fun getTotals(): CallLogTotals = CallLogTotals(
        total = countWhere(null, null),
        missed = countWhere(
            "${CallLog.Calls.TYPE} IN (?, ?)",
            arrayOf(
                CallLog.Calls.MISSED_TYPE.toString(),
                CallLog.Calls.REJECTED_TYPE.toString(),
            ),
        ),
    )

    private fun countWhere(selection: String?, args: Array<String>?): Int =
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID),
                selection,
                args,
                null
            )?.use { it.count } ?: 0
        }.getOrDefault(0)

    fun getCalls(limit: Int = 500): List<CallRecord> {
        val result = mutableListOf<CallRecord>()
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION)

            while (cursor.moveToNext() && result.size < limit) {
                val type = when (cursor.getInt(typeIdx)) {
                    CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                    CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                    CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                    CallLog.Calls.REJECTED_TYPE -> CallType.MISSED
                    CallLog.Calls.BLOCKED_TYPE -> CallType.SPAM
                    else -> CallType.INCOMING
                }
                result.add(
                    CallRecord(
                        name = cursor.getString(nameIdx),
                        number = cursor.getString(numberIdx).orEmpty().ifBlank { "Unknown" },
                        type = type,
                        date = cursor.getLong(dateIdx),
                        durationSec = cursor.getLong(durationIdx)
                    )
                )
            }
        }
        return result
    }

    /**
     * Returns the most frequently called numbers, busiest first.
     * Caller must ensure READ_CALL_LOG is granted (otherwise the list is empty).
     */
    fun getMostUsed(limit: Int = 20): List<FavoriteNumber> =
        getCalls(limit = 1000)
            .filter { it.number.isNotBlank() && !it.number.equals("Unknown", ignoreCase = true) }
            .groupBy { it.number }
            .map { (number, entries) ->
                FavoriteNumber(
                    name = entries.firstOrNull { !it.name.isNullOrBlank() }?.name,
                    number = number,
                    count = entries.size
                )
            }
            .sortedByDescending { it.count }
            .take(limit)
}
