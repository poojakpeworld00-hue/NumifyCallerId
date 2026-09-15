package com.contacts.callerid.number.lookup.repository.assistant

/**
 * Pulls a dialable number out of a free-text question.
 *
 * Separated from [LocalIntentResolver] because it is the part most likely to be
 * subtly wrong, and it is pure — no Context, so it is covered by JVM tests. The
 * failure that matters is a false positive: reading "last 7 days" or "top 3
 * callers" as a phone number sends the whole question down the spam-verdict
 * branch and produces a confident answer about a number nobody asked about.
 */
internal object NumberInText {

    /**
     * Digits, optionally led by "+" and/or an opening bracket, allowing spaces,
     * dashes and brackets in the middle. The trailing `\d` stops the match
     * running into a following word.
     *
     * The leading `\(?` matters: without it "(020) 7946 0958" matches from the
     * "0" and the number is echoed back to the user missing its first bracket.
     */
    private val PATTERN = Regex("""\+?\(?\d[\d\s\-()]{5,}\d""")

    /** Shortest run of digits that can be a real number rather than a quantity. */
    private const val MIN_DIGITS = 7

    /** The first plausible number in [text], or null when there is none. */
    fun find(text: String): String? =
        PATTERN.findAll(text)
            .map { it.value.trim() }
            .firstOrNull { it.count(Char::isDigit) >= MIN_DIGITS }

    /**
     * The last [count] digits, which is how numbers are compared throughout the
     * app — the same rule [com.contacts.callerid.number.lookup.repository.BlocklistRepository]
     * uses, so "+91 70164 14568" and "7016414568" are one number.
     */
    fun tail(number: String, count: Int = 10): String =
        number.filter(Char::isDigit).takeLast(count)
}
