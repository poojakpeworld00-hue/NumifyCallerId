package com.contacts.callerid.number.lookup.feature.widgets

import android.content.Context
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.common.TimeFormats
import com.contacts.callerid.number.lookup.repository.CallType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatting helpers shared by call-log adapters. */
object CallActionHandler {

    fun displayName(name: String?, number: String): String =
        if (!name.isNullOrBlank()) name else number

    fun initials(name: String?, number: String): String {
        if (!name.isNullOrBlank()) {
            val parts = name.trim().split(" ").filter { it.isNotEmpty() }
            val letters = parts.take(2).joinToString("") { it.first().uppercase() }
            if (letters.isNotEmpty()) return letters
        }
        return "#"
    }

    /**
     * A call's timestamp: the time for today, the date otherwise — and the year
     * too, once the call is from a different one.
     *
     * The year used to be left off always. On a log that only goes back a few
     * weeks that reads fine; on one spanning years it is a lie. A call from
     * August 2024 sitting below one from March 2026 both said "Aug 12" and
     * "Mar 24", so the list looked as if it had lost its ordering when it was
     * sorted correctly the whole time.
     *
     * Today's time comes from [TimeFormats], which follows the device's 12/24-hour
     * setting. The date halves stay pattern-based: there is no system preference
     * for how a date is written, only for the clock.
     */
    fun timeLabel(context: Context, date: Long): String {
        if (isToday(date)) return TimeFormats.clock(context, date)
        val pattern = if (isThisYear(date)) "MMM d" else "MMM d, yyyy"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(date))
    }

    /** Whether [date] falls in the current calendar year. */
    private fun isThisYear(date: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = date }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    }

    fun durationLabel(seconds: Long): String {
        if (seconds <= 0) return ""
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }

    fun typeLabelRes(type: CallType): Int = when (type) {
        CallType.INCOMING -> R.string.type_incoming
        CallType.OUTGOING -> R.string.type_outgoing
        CallType.MISSED -> R.string.type_missed
        CallType.SPAM -> R.string.type_blocked
    }

    fun typeIconRes(type: CallType): Int = when (type) {
        CallType.INCOMING -> R.drawable.ic_call_received
        CallType.OUTGOING -> R.drawable.ic_call_made
        CallType.MISSED -> R.drawable.ic_call_missed
        CallType.SPAM -> R.drawable.ic_warning
    }

    fun typeColorRes(type: CallType): Int = when (type) {
        CallType.INCOMING, CallType.OUTGOING -> R.color.on_surface_variant
        CallType.MISSED -> R.color.danger
        CallType.SPAM -> R.color.warn
    }

    private fun isToday(date: Long): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        cal.timeInMillis = date
        return cal.get(Calendar.DAY_OF_YEAR) == today && cal.get(Calendar.YEAR) == year
    }
}
