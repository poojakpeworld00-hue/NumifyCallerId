package com.numify.callerid.lookup.repository.assistant

import android.content.Context
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.entity.AiAction
import com.numify.callerid.lookup.entity.AiActionKind
import com.numify.callerid.lookup.entity.AiMessage
import com.numify.callerid.lookup.entity.AiSource
import com.numify.callerid.lookup.entity.AiSuggestion
import com.numify.callerid.lookup.repository.BlocklistRepository
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import java.util.Calendar
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
 * [AiAssistantRepository] to escalate to the model. Every prompt the app itself
 * offers — each chip from [com.numify.callerid.lookup.feature.assistant.AiSuggestionEngine]
 * and every follow-up built below — must resolve to an answer here: a canned
 * chip that lands on "the assistant service is not switched on yet" reads as a
 * broken feature rather than as a missing backend. That is what [AskIntent] and
 * the intent carried on each suggestion are for.
 */
class LocalIntentResolver(private val context: Context) {

    private val callLog = CallLogRepository(context)
    private val blocklist = BlocklistRepository(context)

    /**
     * @param intent what the caller already knows the question means — chips
     *   carry it, so their localized labels are never re-parsed. Null for typed
     *   text, which is classified here.
     * @param subject the number or contact name a chip is about, when it has one.
     */
    fun resolve(
        question: String,
        intent: AskIntent? = null,
        subject: String = ""
    ): AiMessage.Answer? {
        val q = question.lowercase(Locale.ROOT).trim()
        if (q.isEmpty()) return null

        val kind = intent ?: AskIntent.of(q) ?: return null
        return when (kind) {
            AskIntent.SPAM -> spamVerdict(q, subject)
            AskIntent.REPLY -> replyDraft(q, subject)
            AskIntent.SUMMARY -> callSummary(q, subject)
            AskIntent.TOP_CALLER -> topCaller()
            AskIntent.MISSED -> missedSummary()
            AskIntent.BLOCKLIST -> blocklistSummary(q, subject)
            AskIntent.AREA -> areaSummary(q, subject)
            AskIntent.DAY -> dayDigest(q)
            AskIntent.WEEK -> weekDigest()
        }
    }

    // --- intents ------------------------------------------------------------

    /**
     * "Is 98200 41255 spam?" — the verdict leans on three signals we already
     * hold: whether it is blocked, how often it called, and how often it was
     * answered. A number that rings repeatedly and is never picked up is the
     * shape of a nuisance caller even when nobody has reported it yet.
     *
     * Asked without a number ("am I getting spam calls?") it names the worst
     * offender in the log rather than giving up: the question is answerable, it
     * just has to choose its own subject.
     */
    private fun spamVerdict(q: String, subject: String): AiMessage.Answer {
        val history = calls()
        val number = numberIn(subject, q) ?: return worstNuisance(history)
        val digits = NumberInText.tail(number, DIGIT_MATCH)
        val forNumber = history.filter { it.number.filter(Char::isDigit).endsWith(digits) }

        // Report is deliberately absent for now: ReportNumberActivity needs a
        // full LookupVerdict, which only a network lookup produces. It joins
        // this row once the assistant can run a lookup of its own.
        val actions = listOf(
            AiAction(AiActionKind.BLOCK, number),
            AiAction(AiActionKind.DETAILS, number)
        )
        val followUps = listOf(
            followUp(R.string.ai_follow_who_else, AskIntent.AREA, number),
            followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
        )

        if (blocklist.isNumberBlocked(number)) {
            // Already blocked, so offering Block again would be a dead button.
            return answer(
                context.getString(R.string.ai_ans_spam_blocked, number, forNumber.size),
                actions.filterNot { it.kind == AiActionKind.BLOCK }, followUps
            )
        }

        val unanswered = forNumber.count { it.type == CallType.MISSED }
        val text = when {
            forNumber.isEmpty() ->
                context.getString(R.string.ai_ans_spam_unknown, number)
            unanswered >= NUISANCE_MISSED && unanswered == forNumber.size ->
                context.getString(R.string.ai_ans_spam_likely, forNumber.size)
            else ->
                context.getString(R.string.ai_ans_spam_clear, forNumber.size, forNumber.size - unanswered)
        }
        return answer(text, actions, followUps)
    }

    /** The unsaved number that rang most and was never once picked up. */
    private fun worstNuisance(history: List<CallRecord>): AiMessage.Answer {
        val worst = history
            .filter { it.name.isNullOrBlank() && it.number.isNotBlank() }
            .groupBy { it.number }
            .filterValues { group -> group.size >= NUISANCE_MISSED && group.all { it.type == CallType.MISSED } }
            .maxByOrNull { it.value.size }
            ?: return answer(context.getString(R.string.ai_ans_no_calls))

        return answer(
            context.getString(R.string.ai_ans_spam_worst, worst.key, worst.value.size),
            listOf(AiAction(AiActionKind.BLOCK, worst.key), AiAction(AiActionKind.DETAILS, worst.key)),
            listOf(
                followUp(R.string.ai_follow_who_else, AskIntent.AREA, worst.key),
                followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
            )
        )
    }

    /**
     * "Reply to Asha" — drafts the message and stops at the hand-off.
     *
     * With no subject it replies to the newest unanswered call, which is what
     * the chip means when it appears straight after one.
     */
    private fun replyDraft(q: String, subject: String): AiMessage.Answer {
        val history = calls()
        val target = subjectRecord(q, subject, history)
            ?: history.firstOrNull { it.type == CallType.MISSED }
            ?: history.firstOrNull()
            ?: return answer(context.getString(R.string.ai_ans_no_calls))

        val who = target.name?.takeIf(String::isNotBlank) ?: target.number
        return answer(
            context.getString(R.string.ai_ans_reply, who),
            listOf(
                AiAction(
                    kind = AiActionKind.MESSAGE,
                    number = target.number,
                    messageBody = context.getString(R.string.ai_draft_missed_reply)
                ),
                AiAction(AiActionKind.CALL, target.number)
            ),
            listOf(
                followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER)
            )
        )
    }

    /**
     * "Summarise my call with Asha".
     *
     * Built from call-log metadata only — see [CallSummary] for why there is no
     * transcript to summarise — and phrased with the same sentences the
     * post-call card uses, so the two surfaces never disagree about a caller.
     */
    private fun callSummary(q: String, subject: String): AiMessage.Answer {
        val history = calls()
        val target = subjectRecord(q, subject, history)
            ?: history.firstOrNull { it.durationSec > 0 }
            ?: return answer(context.getString(R.string.ai_ans_no_calls))

        val who = target.name?.takeIf(String::isNotBlank) ?: target.number
        val digits = NumberInText.tail(target.number, DIGIT_MATCH)
        val forNumber = history.filter { it.number.filter(Char::isDigit).endsWith(digits) }

        val facts = CallSummary.facts(
            // The call being summarised is already written to the log here, so
            // it is taken out before facts() adds it back — otherwise the call
            // is counted twice.
            history = forNumber.filterNot { it.date == target.date },
            durationSec = target.durationSec,
            monthStartMs = sinceDays(MONTH_DAYS),
            nowMs = System.currentTimeMillis()
        )

        val parts = buildList {
            if (facts.firstEverCall) {
                add(context.getString(R.string.ai_summary_first, who))
            } else {
                add(context.getString(R.string.ai_summary_frequency, who, facts.callsThisMonth))
            }
            if (facts.longestYet) add(context.getString(R.string.ai_summary_longest))
            if (facts.unanswered > 0) {
                add(context.getString(R.string.ai_summary_unanswered, facts.unanswered))
            }
        }

        return answer(
            parts.joinToString(" "),
            listOf(
                AiAction(AiActionKind.CALL, target.number),
                AiAction(AiActionKind.DETAILS, target.number)
            ),
            listOf(
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER),
                followUp(R.string.ai_follow_missed, AskIntent.MISSED)
            )
        )
    }

    /** "Who called me most this week?" */
    private fun topCaller(): AiMessage.Answer {
        val week = calls().filter { it.date >= sinceDays(WEEK_DAYS) }
        val busiest = week.groupBy { it.number }.maxByOrNull { it.value.size }
            ?: return answer(context.getString(R.string.ai_ans_no_calls))
        val (number, records) = busiest
        val label = records.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) } ?: number

        return answer(
            context.getString(R.string.ai_ans_top_caller, label, records.size, week.size),
            listOf(AiAction(AiActionKind.CALL, number), AiAction(AiActionKind.DETAILS, number)),
            listOf(
                followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
            )
        )
    }

    /** "How many calls did I miss?" - plus a reply hand-off for the newest one. */
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
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER),
                followUp(R.string.ai_follow_who_else, AskIntent.AREA, newest?.number.orEmpty())
            )
        )
    }

    /**
     * "How many numbers have I blocked?" — and, when the question names a
     * number, the far more likely reading of it: "block this one".
     *
     * The block itself stays behind the button rather than happening here. An
     * answer that had already blocked a number by the time the user read it
     * would be the one action in this screen with no way back.
     */
    private fun blocklistSummary(q: String, subject: String): AiMessage.Answer {
        numberIn(subject, q)?.let { number ->
            if (blocklist.isNumberBlocked(number)) {
                val calls = calls().count {
                    it.number.filter(Char::isDigit).endsWith(NumberInText.tail(number, DIGIT_MATCH))
                }
                return answer(
                    context.getString(R.string.ai_ans_spam_blocked, number, calls),
                    listOf(AiAction(AiActionKind.DETAILS, number)),
                    listOf(
                        followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST),
                        followUp(R.string.ai_follow_missed, AskIntent.MISSED)
                    )
                )
            }
            return answer(
                context.getString(R.string.ai_ans_block_offer, number),
                listOf(AiAction(AiActionKind.BLOCK, number), AiAction(AiActionKind.DETAILS, number)),
                listOf(
                    followUp(R.string.ai_follow_who_else, AskIntent.AREA, number),
                    followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
                )
            )
        }

        val entries = blocklist.getEntries()
        if (entries.isEmpty()) return answer(context.getString(R.string.ai_ans_blocklist_empty))

        val recent = entries.count { it.addedAt >= sinceDays(MONTH_DAYS) }
        return answer(
            context.getString(R.string.ai_ans_blocklist, entries.size, recent),
            emptyList(),
            listOf(
                followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER)
            )
        )
    }

    /**
     * "Who else called from this area?"
     *
     * The range is the leading [AREA_DIGITS] of the national part — the last ten
     * digits — so "+91 22 6544 7129" and "022 6544 7129" fall in the same range.
     * That is a heuristic about the shape of a number rather than a lookup of a
     * real dialling area, so the answer names the prefix it used instead of
     * claiming a place.
     */
    private fun areaSummary(q: String, subject: String): AiMessage.Answer {
        val history = calls()
        val seed = numberIn(subject, q)
            ?: subjectRecord(q, subject, history)?.number
            ?: history.firstOrNull { it.name.isNullOrBlank() }?.number
            ?: return answer(context.getString(R.string.ai_ans_no_calls))

        val prefix = areaPrefix(seed)
        if (prefix.isBlank()) return answer(context.getString(R.string.ai_ans_no_calls))

        val seedTail = NumberInText.tail(seed, DIGIT_MATCH)
        val month = history
            .filter { it.date >= sinceDays(MONTH_DAYS) }
            .filter { areaPrefix(it.number) == prefix }
            .filterNot { NumberInText.tail(it.number, DIGIT_MATCH) == seedTail }

        if (month.isEmpty()) {
            return answer(
                context.getString(R.string.ai_ans_area_none, prefix),
                emptyList(),
                listOf(
                    followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                    followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
                )
            )
        }

        return answer(
            context.getString(
                R.string.ai_ans_area,
                month.size,
                prefix,
                month.distinctBy { NumberInText.tail(it.number, DIGIT_MATCH) }.size
            ),
            emptyList(),
            listOf(
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER),
                followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
            )
        )
    }

    /**
     * "How many calls today?" — a day rather than a week, because a question
     * about today answered with a weekly total is a wrong answer delivered
     * confidently.
     *
     * The boundary is local midnight, not the last 24 hours: "today" means the
     * calendar day to the person asking at 9am.
     */
    private fun dayDigest(q: String): AiMessage.Answer {
        val yesterday = YESTERDAY_WORD in q
        val midnight = startOfToday()
        val from = if (yesterday) midnight - TimeUnit.DAYS.toMillis(1) else midnight
        val until = if (yesterday) midnight else Long.MAX_VALUE

        val day = calls().filter { it.date >= from && it.date < until }
        if (day.isEmpty()) {
            return answer(
                context.getString(
                    if (yesterday) R.string.ai_ans_yesterday_none else R.string.ai_ans_today_none
                ),
                emptyList(),
                listOf(
                    followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                    followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER)
                )
            )
        }

        val missed = day.count { it.type == CallType.MISSED }
        val unknown = day.count { it.name.isNullOrBlank() }
        return answer(
            context.getString(
                if (yesterday) R.string.ai_ans_yesterday else R.string.ai_ans_today,
                day.size, missed, unknown
            ),
            emptyList(),
            listOf(
                followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                followUp(R.string.ai_follow_top_caller, AskIntent.TOP_CALLER)
            )
        )
    }

    /** "What happened this week?" */
    private fun weekDigest(): AiMessage.Answer {
        val week = calls().filter { it.date >= sinceDays(WEEK_DAYS) }
        val busiest = week.groupBy { it.number }.maxByOrNull { it.value.size }
            ?: return answer(context.getString(R.string.ai_ans_no_calls))

        val missed = week.count { it.type == CallType.MISSED }
        val unknown = week.count { it.name.isNullOrBlank() }
        val who = busiest.value.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
            ?: busiest.key

        return answer(
            context.getString(R.string.ai_ans_week, week.size, missed, unknown, who),
            listOf(AiAction(AiActionKind.DETAILS, busiest.key)),
            listOf(
                followUp(R.string.ai_follow_missed, AskIntent.MISSED),
                followUp(R.string.ai_follow_blocked_count, AskIntent.BLOCKLIST)
            )
        )
    }

    // --- helpers ------------------------------------------------------------

    private fun answer(
        text: String,
        actions: List<AiAction> = emptyList(),
        followUps: List<AiSuggestion> = emptyList()
    ) = AiMessage.Answer(text, actions, followUps, AiSource.LOCAL)

    /**
     * A follow-up chip. It carries its [intent] and [subject] for the same
     * reason the starter chips do: the label is localized, so the answer it
     * produces must not depend on reading that label back.
     */
    private fun followUp(resId: Int, intent: AskIntent, subject: String = ""): AiSuggestion {
        val text = context.getString(resId)
        return AiSuggestion(
            label = text,
            query = text,
            priority = 0,
            intent = intent,
            subject = subject
        )
    }

    /** A dialable number from the chip's subject first, then the question text. */
    private fun numberIn(subject: String, q: String): String? =
        NumberInText.find(subject) ?: NumberInText.find(q)

    /**
     * The call a question is about. A chip's subject is whatever the hub showed
     * the user — a saved name as often as a number — so both are matched, and a
     * number with no history still resolves, so the actions have a target.
     */
    private fun subjectRecord(q: String, subject: String, history: List<CallRecord>): CallRecord? {
        numberIn(subject, q)?.let { number ->
            val digits = NumberInText.tail(number, DIGIT_MATCH)
            return history.firstOrNull { it.number.filter(Char::isDigit).endsWith(digits) }
                ?: CallRecord(
                    name = null,
                    number = number,
                    type = CallType.INCOMING,
                    date = 0L,
                    durationSec = 0L
                )
        }

        val named = subject.trim().lowercase(Locale.ROOT).takeIf { it.isNotEmpty() }
        if (named != null) {
            history.firstOrNull { it.name?.lowercase(Locale.ROOT) == named }?.let { return it }
        }
        // A typed question carries the name inside the sentence rather than on
        // its own: "summarise my call with asha".
        return history.firstOrNull { record ->
            val name = record.name?.takeIf { it.length >= MIN_NAME_MATCH } ?: return@firstOrNull false
            name.lowercase(Locale.ROOT) in q
        }
    }

    /** Leading digits of the national part, which is what groups a range. */
    private fun areaPrefix(number: String): String =
        NumberInText.tail(number, DIGIT_MATCH).take(AREA_DIGITS)

    /**
     * The call log throws rather than returning empty when READ_CALL_LOG is not
     * held, and the assistant is reachable before the permission sheet has been
     * satisfied — so a refusal degrades to "no history" instead of a crash.
     */
    private fun calls(): List<CallRecord> =
        runCatching { callLog.getCalls() }.getOrDefault(emptyList())

    private fun sinceDays(days: Long): Long =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days)

    /** Local midnight, so a day is the calendar day and not a rolling window. */
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private companion object {
        /** Lower-cased by [resolve] before any of this runs. */
        const val YESTERDAY_WORD = "yesterday"

        /** Compare on the last N digits, matching BlocklistRepository's rule. */
        const val DIGIT_MATCH = 10

        /** Calls from one number, all unanswered, before it reads as a nuisance. */
        const val NUISANCE_MISSED = 3

        /** Leading digits of the national part that define a range. */
        const val AREA_DIGITS = 4

        /** Shorter names match too much of a sentence to identify a subject. */
        const val MIN_NAME_MATCH = 3

        const val WEEK_DAYS = 7L
        const val MONTH_DAYS = 30L
    }
}
