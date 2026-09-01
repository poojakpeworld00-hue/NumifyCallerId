package com.numify.callerid.lookup.feature.assistant

import android.content.Context
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.entity.AiSuggestion
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import java.util.concurrent.TimeUnit

/**
 * Builds the four starter chips on the Ask AI hub.
 *
 * An empty chat box is where new users leave, so the hub never shows one. The
 * chips are ranked from what actually just happened on the device rather than
 * being a fixed list: a spam call two minutes ago is a far better first question
 * than "who called me most this week".
 *
 * More than one rule can hold at once, so each carries an explicit priority and
 * the highest-ranked fills the top slot. Anything still empty is backfilled with
 * generic prompts, so the hub is never short of four.
 */
class AiSuggestionEngine(private val context: Context) {

    private val callLog = CallLogRepository(context)
    private val blocklist = BlocklistRepository(context)

    /** Ranked chips, highest priority first, always [SLOTS] of them. */
    fun suggestions(): List<AiSuggestion> {
        val calls = runCatching { callLog.getCalls() }.getOrDefault(emptyList())
        val contextual = (
            spamJustCalled(calls) +
                missedRecently(calls) +
                justFinishedCall(calls) +
                repeatUnknown(calls)
            ).sortedBy { it.priority }

        return (contextual + backfill()).distinctBy { it.query }.take(SLOTS)
    }

    // --- P1: a spam or blocked number rang in the last 30 minutes -----------

    private fun spamJustCalled(calls: List<CallRecord>): List<AiSuggestion> {
        val recent = calls.firstOrNull {
            it.date >= minutesAgo(SPAM_WINDOW_MIN) &&
                (it.type == CallType.SPAM || blocklist.isNumberBlocked(it.number))
        } ?: return emptyList()

        return listOf(
            AiSuggestion(
                label = context.getString(R.string.ai_chip_is_spam, recent.number),
                query = context.getString(R.string.ai_chip_is_spam, recent.number),
                priority = 1,
                highlighted = true
            )
        )
    }

    // --- P2: an unanswered call in the last two hours -----------------------

    private fun missedRecently(calls: List<CallRecord>): List<AiSuggestion> {
        val missed = calls.firstOrNull {
            it.type == CallType.MISSED && it.date >= minutesAgo(MISSED_WINDOW_MIN)
        } ?: return emptyList()

        val who = missed.name?.takeIf(String::isNotBlank) ?: missed.number
        return listOf(
            AiSuggestion(
                label = context.getString(R.string.ai_chip_reply_to, who),
                query = context.getString(R.string.ai_chip_reply_to, who),
                priority = 2,
                highlighted = true
            )
        )
    }

    // --- P3: a real conversation ended in the last ten minutes --------------

    private fun justFinishedCall(calls: List<CallRecord>): List<AiSuggestion> {
        val done = calls.firstOrNull {
            it.date >= minutesAgo(SUMMARY_WINDOW_MIN) &&
                it.durationSec >= MIN_SUMMARY_SECONDS &&
                it.type != CallType.MISSED
        } ?: return emptyList()

        val who = done.name?.takeIf(String::isNotBlank) ?: done.number
        return listOf(
            AiSuggestion(
                label = context.getString(R.string.ai_chip_summarise, who),
                query = context.getString(R.string.ai_chip_summarise, who),
                priority = 3,
                highlighted = true
            )
        )
    }

    // --- P4: the same unknown number keeps ringing this week ----------------

    private fun repeatUnknown(calls: List<CallRecord>): List<AiSuggestion> {
        val week = calls.filter { it.date >= daysAgo(WEEK_DAYS) && it.name.isNullOrBlank() }
        val persistent = week.groupBy { it.number }
            .filterValues { it.size >= REPEAT_THRESHOLD }
            .keys.firstOrNull() ?: return emptyList()

        return listOf(
            AiSuggestion(
                label = context.getString(R.string.ai_chip_who_keeps_calling, persistent),
                query = context.getString(R.string.ai_chip_who_keeps_calling, persistent),
                priority = 4,
                highlighted = true
            )
        )
    }

    // --- backfill -----------------------------------------------------------

    private fun backfill(): List<AiSuggestion> = listOf(
        suggestion(R.string.ai_follow_top_caller, 90),
        suggestion(R.string.ai_follow_missed, 91),
        suggestion(R.string.ai_follow_blocked_count, 92),
        suggestion(R.string.ai_chip_this_week, 93)
    )

    private fun suggestion(resId: Int, priority: Int): AiSuggestion {
        val text = context.getString(resId)
        return AiSuggestion(text, text, priority)
    }

    private fun minutesAgo(minutes: Long) =
        System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(minutes)

    private fun daysAgo(days: Long) =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)

    private companion object {
        const val SLOTS = 4

        const val SPAM_WINDOW_MIN = 30L
        const val MISSED_WINDOW_MIN = 120L
        const val SUMMARY_WINDOW_MIN = 10L

        /** Below this a call was not a conversation, so there is nothing to summarise. */
        const val MIN_SUMMARY_SECONDS = 60L

        const val WEEK_DAYS = 7L
        const val REPEAT_THRESHOLD = 3
    }
}
