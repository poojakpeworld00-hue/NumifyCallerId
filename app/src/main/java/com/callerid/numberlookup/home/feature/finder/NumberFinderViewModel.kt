package com.callerid.numberlookup.home.feature.finder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.callerid.numberlookup.home.repository.ContactRepository
import com.callerid.numberlookup.home.repository.finder.SearchHistoryRepository
import com.callerid.numberlookup.home.repository.finder.OfflineNumberIndex
import com.callerid.numberlookup.home.entity.LookupPayload
import com.callerid.numberlookup.home.resolver.EndpointConfig
import com.callerid.numberlookup.home.resolver.CredentialProvider
import com.callerid.numberlookup.home.resolver.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class NumberFinderViewModel(app: Application) : AndroidViewModel(app) {

    private val contactsRepository = ContactRepository(app)
    private val historyStore = SearchHistoryRepository(app)

    private val _state = MutableLiveData<SearchState>(SearchState.Idle)
    val state: LiveData<SearchState> = _state

    private val _history = MutableLiveData(historyStore.all())
    val history: LiveData<List<SearchHistoryEntry>> = _history

    private var searchToken = 0

    // CountryItem selected in the picker (defaults to device region).
    private var selectedIso: String? = null
    private var selectedDial: String? = null

    fun changeRegion(iso: String, dial: String) {
        selectedIso = iso
        selectedDial = dial
    }

    fun search(raw: String) {
        val typed = NumberDetails.normalize(raw)
        // Apply the chosen country's dialing code when the user didn't type a '+'.
        val normalized = when {
            typed.startsWith("+") -> typed
            !selectedDial.isNullOrBlank() -> "+" + selectedDial + typed.filter { it.isDigit() }
            else -> typed
        }
        val digitCount = normalized.count { it.isDigit() }
        if (digitCount < MIN_DIGITS) {
            _state.value = SearchState.Idle
            return
        }

        val token = ++searchToken
        _state.value = SearchState.Loading
        viewModelScope.launch {
            // Never blank — a blank region makes libphonenumber fail to parse
            // bare numbers, nulling out country/carrier/line-type/city.
            val region = (selectedIso ?: Locale.getDefault().country).ifBlank { "US" }
            // Show the shimmer for a fixed minimum so the loading state is visible.
            val data = withContext(Dispatchers.IO) {
                val contactName = contactsRepository.lookupNameByNumber(normalized)
                val offline = OfflineNumberIndex.lookup(normalized, region) // libphonenumber, no network
                val payload = fetchFromApi(normalized)
                Triple(contactName, offline, payload)
            }
            delay(SHIMMER_MIN_MS)
            if (token != searchToken) return@launch // a newer query superseded this one

            val (contactName, offline, payload) = data
            val apiList = payload?.data.orEmpty()
            // Prefer the community/network name so a saved contact shows how OTHERS
            // identify this number (not the name you already gave it). Fall back to
            // your own contact name only when the network has no name at all.
            // primaryName() skips the junk rows the endpoint returns (the number
            // itself, fragments of it, "Llllll").
            val apiPrimary = payload?.primaryName()
            val displayName = apiPrimary ?: contactName

            // Country, city and the spam verdict are top-level in the response, not
            // per row - read them from the payload, then fall back to the offline
            // (libphonenumber) result. Carrier and line type are per-row when sent.
            val apiCarrier = apiList.firstNotNullOfOrNull { it.carrierOrNull }
            val apiCountry = payload?.countryName
            val apiLineType = apiList.firstNotNullOfOrNull { it.lineTypeOrNull }
            val apiCity = payload?.city

            // "Also known as": distinct network names, excluding the primary shown name
            // AND your own saved contact name — so your own name is never echoed here.
            val nicknames = payload?.meaningfulNames().orEmpty()
                .filter {
                    !it.equals(displayName, ignoreCase = true) &&
                        !it.equals(contactName, ignoreCase = true)
                }
                .take(10)

            val result = LookupVerdict(
                name = displayName,
                number = NumberDetails.format(normalized),
                rawNumber = normalized,
                inContacts = contactName != null,
                regionCode = NumberDetails.regionCode(normalized),
                country = apiCountry ?: offline?.regionName ?: offline?.location,
                carrier = apiCarrier ?: offline?.carrier,
                lineType = apiLineType ?: offline?.lineType,
                valid = offline?.valid,
                city = apiCity ?: offline?.location,
                isSpam = payload?.isSpam == true,
                spamType = apiList.firstNotNullOfOrNull { it.spamType?.takeIf { t -> t.isNotBlank() } },
                nicknames = nicknames
            )
            Log.d(TAG, "carrier: api=$apiCarrier offline=${offline?.carrier} → ${result.carrier}")
            Log.d(TAG, "result: carrier=${result.carrier}, lineType=${result.lineType}, country=${result.country}")
            _state.value = SearchState.Result(result)
            saveToHistory(result)
        }
    }

    /** Queries the caller-ID API for this number; null when it failed or was unreachable. */
    private suspend fun fetchFromApi(phone: String): LookupPayload? = runCatching {
        val response = NetworkClientFactory.api.checkPhoneNumber(
            url = EndpointConfig.similarPhonePath(getApplication()),
            phone = phone
        )
        if (response.isSuccessful) {
            response.body()
        } else {
            Log.e(TAG, "checkPhoneNumber failed (${response.code()})")
            null
        }
    }.onFailure { Log.e(TAG, "checkPhoneNumber error: ${it.message}") }.getOrNull()

    private fun saveToHistory(result: LookupVerdict) {
        val subtitle = result.country
            ?: NumberDetails.regionName(result.rawNumber)
            ?: result.number
        historyStore.add(
            SearchHistoryEntry(
                rawNumber = result.rawNumber,
                number = result.number,
                name = result.name,
                subtitle = subtitle
            )
        )
        _history.value = historyStore.all()
    }

    fun clearSearchHistory() {
        historyStore.clear()
        _history.value = emptyList()
    }

    /** Re-reads the persisted history (e.g. after the standalone history screen edits it). */
    fun refreshSearchHistory() {
        _history.value = historyStore.all()
    }

    fun clear() {
        _state.value = SearchState.Idle
    }

    fun regionName(normalized: String): String? = NumberDetails.regionName(normalized)

    companion object {
        private const val TAG = "NumberFinderViewModel"
        private const val MIN_DIGITS = 3
        private const val SHIMMER_MIN_MS = 2_000L
    }
}
