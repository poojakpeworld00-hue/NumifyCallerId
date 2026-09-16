package com.callerid.numberlookup.home.repository.assistant

/**
 * How this app reads a number from its own history, with no network involved.
 *
 * The judgement is deliberately shared: the Ask AI spam answer and the line on
 * the incoming-call card have to agree, or the app contradicts itself while the
 * phone is ringing. Keeping it pure also makes the thresholds testable, which
 * matters because they are the whole product — a false SPAM on a number the user
 * actually wants is worse than saying nothing at all.
 */
enum class CallerRisk {
    /** The user blocked this number themselves. Nothing to weigh up. */
    BLOCKED,

    /** Rang repeatedly and was never once answered. */
    NUISANCE,

    /** In the address book, so the user already decided about it. */
    KNOWN,

    /** Never seen before — worth flagging as new, but not as bad. */
    FIRST_TIME,

    /** Has called before and been answered. Ordinary. */
    ORDINARY;

    companion object {

        /**
         * Calls from one number, all unanswered, before it reads as a nuisance.
         *
         * Three rather than two on purpose: two unanswered calls is what a friend
         * does when you are away from your desk, and calling that spam is the
         * error that loses trust.
         */
        const val NUISANCE_THRESHOLD = 3

        /**
         * @param blocked      the number is on the blocklist
         * @param knownContact the number resolves to a saved contact
         * @param totalCalls   times it appears in the call log
         * @param unanswered   how many of those were never picked up
         */
        fun assess(
            blocked: Boolean,
            knownContact: Boolean,
            totalCalls: Int,
            unanswered: Int
        ): CallerRisk = when {
            blocked -> BLOCKED
            // A saved contact outranks the call pattern: someone in the address
            // book who keeps missing you is not a nuisance caller.
            knownContact -> KNOWN
            totalCalls == 0 -> FIRST_TIME
            unanswered >= NUISANCE_THRESHOLD && unanswered == totalCalls -> NUISANCE
            else -> ORDINARY
        }
    }
}
