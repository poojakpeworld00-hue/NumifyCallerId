package com.numify.callerid.lookup.repository

import android.content.Context

/** A blocked number together with when it was added. */
data class BlockedNumber(val number: String, val addedAt: Long)

/** Persists user-blocked phone numbers (and when they were blocked) in SharedPreferences. */
class BlocklistRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(): List<String> =
        prefs.getStringSet(KEY, emptySet()).orEmpty().sorted()

    /** Blocked numbers as entries, newest first. */
    fun getEntries(): List<BlockedNumber> =
        prefs.getStringSet(KEY, emptySet()).orEmpty()
            .map { BlockedNumber(it, prefs.getLong(timeKey(it), 0L)) }
            .sortedByDescending { it.addedAt }

    fun add(number: String) {
        val value = number.trim()
        if (value.isEmpty()) return
        save(current().apply { add(value) })
        prefs.edit().putLong(timeKey(value), System.currentTimeMillis()).apply()
    }

    fun remove(number: String) {
        save(current().apply { remove(number) })
        prefs.edit().remove(timeKey(number)).apply()
    }

    /**
     * True if [number] is blocked. Matches exactly first, then by normalized
     * digits (last 10) so different formats of the same number still match
     * (e.g. "+91 70164 14568" vs "7016414568").
     */
    fun isBlocked(number: String): Boolean {
        val set = prefs.getStringSet(KEY, emptySet()).orEmpty()
        if (set.contains(number.trim())) return true
        val target = normalizeDigits(number)
        if (target.isEmpty()) return false
        return set.any { normalizeDigits(it) == target }
    }

    private fun normalizeDigits(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.length >= 10) d.takeLast(10) else d
    }

    private fun current(): MutableSet<String> =
        HashSet(prefs.getStringSet(KEY, emptySet()).orEmpty())

    private fun save(set: Set<String>) {
        prefs.edit().putStringSet(KEY, set).apply()
    }

    private fun timeKey(number: String) = "$KEY_TIME_PREFIX$number"

    companion object {
        private const val PREFS_NAME = "blocklist"
        private const val KEY = "blocked_numbers"
        private const val KEY_TIME_PREFIX = "blocked_at_"
    }
}
