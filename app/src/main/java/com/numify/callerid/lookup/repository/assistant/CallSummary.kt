package com.numify.callerid.lookup.repository.assistant

import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType

/**
 * What can honestly be said about a call that just ended.
 *
 * Built from call-log metadata — how often, how long, how many went unanswered —
 * and nothing else. There is no transcript and no recording: Android has not
 * allowed a third-party app to capture call audio since Android 10, so a
 * "summary" here means the pattern around the call, not its contents.
 *
 * Pure and free of Context so the thresholds can be tested; the caller turns
 * [Facts] into localized copy.
 */
object CallSummary {

    /**
     * @param callsThisMonth  times this number appears in the last 30 days,
     *                        including the call that just ended
     * @param firstEverCall   nothing before this one
     * @param longestYet      this call ran longer than any previous one
     * @param unanswered      of [callsThisMonth], how many were never picked up
     */
    data class Facts(
        val callsThisMonth: Int,
        val firstEverCall: Boolean,
        val longestYet: Boolean,
        val unanswered: Int
    )

    /**
     * [history] is every logged call for this number, [durationSec] the call that
     * just ended, and [monthStartMs] the cut-off for "this month".
     *
     * The just-ended call may or may not have reached the provider yet — the log
     * is written asynchronously — so it is counted explicitly rather than being
     * assumed present in [history].
     */
    fun facts(
        history: List<CallRecord>,
        durationSec: Long,
        monthStartMs: Long,
        nowMs: Long
    ): Facts {
        // Drop anything the provider already wrote for the call in progress, so
        // it cannot be counted twice.
        val previous = history.filterNot { it.date >= nowMs - RECENT_WRITE_WINDOW_MS }

        val thisMonth = previous.count { it.date >= monthStartMs } + 1
        val longestBefore = previous.maxOfOrNull { it.durationSec } ?: 0L

        return Facts(
            callsThisMonth = thisMonth,
            firstEverCall = previous.isEmpty(),
            // A call nobody answered is not "your longest yet".
            longestYet = durationSec > 0 && previous.isNotEmpty() && durationSec > longestBefore,
            unanswered = previous.count { it.type == CallType.MISSED }
        )
    }

    /** How recently a log row must be to count as the call that just ended. */
    private const val RECENT_WRITE_WINDOW_MS = 60_000L
}
