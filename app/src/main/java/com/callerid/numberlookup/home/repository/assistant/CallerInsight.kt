package com.callerid.numberlookup.home.repository.assistant

import com.callerid.numberlookup.home.repository.CallRecord
import com.callerid.numberlookup.home.repository.CallType
import java.util.concurrent.TimeUnit

/**
 * The one thing worth saying about a caller while the phone is ringing.
 *
 * [CallerRisk] answers "is this dangerous". This answers the wider question the
 * card actually needs — what does this app know that the user does not already
 * see? The three meta chips already show when, how many, and the network, so an
 * insight only earns its place by being something else.
 *
 * Ranked, and only one is shown: a card that lists four facts during a ringing
 * phone is a card nobody finishes reading.
 */
sealed interface CallerInsight {

    /** The user blocked this number and it is ringing anyway. */
    data object Blocked : CallerInsight

    /** Rang repeatedly, never once answered, not in the address book. */
    data class Nuisance(val calls: Int) : CallerInsight

    /**
     * The last [count] calls from this number all went unanswered.
     *
     * Deliberately applies to saved contacts too, and it is the reason this class
     * exists: "you have missed their last 3 calls" is the single most useful
     * thing the app can say about someone you actually know, and nothing else on
     * the card conveys it.
     */
    data class MissedStreak(val count: Int) : CallerInsight

    /** No history at all for this number. */
    data object FirstTime : CallerInsight

    /** An unknown number with a mixed record. */
    data class AnswerRate(val total: Int, val answered: Int) : CallerInsight

    /** A saved contact you are genuinely in touch with. */
    data class Frequent(val callsThisMonth: Int) : CallerInsight

    /** Nothing worth a line. */
    data object None : CallerInsight

    companion object {

        /** Consecutive unanswered calls before the streak is worth mentioning. */
        const val MISSED_STREAK_MIN = 2

        /** Calls in the last 30 days before a contact counts as frequent. */
        const val FREQUENT_MIN = 5

        /**
         * @param history every logged call for this number, any order
         * @param blocked the number is on the blocklist
         * @param knownContact the number resolves to a saved contact
         * @param nowMs current time, injected so the ranking is testable
         */
        fun of(
            history: List<CallRecord>,
            blocked: Boolean,
            knownContact: Boolean,
            nowMs: Long = System.currentTimeMillis()
        ): CallerInsight {
            if (blocked) return Blocked

            val newestFirst = history.sortedByDescending { it.date }
            val unanswered = newestFirst.count { it.type == CallType.MISSED }

            if (!knownContact &&
                unanswered >= CallerRisk.NUISANCE_THRESHOLD &&
                unanswered == newestFirst.size
            ) {
                return Nuisance(newestFirst.size)
            }

            val streak = newestFirst.takeWhile { it.type == CallType.MISSED }.size
            if (streak >= MISSED_STREAK_MIN) return MissedStreak(streak)

            if (newestFirst.isEmpty()) return FirstTime

            if (knownContact) {
                val monthStart = nowMs - TimeUnit.DAYS.toMillis(30)
                val thisMonth = newestFirst.count { it.date >= monthStart }
                return if (thisMonth >= FREQUENT_MIN) Frequent(thisMonth) else None
            }

            return AnswerRate(newestFirst.size, newestFirst.size - unanswered)
        }
    }
}
