package com.numify.callerid.lookup.repository

import android.content.Context
import com.numify.callerid.monetize.model.lookupRegionByIp
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.feature.finder.CountryCatalog
import com.numify.callerid.lookup.common.WindowInsetsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The single source of truth for the user's IP-resolved country.
 *
 * Detection happens **once**: AdAwareActivity resolves it early through
 * [lookupRegionByIp] and stores it in [SettingsRepository.homeCountryIso]. Every
 * other caller - Language, Home, Lookup - comes through [detectCountry], which
 * reuses that cached value and only reaches for the network when nothing has
 * resolved it yet, so the geo endpoint is never hit more than once.
 *
 * It is best-effort, returning null when there is no cache and the network call
 * fails. ip-api yields only the ISO code, so the dialling code is derived from
 * [CountryCatalog].
 */
object RegionDetector {

    private const val TAG = "RegionDetector"

    /**
     * TEST ONLY — force a country in debug builds, bypassing cache and network.
     * Set to an ISO-3166 alpha-2 code (e.g. "JP" for Japan) to test that region's
     * suggestions; set back to null for normal IP detection. No effect in release.
     */
    private val DEBUG_FORCE_ISO: String? = "JP"

    /** Resolved country: ISO-3166 alpha-2 plus the dialing code (digits, no '+'). */
    data class GeoCountry(val iso: String, val dial: String)

    /**
     * The user's country, cache-first:
     *  1. [SettingsRepository.homeCountryIso] — user pick or AdAwareActivity's detection.
     *  2. [SettingsRepository.geoCountryIso]  — our own previously-cached IP result.
     *  3. Only if both are empty, hit the network once and cache the result.
     *
     * Returns null only when there's no cache and the IP lookup fails.
     */
    suspend fun detectCountry(context: Context): GeoCountry? {
        // Debug override wins over everything so a forced region is deterministic.
        if (BuildConfig.DEBUG) {
            validCountry(DEBUG_FORCE_ISO)?.let { forced ->
                WindowInsetsHelper.log(TAG, "DEBUG force country=$forced (cache/network bypassed)")
                return GeoCountry(forced, CountryCatalog.dialOf(forced).orEmpty())
            }
        }

        val prefs = SettingsRepository(context)

        // Reuse a cached value only if it's a *real* ISO-2 country (present in the
        // dial table). This rejects stale/garbage codes (e.g. a language tag like
        // "JA" wrongly stored as a country), so the cache self-heals via re-detect.
        val cached = validCountry(prefs.homeCountryIso) ?: validCountry(prefs.geoCountryIso)
        if (cached != null) {
            WindowInsetsHelper.log(TAG, "reuse cached country=$cached (no IP call)")
            return GeoCountry(cached, CountryCatalog.dialOf(cached).orEmpty())
        }

        val geo = detectCountryFromIp() ?: return null
        // Cache under our own key (not homeCountryIso) so it's never confused with
        // an explicit user pick — subsequent callers reuse it without a network hit.
        prefs.geoCountryIso = geo.iso
        WindowInsetsHelper.log(TAG, "cached detected country=${geo.iso} for app-wide reuse")
        return geo
    }

    /** An uppercase ISO-2 code, or null when [raw] isn't a recognised country. */
    private fun validCountry(raw: String?): String? {
        val iso = raw?.takeIf { it.length == 2 }?.uppercase() ?: return null
        return iso.takeIf { CountryCatalog.dialOf(it) != null }
    }

    /** One-shot IP lookup via the app's shared [lookupRegionByIp] source (ip-api.com). */
    private suspend fun detectCountryFromIp(): GeoCountry? = withContext(Dispatchers.IO) {
        WindowInsetsHelper.log(TAG, "no cache → lookupRegionByIp()")
        val location = lookupRegionByIp()
        if (location == null) {
            WindowInsetsHelper.log(TAG, "lookupRegionByIp() returned null → null")
            return@withContext null
        }
        WindowInsetsHelper.log(TAG, "location: country=${location.country} code=${location.countryCode}")

        val iso = location.countryCode?.takeIf { it.length == 2 }?.uppercase()
        if (iso == null) {
            WindowInsetsHelper.log(TAG, "no valid countryCode → null")
            return@withContext null
        }
        val dial = CountryCatalog.dialOf(iso).orEmpty()
        WindowInsetsHelper.log(TAG, "resolved country=$iso dial=$dial")
        GeoCountry(iso, dial)
    }
}
