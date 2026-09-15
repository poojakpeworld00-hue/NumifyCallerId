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

    /**
     * The newest [limit] calls, optionally only those of the given provider
     * [types].
     *
     * [types] exists because the cap and in-memory filtering do not mix. The
     * recents tabs used to take these rows once and filter them in the view
     * model, which quietly means "the incoming calls among the newest 500" —
     * not "the newest 500 incoming calls". On a log where one number dominates
     * the recent rows, a tab could show a handful of calls while the log held
     * plenty more, and the header count (taken from the whole log) disagreed
     * with the list underneath it. Asking the provider for the type lets each
     * tab fill its own window.
     */
    fun getCalls(limit: Int = 500, types: IntArray? = null): List<CallRecord> {
        val result = mutableListOf<CallRecord>()
        val projection = arrayOf(
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val selection = types
            ?.takeIf { it.isNotEmpty() }
            ?.let { "${CallLog.Calls.TYPE} IN (${it.joinToString(",") { "?" }})" }
        val args = types?.takeIf { it.isNotEmpty() }?.map { it.toString() }?.toTypedArray()

        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            selection,
            args,
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
     *
     * Grouped on the last [MATCH_TAIL] digits, not on the raw string. One person
     * dialled once as `+918336051755` and once as `8336051755` is two different
     * strings and was therefore two rows — the same contact listed twice in the
     * dialer, once with a country code and once without. The digits are what
     * identify a line; the punctuation in front of them is a formatting accident.
     *
     * The surviving row keeps the most complete form of the number, so a contact
     * that has ever been dialled in full international form is shown that way
     * rather than in whatever shorthand happened to sort first.
     */
    fun getMostUsed(limit: Int = 20): List<FavoriteNumber> =
        getCalls(limit = 1000)
            .filter { it.number.isNotBlank() && !it.number.equals("Unknown", ignoreCase = true) }
            .groupBy { record ->
                record.number.filter(Char::isDigit).takeLast(MATCH_TAIL)
                    .ifEmpty { record.number }
            }
            .map { (_, entries) ->
                FavoriteNumber(
                    name = entries.firstOrNull { !it.name.isNullOrBlank() }?.name,
                    number = entries.maxByOrNull { it.number.length }?.number
                        ?: entries.first().number,
                    count = entries.size
                )
            }
            .sortedByDescending { it.count }
            .take(limit)

    companion object {
        /**
         * Digits that identify a line, counted from the end.
         *
         * Ten, matching ContactRepository.MATCH_DIGITS — the whole app has to agree
         * on this or the same number is one row in one place and two in another.
         */
        const val MATCH_TAIL = 10
    }
}
