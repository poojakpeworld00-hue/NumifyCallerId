package com.callerid.numberlookup.home.feature.finder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.callerid.numberlookup.home.repository.finder.SearchHistoryRepository

/** Backs the standalone search-history screen; reads/clears the shared history store. */
class SearchHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStore = SearchHistoryRepository(app)

    private val _history = MutableLiveData<List<SearchHistoryEntry>>(emptyList())
    val history: LiveData<List<SearchHistoryEntry>> = _history

    fun load() {
        _history.value = historyStore.all()
    }

    fun clear() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
