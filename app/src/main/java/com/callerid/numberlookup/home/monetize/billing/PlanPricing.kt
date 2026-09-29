package com.callerid.numberlookup.home.monetize.billing

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import kotlin.math.floor

/**
 * The paywall's price arithmetic: every plan normalised to a price per week, and
 * each plan's saving against paying weekly.
 *
 * The design's rules, which this follows exactly:
 *  - a year is 52 weeks and a month is 52/12 of one (so $19.99/month is
 *    19.99 x 12 / 52 = $4.61 a week, not 19.99 / 4);
 *  - per-week prices round **down** to the smallest unit of the currency, and
 *    savings round down to a whole percent - the screen never overstates a
 *    discount;
 *  - the weekly plan is the baseline, because it is the plan being compared
 *    against.
 *
 * Everything runs on Play's micros and currency code, never on the formatted
 * string, so it holds in every country and currency Play sells in.
 */
object PlanPricing {

    private val PERIOD = Regex("""P(\d+)([DWMY])""")

    /** How many weeks one billing [period] ("P1W", "P1M", "P1Y", "P3M"…) covers. */
    fun weeksIn(period: String?): Double {
        val match = period?.let { PERIOD.matchEntire(it) } ?: return 1.0
        val n = match.groupValues[1].toDouble()
        return when (match.groupValues[2]) {
            "D" -> n / 7.0
            "W" -> n
            "M" -> n * 52.0 / 12.0
            "Y" -> n * 52.0
            else -> 1.0
        }
    }

    /** Days in an ISO-8601 period, for a trial ("P3D" → 3, "P1W" → 7). */
    fun daysIn(period: String?): Int {
        val match = period?.let { PERIOD.matchEntire(it) } ?: return 0
        val n = match.groupValues[1].toInt()
        return when (match.groupValues[2]) {
            "D" -> n
            "W" -> n * 7
            "M" -> n * 30
            "Y" -> n * 365
            else -> 0
        }
    }

    fun perWeekMicros(offer: PremiumOffer): Long =
        (offer.priceMicros / weeksIn(offer.billingPeriod)).toLong()

    /** Whole-percent saving of [offer] against [weekly], rounded down; 0 if none. */
    fun savePct(offer: PremiumOffer, weekly: PremiumOffer?): Int {
        if (weekly == null || weekly.priceMicros <= 0 || offer === weekly) return 0
        if (offer.currencyCode != weekly.currencyCode) return 0
        val ratio = perWeekMicros(offer).toDouble() / weekly.priceMicros
        return floor((1 - ratio) * 100).toInt().coerceAtLeast(0)
    }

    /** [micros] as money in [currencyCode], rounded down to the currency's smallest unit. */
    fun money(micros: Long, currencyCode: String, locale: Locale = Locale.getDefault()): String {
        val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        val format = NumberFormat.getCurrencyInstance(locale).apply {
            roundingMode = RoundingMode.DOWN
            if (currency != null) {
                this.currency = currency
                val digits = currency.defaultFractionDigits.coerceAtLeast(0)
                minimumFractionDigits = digits
                maximumFractionDigits = digits
            }
        }
        return format.format(BigDecimal.valueOf(micros).movePointLeft(6))
    }
}
