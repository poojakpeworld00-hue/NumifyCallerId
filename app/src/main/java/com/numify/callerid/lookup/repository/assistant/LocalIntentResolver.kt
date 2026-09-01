package com.numify.callerid.lookup.repository.assistant

import android.content.Context
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.entity.AiAction
import com.numify.callerid.lookup.entity.AiActionKind
import com.numify.callerid.lookup.entity.AiMessage
import com.numify.callerid.lookup.entity.AiSource
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Answers the questions that can be settled from data already on the device.
 *
 * This exists for two reasons beyond speed. It costs nothing to serve, so these
 * answers never consume a paid quota; and it works with no network and without
 * sending a single number off the device, which is what lets the data-safety
 * disclosure stay narrow.
 *
 * [resolve] returns null when nothing matches, which is the signal for
 * [AiAssistantRepository] to escalate to the model.
 */
class LocalIntentResolver(private val context: Context) {

    private val callLog = CallLogRepository(context)
    private val blocklist = BlocklistRepository(context)

    fun resolve(question: String): AiMessage.Answer? {
        val q = question.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return null

        return when {
            matches(q, SPAM_WORDS) -> spamVerdict(q)
            matches(q, TOP_CALLER_WORDS) -> topCaller()
            matches(q, MISSED_WORDS) -> missedSummary()
            matches(q, BLOCKED_WORDS) -> blocklistSummary()
            else -> null
        }
    }

    // --- intents ------------------------------------------------------------

    /**
     * "Is 98200 41255 spam?" — the verdict leans on three signals we already
     * hold: whether it is blocked, how often it called, and how often it was
     * answered. A number that rings repeatedly and is never picked up is the
     * shape of a nuisance caller even when nobody has reported it yet.
     */
    private fun spamVerdict(q: String): AiMessage.Answer? {
        val number = NumberInText.find(q) ?: return null
        val digits = NumberInText.tail(number, DIGIT_MATCH)
        val history = calls().filter { it.number.filter(Char::isDigit).endsWith(digits) }

        // Report is deliberately absent for now: ReportNumberActivity needs a
        // full LookupVerdict, which only a network lookup produces. It joins
        // this row once the assistant can run a lookup of its own.
        val actions = listOf(
            AiAction(AiActionKind.BLOCK, number),
            AiAction(AiActionKind.DETAILS, number)
        )
        val followUps = listOf(
            context.getString(R.string.ai_follow_who_else),
            context.getString(R.string.ai_follow_blocked_count)
        )

        if (blocklist.isNumberBlocked(number)) {
            // Already blocked, so offering Block again would be a dead button.
            return answer(
                context.getString(R.string.ai_ans_spam_blocked, number, history.size),
                actions.filterNot { it.kind == AiActionKind.BLOCK }, followUps
            )
        }

        val unanswered = history.count { it.type == CallType.MISSED }
        val text = when {
            history.isEmpty() ->
                context.getString(R.string.ai_ans_spam_unknown, number)
            unanswered >= NUISANCE_MISSED && unanswered == history.size ->
                context.getString(R.string.ai_ans_spam_likely, history.size)
            else ->
                context.getString(R.string.ai_ans_spam_clear, history.size, history.size - unanswered)
        }
        return answer(text, actions, followUps)
    }

    /** "Who called me most this week?" */
    private fun topCaller(): AiMessage.Answer? {
        val week = calls().filter { it.date >= sinceDays(WEEK_DAYS) }
        if (week.isEmpty()) return answer(context.getString(R.string.ai_ans_no_calls))

        val (number, records) = week.groupBy { it.number }.maxByOrNull { it.value.size } ?: return null
        val label = records.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } ?: number

        return answer(
            context.getString(R.string.ai_ans_top_caller, label, records.size, week.size),
            listOf(AiAction(AiActionKind.CALL, number), AiAction(AiActionKind.DETAILS, number)),
            listOf(
                context.getString(R.string.ai_follow_missed),
                context.getString(R.string.ai_follow_blocked_count)
            )
        )
    }

    /** "How many calls did I miss?" — plus a reply hand-off for the newest one. */
    private fun missedSummary(): AiMessage.Answer {
        val week = calls().filter { it.date >= sinceDays(WEEK_DAYS) }
        val missed = week.filter { it.type == CallType.MISSED }
        if (missed.isEmpty()) return answer(context.getString(R.string.ai_ans_no_missed))

        val unknown = missed.count { it.name.isNullOrBlank() }
        val newest = missed.maxByOrNull { it.date }
        val who = newest?.name?.takeIf(String::isNotBlank) ?: newest?.number.orEmpty()

        val actions = buildList {
            if (newest != null) {
                add(
                    AiAction(
                        kind = AiActionKind.MESSAGE,
                        number = newest.number,
                        messageBody = context.getString(R.string.ai_draft_missed_reply)
                    )
                )
                add(AiAction(AiActionKind.CALL, newest.number))
            }
        }
        return answer(
            context.getString(R.string.ai_ans_missed, missed.size, unknown, who),
            actions,
            listOf(
                context.getString(R.string.ai_follow_top_caller),
                context.getString(R.string.ai_follow_who_else)
            )
        )
    }

    /** "How many numbers have I blocked?" */
    private fun blocklistSummary(): AiMessage.Answer {
        val entries = blocklist.getEntries()
        if (entries.isEmpty()) return answer(context.getString(R.string.ai_ans_blocklist_empty))

        val recent = entries.count { it.addedAt >= sinceDays(MONTH_DAYS) }
        return answer(
            context.getString(R.string.ai_ans_blocklist, entries.size, recent),
            emptyList(),
            listOf(
                context.getString(R.string.ai_follow_missed),
                context.getString(R.string.ai_follow_top_caller)
            )
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun answer(
        text: String,
        actions: List<AiAction> = emptyList(),
        followUps: List<String> = emptyList()
    ) = AiMessage.Answer(text, actions, followUps, AiSource.LOCAL)

    /**
     * The call log throws rather than returning empty when READ_CALL_LOG is not
     * held, and the assistant is reachable before the permission sheet has been
     * satisfied — so a refusal degrades to "no history" instead of a crash.
     */
    private fun calls(): List<CallRecord> =
        runCatching { callLog.getCalls() }.getOrDefault(emptyList())

    private fun sinceDays(days: Long): Long =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)

    private fun matches(q: String, words: List<String>): Boolean = words.any { it in q }

    private companion object {
        /** Compare on the last N digits, matching BlocklistRepository's rule. */
        const val DIGIT_MATCH = 10

        /** Calls from one number, all unanswered, before it reads as a nuisance. */
        const val NUISANCE_MISSED = 3

        const val WEEK_DAYS = 7L
        const val MONTH_DAYS = 30L

        val SPAM_WORDS = listOf("spam", "scam", "fraud", "safe", "legit", "who is")
        val TOP_CALLER_WORDS = listOf("most", "top caller", "frequent", "who called me")
        val MISSED_WORDS = listOf("missed", "miss call", "not answer", "unanswered")
        val BLOCKED_WORDS = listOf("blocked", "blocklist", "block list", "blocking")
    }
}
