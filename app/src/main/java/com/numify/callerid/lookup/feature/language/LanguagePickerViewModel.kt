package com.numify.callerid.lookup.feature.language

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.numify.callerid.lookup.foundation.BaseViewModel

class LanguagePickerViewModel : BaseViewModel() {

    val languages: List<LanguageOption> = LocaleCatalog.all

    // The "Suggested" group is chosen by the user's region (GEO). "All languages"
    // holds everything not already surfaced under Suggested, so nothing repeats.
    // Both are LiveData because the region can arrive asynchronously (IP lookup),
    // refining an initial guess made from the device locale.
    private val _suggested = MutableLiveData<List<LanguageOption>>()
    val suggested: LiveData<List<LanguageOption>> = _suggested

    private val _others = MutableLiveData<List<LanguageOption>>()
    val others: LiveData<List<LanguageOption>> = _others

    private val _selectedTag = MutableLiveData(LocaleCatalog.all.first().tag)
    val selectedTag: LiveData<String> = _selectedTag

    /** Remembers the last region applied so an IP result equal to it is a no-op. */
    private var appliedCountry: String? = "__none__"

    fun init(currentTag: String) {
        if (languages.any { it.tag == currentTag }) {
            _selectedTag.value = currentTag
        }
    }

    /**
     * Rebuilds the Suggested and All groups for a country, given as ISO-3166
     * alpha-2 or null when unknown. It is idempotent for the same country, so
     * repeated calls from the locale guess and the IP refine do not thrash the
     * lists.
     */
    fun confirmCountry(iso2: String?) {
        val normalized = iso2?.uppercase()
        if (normalized == appliedCountry) return
        appliedCountry = normalized

        val suggested = LocaleCatalog.suggestedFor(normalized)
        val suggestedTags = suggested.map { it.tag }.toSet()
        _suggested.value = suggested
        _others.value = languages.filterNot { it.tag in suggestedTags }
    }

    fun select(tag: String) {
        if (_selectedTag.value != tag) _selectedTag.value = tag
    }
}
