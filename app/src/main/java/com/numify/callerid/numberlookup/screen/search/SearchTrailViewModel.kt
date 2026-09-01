package com.numify.callerid.numberlookup.screen.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.numify.callerid.numberlookup.store.search.SearchTrailStore

/** Backs the standalone search-history screen; reads/clears the shared history store. */
class SearchTrailViewModel(app: Application) : AndroidViewModel(app) {

    private val historyStore = SearchTrailStore(app)

    private val _history = MutableLiveData<List<TrailEntry>>(emptyList())
    val history: LiveData<List<TrailEntry>> = _history

    fun load() {
        _history.value = historyStore.all()
    }

    fun clear() {
        historyStore.clear()
        _history.value = emptyList()
    }
}
