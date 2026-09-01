package com.callora.callerid.numberlookup.screen.keypad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.callora.callerid.numberlookup.store.CallLogSource
import com.callora.callerid.numberlookup.store.FavoriteNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backs [KeypadActivity]: loads the top-used numbers and filters them by the typed query. */
class KeypadViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = CallLogSource(app)

    /** Full pool of used numbers (busiest first) searched while typing. */
    private val all = mutableListOf<FavoriteNumber>()
    private var query = ""

    private val _frequent = MutableLiveData<List<FavoriteNumber>>(emptyList())
    val frequent: LiveData<List<FavoriteNumber>> = _frequent

    /** Loads used numbers. Caller must ensure READ_CALL_LOG is granted. */
    fun load() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repository.getMostUsed(limit = 200) }
            all.clear()
            all.addAll(list)
            applyFilter()
        }
    }

    fun filter(text: String) {
        query = text.trim()
        applyFilter()
    }

    private fun applyFilter() {
        // No query → show only the 20 most-used. Typing searches the whole pool.
        if (query.isEmpty()) {
            _frequent.value = all.take(TOP_LIMIT)
            return
        }
        val digits = query.filter { it.isDigit() }
        _frequent.value = all.filter { item ->
            (digits.isNotEmpty() && item.number.filter { it.isDigit() }.contains(digits)) ||
                item.number.contains(query) ||
                item.name?.contains(query, ignoreCase = true) == true
        }
    }

    private companion object {
        const val TOP_LIMIT = 20
    }
}
