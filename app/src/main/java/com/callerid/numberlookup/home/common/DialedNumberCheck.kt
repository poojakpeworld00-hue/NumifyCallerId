package com.callerid.numberlookup.home.common

import android.content.Context
import android.telephony.TelephonyManager
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * Is what the user has typed a real phone number in their country?
 *
 * The dialer offers to look a number up while it is still being typed, and four
 * digits is not a number anyone can look up — in India or anywhere else. The
 * answer is country-specific, so it comes from libphonenumber (already a
 * dependency, and what the lookup result screen validates with) rather than a
 * digit count that would be wrong somewhere.
 *
 * Deliberately strict: `isValidNumber`, not `isPossibleNumber`. Possible-number
 * only checks the length, which would call a four-digit short code valid in
 * plenty of regions and put the row back.
 */
object DialedNumberCheck {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** True when [raw] parses as a valid number for the user's region. */
    fun isLookupable(context: Context, raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty()) return false
        // parse() throws on anything it cannot make sense of, which for a field
        // being typed into is most of the time — that is a "no", not a crash.
        return runCatching { util.isValidNumber(util.parse(value, region(context))) }
            .getOrDefault(false)
    }

    /**
     * The region to judge the number against, best evidence first: the country
     * the user picked for lookups, then the SIM, then the network, then the
     * device locale.
     *
     * The SIM outranks the network so a roaming user's own numbers still read as
     * valid, and the locale is the last resort because a phone set to en-GB in
     * Delhi says nothing about the numbers being dialled.
     */
    private fun region(context: Context): String {
        SettingsRepository(context).homeCountryIso
            .takeIf { it.isNotBlank() }
            ?.let { return it.uppercase(Locale.ROOT) }

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        runCatching { tm?.simCountryIso }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.uppercase(Locale.ROOT) }
        runCatching { tm?.networkCountryIso }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.uppercase(Locale.ROOT) }

        return Locale.getDefault().country.ifBlank { "US" }
    }
}
