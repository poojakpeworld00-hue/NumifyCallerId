package com.callora.callerid.numberlookup.screen.shared

import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.store.CallDirection
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Formatting helpers shared by call-log adapters. */
object CallPresenter {

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

    fun typeLabelRes(type: CallDirection): Int = when (type) {
        CallDirection.INCOMING -> R.string.type_incoming
        CallDirection.OUTGOING -> R.string.type_outgoing
        CallDirection.MISSED -> R.string.type_missed
        CallDirection.SPAM -> R.string.type_blocked
    }

    fun typeIconRes(type: CallDirection): Int = when (type) {
        CallDirection.INCOMING -> R.drawable.glyph_call_received
        CallDirection.OUTGOING -> R.drawable.glyph_call_made
        CallDirection.MISSED -> R.drawable.glyph_call_missed
        CallDirection.SPAM -> R.drawable.glyph_warning
    }

    fun typeColorRes(type: CallDirection): Int = when (type) {
        CallDirection.INCOMING, CallDirection.OUTGOING -> R.color.on_surface_variant
        CallDirection.MISSED -> R.color.danger
        CallDirection.SPAM -> R.color.warn
    }

    private fun isToday(date: Long): Boolean {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val year = cal.get(Calendar.YEAR)
        cal.timeInMillis = date
        return cal.get(Calendar.DAY_OF_YEAR) == today && cal.get(Calendar.YEAR) == year
    }
}
