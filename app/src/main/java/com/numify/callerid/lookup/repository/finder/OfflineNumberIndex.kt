package com.numify.callerid.lookup.repository.finder

import com.google.i18n.phonenumbers.PhoneNumberToCarrierMapper
import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder
import java.util.Locale

/** Number metadata resolved fully offline via libphonenumber (no network). */
data class LocalNumberFacts(
    val valid: Boolean,
    val regionName: String?,  // country, e.g. "India"
    val location: String?,    // geocoded area, e.g. "California" / "Bengaluru"
    val carrier: String?,     // carrier name, e.g. "Airtel"
    val lineType: String?     // Mobile / Landline / VoIP / ...
)

/**
 * Offline number lookup built on Google's libphonenumber:
 *  - [PhoneNumberOfflineGeocoder] for the location description,
 *  - [PhoneNumberToCarrierMapper] for the carrier name,
 *  - [PhoneNumberUtil] for validity, line type and country.
 *
 * This took over from the old numverify (apilayer.net) online call. It is safe to
 * run off the main thread, and the instances are reused because the metadata
 * loads lazily.
 */
object OfflineNumberIndex {

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private val geocoder: PhoneNumberOfflineGeocoder by lazy { PhoneNumberOfflineGeocoder.getInstance() }
    private val carrierMapper: PhoneNumberToCarrierMapper by lazy { PhoneNumberToCarrierMapper.getInstance() }

    /**
     * @param normalized     the number, ideally in E.164 form starting "+"; bare
     *                       numbers fall back to [fallbackRegion].
     * @param fallbackRegion ISO-3166 alpha-2 region, "IN" for example, applied when
     *                       [normalized] carries no "+".
     */
    fun lookup(normalized: String, fallbackRegion: String?): LocalNumberFacts? {
        // Parse first; bail only if parsing fails. A bare (no "+") number needs a
        // region — fall back to "US" when none is supplied so parsing never fails
        // on a blank region (which would null out every field).
        val parsed = runCatching {
            val region = if (normalized.startsWith("+")) null
            else (fallbackRegion?.takeIf { it.isNotBlank() } ?: "US").uppercase(Locale.ROOT)
            phoneUtil.parse(normalized, region)
        }.getOrNull() ?: return null

        // Each lookup is independent so a missing geocoder/carrier metadata file
        // (or any single failure) can't wipe out the others (e.g. carrier).
        val valid = runCatching { phoneUtil.isValidNumber(parsed) }.getOrDefault(false)
        val location = runCatching { geocoder.getDescriptionForNumber(parsed, Locale.ENGLISH) }
            .getOrNull()?.ifBlank { null }
        val carrier = runCatching { carrierMapper.getNameForNumber(parsed, Locale.ENGLISH) }
            .getOrNull()?.ifBlank { null }
        val lineType = runCatching { readableType(phoneUtil.getNumberType(parsed)) }.getOrNull()
        val regionName = runCatching {
            phoneUtil.getRegionCodeForNumber(parsed)
                ?.let { Locale("", it).getDisplayCountry(Locale.ENGLISH).ifBlank { null } }
        }.getOrNull()

        return LocalNumberFacts(valid, regionName, location, carrier, lineType)
    }

    private fun readableType(type: PhoneNumberType): String? = when (type) {
        PhoneNumberType.MOBILE -> "Mobile"
        PhoneNumberType.FIXED_LINE -> "Landline"
        PhoneNumberType.FIXED_LINE_OR_MOBILE -> "Mobile / Landline"
        PhoneNumberType.VOIP -> "VoIP"
        PhoneNumberType.TOLL_FREE -> "Toll-free"
        PhoneNumberType.PREMIUM_RATE -> "Premium rate"
        PhoneNumberType.SHARED_COST -> "Shared cost"
        PhoneNumberType.PAGER -> "Pager"
        PhoneNumberType.PERSONAL_NUMBER -> "Personal"
        PhoneNumberType.UAN -> "UAN"
        PhoneNumberType.VOICEMAIL -> "Voicemail"
        else -> null
    }
}
