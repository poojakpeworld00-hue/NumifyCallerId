package com.contacts.callerid.number.lookup.repository.assistant

import java.util.Locale

/**
 * What a question wants, separated from the words used to ask it.
 *
 * Split out of [LocalIntentResolver] for the same reason [NumberInText] was: it
 * is pure, so it is covered by JVM tests, and it is the part that silently
 * decides whether the assistant answers at all. A question that classifies as
 * null falls through to "the assistant service is not switched on yet" — so a
 * keyword list that drifts out of step with the prompts the app itself offers
 * reads to the user as a broken feature rather than a missing word.
 *
 * [of] matches English only. That is deliberate and it is why chips carry their
 * intent in [com.contacts.callerid.number.lookup.entity.AiSuggestion.intent] instead of
 * being re-parsed from their label: every prompt the app offers is translated
 * into ten locales, so matching on display text answers nothing on a device set
 * to anything but English. Typed questions still come through here, which is the
 * known limit of the on-device path.
 */
enum class AskIntent {
    /** "Is 98200 41255 spam?" — a verdict on one number. */
    SPAM,

    /** "Reply to Asha" — a drafted message hand-off. */
    REPLY,

    /** "Summarise my call with Asha" — what the log says about a finished call. */
    SUMMARY,

    /** "Who called me most this week?" */
    TOP_CALLER,

    /** "How many calls did I miss?" */
    MISSED,

    /** "How many numbers have I blocked?" */
    BLOCKLIST,

    /** "Who else called from this area?" */
    AREA,

    /** "How many calls today?" — today or yesterday, as a day rather than a week. */
    DAY,

    /** "What happened this week?" */
    WEEK;

    companion object {

        /** The intent behind [question], or null when nothing on-device fits. */
        fun of(question: String): AskIntent? {
            val q = question.lowercase(Locale.ROOT).trim()
            if (q.isEmpty()) return null
            return KEYWORDS.firstOrNull { (_, words) -> words.any { it in q } }?.first
        }

        /**
         * Ordered, and the order carries the tie-breaks: several of these words
         * co-occur in one question and the first match wins.
         *
         * - SPAM before TOP_CALLER, so "who is 98200 41255" is not read as
         *   "who called me most".
         * - TOP_CALLER before WEEK, because "who called me most this week"
         *   contains "this week" and is not the weekly digest.
         * - MISSED and DAY before WEEK, so "how many calls did I miss" and
         *   "how many calls today" are not both swallowed by "how many calls".
         * - WEEK last, as the widest net of the set.
         *
         * The lists are wider than the app's own prompts on purpose. Anything
         * that lands nowhere is answered with "not switched on yet", which is
         * an honest sentence and a disappointing one — so a phrasing that the
         * call log can in fact settle belongs here rather than at the wall.
         */
        private val KEYWORDS: List<Pair<AskIntent, List<String>>> = listOf(
            SPAM to listOf(
                "spam", "scam", "fraud", "safe", "legit", "who is", "keep calling", "keeps calling",
                "fake", "robocall", "telemarket", "suspicious", "unknown number", "kaun"
            ),
            // "block" covers blocked, blocklist, block list and blocking in one
            // word, and SPAM has already had its turn — so "is it spam, should
            // I block it" still reads as the verdict it is asking for. It sits
            // above SUMMARY so that "how long is my blocklist" is not read as a
            // question about the length of a call.
            BLOCKLIST to listOf("block"),
            REPLY to listOf(
                "reply", "respond", "text back", "message back", "get back to",
                "message to", "sms to", "text to", "send a message"
            ),
            SUMMARY to listOf(
                "summarise", "summarize", "summary", "call with", "how did my call",
                "how long", "last call with", "about my call"
            ),
            AREA to listOf("area", "same code", "this code", "prefix", "same area", "starting with"),
            TOP_CALLER to listOf(
                "most", "top caller", "frequent", "who called me", "who calls me",
                "most called", "busiest", "top number"
            ),
            // "miss" rather than "missed": the app's own prompt is "How many
            // calls did I miss?", which the longer form does not match. That
            // exact gap is what made every tap on that chip fall through.
            MISSED to listOf(
                "miss", "not answer", "no answer", "unanswered", "did not pick",
                "didn't pick", "not pick"
            ),
            DAY to listOf("today", "yesterday", "last 24"),
            WEEK to listOf(
                "this week", "what happened", "week", "recent call", "how many call",
                "call log", "overview", "digest"
            ),
        )
    }
}
