package com.numify.callerid.numberlookup.store.search

import android.content.Context
import com.numify.callerid.numberlookup.screen.search.SearchHistoryEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists recent number lookups in SharedPreferences (most-recent first,
 * de-duplicated by raw number, capped at [MAX]).
 */
class SearchHistoryRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<SearchHistoryEntry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SearchHistoryEntry(
                    rawNumber = o.optString("raw"),
                    number = o.optString("num"),
                    name = o.optString("name").ifBlank { null },
                    subtitle = o.optString("sub").ifBlank { null }
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(entry: SearchHistoryEntry) {
        val current = all().filter { it.rawNumber != entry.rawNumber }.toMutableList()
        current.add(0, entry)
        val capped = current.take(MAX)
        val arr = JSONArray()
        capped.forEach {
            arr.put(
                JSONObject()
                    .put("raw", it.rawNumber)
                    .put("num", it.number)
                    .put("name", it.name ?: "")
                    .put("sub", it.subtitle ?: "")
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object {
        private const val PREFS = "lookup_history"
        private const val KEY = "entries"
        private const val MAX = 20
    }
}
