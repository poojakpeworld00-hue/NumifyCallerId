package com.callerid.numberlookup.home.feature.finder

/** Lightweight, offline number helpers: formatting + region from country code. */
object NumberDetails {

    /** CountryItem-calling-code -> human-readable region. Longest codes matched first. */
    private val regions: Map<String, String> = linkedMapOf(
        "+1" to "United States / Canada",
        "+44" to "United Kingdom",
        "+91" to "India",
        "+92" to "Pakistan",
        "+880" to "Bangladesh",
        "+971" to "United Arab Emirates",
        "+966" to "Saudi Arabia",
        "+61" to "Australia",
        "+33" to "France",
        "+49" to "Germany",
        "+34" to "Spain",
        "+39" to "Italy",
        "+55" to "Brazil",
        "+52" to "Mexico",
        "+81" to "Japan",
        "+86" to "China",
        "+7" to "Russia / Kazakhstan",
        "+27" to "South Africa",
        "+234" to "Nigeria",
        "+62" to "Indonesia",
        "+63" to "Philippines",
        "+90" to "Türkiye",
        "+20" to "Egypt"
    )

    /** Removes everything except digits and a leading '+'. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        val plus = if (trimmed.startsWith("+")) "+" else ""
        return plus + trimmed.filter { it.isDigit() }
    }

    /** Returns the matched country calling code (e.g. "+1") or empty string. */
    fun regionCode(normalized: String): String {
        if (!normalized.startsWith("+")) return ""
        // Try longest prefixes first (up to 4 chars incl '+').
        for (len in 4 downTo 2) {
            val prefix = normalized.take(len)
            if (regions.containsKey(prefix)) return prefix
        }
        return ""
    }

    /** Human-readable region, or null when it can't be determined. */
    fun regionName(normalized: String): String? {
        val code = regionCode(normalized)
        return regions[code]
    }

    /** Pretty-print a normalized number in light groups. */
    fun format(normalized: String): String {
        if (normalized.isEmpty()) return normalized
        val hasPlus = normalized.startsWith("+")
        val digits = normalized.filter { it.isDigit() }
        if (digits.length < 7) return normalized
        // Group the last 10 digits as (xxx) xxx-xxxx style when possible.
        val tail = digits.takeLast(10)
        val country = digits.dropLast(10)
        val grouped = when (tail.length) {
            10 -> "${tail.substring(0, 3)} ${tail.substring(3, 6)} ${tail.substring(6)}"
            else -> tail
        }
        val prefix = if (hasPlus) "+$country " else if (country.isNotEmpty()) "$country " else ""
        return (prefix + grouped).trim()
    }
}
