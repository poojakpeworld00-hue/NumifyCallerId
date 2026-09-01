package com.numify.callerid.lookup.repository.assistant

import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType

/**
 * Whole-call-log analyses behind the two assistant tools.
 *
 * Both group the log by number and judge each group, so they are written as one
 * pass over pure data with no Context. That keeps the thresholds testable, which
 * matters more here than anywhere else in the assistant: these screens end in
 * bulk actions, and a rule that is slightly wrong blocks someone the user wanted
 * or nags them to save a number they deliberately never saved.
 */
object CallLogScan {

    /** One unknown number the scanner thinks is worth blocking. */
    data class SpamCandidate(
        val number: String,
        val calls: Int,
        val unanswered: Int,
        val lastCallMs: Long,
        val insight: CallerInsight
    )

    /** A number the user deals with often but has never saved. */
    data class UnsavedCaller(
        val number: String,
        val calls: Int,
        val answered: Int,
        val lastCallMs: Long
    )

    /** Calls from one number before the scanner will propose blocking it. */
    const val SPAM_MIN_CALLS = 3

    /** Calls with a number before "you should probably save this" is fair. */
    const val UNSAVED_MIN_CALLS = 4

    /**
     * Unknown numbers ranked worst-first.
     *
     * [isBlocked] and [isKnownContact] are passed in rather than looked up here,
     * so the caller decides how numbers are matched and this stays pure.
     *
     * Already-blocked numbers are excluded: the screen's whole purpose is a Block
     * button, and offering it for something already blocked is a dead row.
     */
    fun spamCandidates(
        history: List<CallRecord>,
        isBlocked: (String) -> Boolean,
        isKnownContact: (String) -> Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<SpamCandidate> =
        history.groupBy { it.number }
            .asSequence()
            .filter { (number, _) -> number.isNotBlank() }
            .filter { (number, _) -> !isBlocked(number) && !isKnownContact(number) }
            .mapNotNull { (number, calls) ->
                if (calls.size < SPAM_MIN_CALLS) return@mapNotNull null
                val insight = CallerInsight.of(calls, blocked = false, knownContact = false, nowMs = nowMs)
                // Only the two states that actually accuse the number. An unknown
                // caller you have answered before is not a scanner result.
                if (insight !is CallerInsight.Nuisance && insight !is CallerInsight.MissedStreak) {
                    return@mapNotNull null
                }
                SpamCandidate(
                    number = number,
                    calls = calls.size,
                    unanswered = calls.count { it.type == CallType.MISSED },
                    lastCallMs = calls.maxOf { it.date },
                    insight = insight
                )
            }
            // Worst first: most unanswered, then most recent, so the top of the
            // list is both the strongest case and the freshest annoyance.
            .sortedWith(compareByDescending<SpamCandidate> { it.unanswered }.thenByDescending { it.lastCallMs })
            .toList()

    /**
     * Numbers worth saving as contacts, most-contacted first.
     *
     * Requires at least one *answered* call. A number that only ever rang and was
     * never picked up belongs in [spamCandidates], not in a list telling the user
     * to add it to their address book.
     */
    fun unsavedCallers(
        history: List<CallRecord>,
        isKnownContact: (String) -> Boolean,
        isBlocked: (String) -> Boolean
    ): List<UnsavedCaller> =
        history.groupBy { it.number }
            .asSequence()
            .filter { (number, _) -> number.isNotBlank() }
            .filter { (number, _) -> !isKnownContact(number) && !isBlocked(number) }
            .mapNotNull { (number, calls) ->
                val answered = calls.count { it.type != CallType.MISSED }
                if (calls.size < UNSAVED_MIN_CALLS || answered == 0) return@mapNotNull null
                UnsavedCaller(
                    number = number,
                    calls = calls.size,
                    answered = answered,
                    lastCallMs = calls.maxOf { it.date }
                )
            }
            .sortedWith(compareByDescending<UnsavedCaller> { it.calls }.thenByDescending { it.lastCallMs })
            .toList()
}
