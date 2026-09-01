package com.numify.callerid.lookup.feature.widgets

import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallType
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

    fun timeLabel(date: Long): String {
        val pattern = if (isToday(date)) "h:mm a" else "MMM d"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(date))
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
        CallType.INCOMING -> R.drawable.glyph_call_received
        CallType.OUTGOING -> R.drawable.glyph_call_made
        CallType.MISSED -> R.drawable.glyph_call_missed
        CallType.SPAM -> R.drawable.glyph_warning
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
