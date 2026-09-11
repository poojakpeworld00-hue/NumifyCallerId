package com.numify.callerid.lookup.repository.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure that matters here is the silent one. A question that classifies as
 * null is answered with "the assistant service is not switched on yet" — which
 * is correct for an open question and a bug report for a prompt the app itself
 * put on screen. The first block below is therefore the point of this file: it
 * pins the exact English of every shipped chip and follow-up against the
 * matcher, so a copy edit that drifts out of step fails here rather than on a
 * user's phone.
 */
class AskIntentTest {

    // --- every prompt the app offers must classify --------------------------

    /**
     * These are the literal values of R.string.ai_chip_* and R.string.ai_follow_*
     * with their placeholders filled, copied deliberately: a unit test cannot
     * read resources, and the pairing is what is being tested. Change one of
     * those strings and this test is the thing that has to change with it.
     */
    @Test
    fun `starter chips and follow-ups all resolve to an intent`() {
        val shipped = mapOf(
            // ai_chip_is_spam
            "Is +91 22 6544 7129 spam?" to AskIntent.SPAM,
            // ai_chip_who_keeps_calling
            "Who is +91 22 6544 7129 and why do they keep calling?" to AskIntent.SPAM,
            // ai_chip_reply_to
            "Reply to +912265447129" to AskIntent.REPLY,
            // ai_chip_summarise
            "Summarise my call with Asha" to AskIntent.SUMMARY,
            // ai_chip_this_week
            "What happened this week?" to AskIntent.WEEK,
            // ai_follow_top_caller
            "Who called me most this week?" to AskIntent.TOP_CALLER,
            // ai_follow_missed
            "How many calls did I miss?" to AskIntent.MISSED,
            // ai_follow_blocked_count
            "How many numbers have I blocked?" to AskIntent.BLOCKLIST,
            // ai_follow_who_else
            "Who else called from this area?" to AskIntent.AREA,
        )

        shipped.forEach { (prompt, expected) ->
            assertEquals("wrong intent for the shipped prompt: $prompt", expected, AskIntent.of(prompt))
        }
    }

    /**
     * The regression this file was written for: the prompt says "miss" and the
     * keyword list said "missed", so every tap on that chip fell through to the
     * not-configured message.
     */
    @Test
    fun `the missed-calls chip is not matched on the past tense alone`() {
        assertEquals(AskIntent.MISSED, AskIntent.of("How many calls did I miss?"))
        assertEquals(AskIntent.MISSED, AskIntent.of("how many calls did i miss"))
        assertEquals(AskIntent.MISSED, AskIntent.of("what did I miss today"))
    }

    // --- tie-breaks between overlapping words -------------------------------

    @Test
    fun `a weekly question that names a top caller is not the weekly digest`() {
        assertEquals(AskIntent.TOP_CALLER, AskIntent.of("Who called me most this week?"))
    }

    @Test
    fun `a who-is question about a number is a spam verdict, not a top caller`() {
        assertEquals(AskIntent.SPAM, AskIntent.of("who is 7016414568"))
    }

    @Test
    fun `blocklist beats the generic week net`() {
        assertEquals(AskIntent.BLOCKLIST, AskIntent.of("how many numbers did I block this week"))
    }

    @Test
    fun `a question about a day is not answered with a week`() {
        assertEquals(AskIntent.DAY, AskIntent.of("how many calls today"))
        assertEquals(AskIntent.DAY, AskIntent.of("what happened yesterday"))
        // "how many call" is in the week net, so only the day word separates
        // these two — and a weekly total given for today is a wrong answer.
        assertEquals(AskIntent.WEEK, AskIntent.of("how many calls this week"))
    }

    @Test
    fun `the length of a blocklist is not the length of a call`() {
        assertEquals(AskIntent.BLOCKLIST, AskIntent.of("how long is my blocklist"))
        assertEquals(AskIntent.SUMMARY, AskIntent.of("how long was my last call"))
    }

    // --- loose typed phrasings ----------------------------------------------

    @Test
    fun `everyday wordings reach the intent they mean`() {
        assertEquals(AskIntent.BLOCKLIST, AskIntent.of("block 9820041255"))
        assertEquals(AskIntent.SPAM, AskIntent.of("kaun hai ye number"))
        assertEquals(AskIntent.REPLY, AskIntent.of("send a message to asha"))
        assertEquals(AskIntent.TOP_CALLER, AskIntent.of("busiest caller"))
        assertEquals(AskIntent.MISSED, AskIntent.of("calls I did not pick"))
        assertEquals(AskIntent.WEEK, AskIntent.of("show my recent calls"))
    }

    // --- typed questions ----------------------------------------------------

    @Test
    fun `case and surrounding words do not matter`() {
        assertEquals(AskIntent.SPAM, AskIntent.of("IS THIS A SCAM"))
        assertEquals(AskIntent.BLOCKLIST, AskIntent.of("  show me my blocklist  "))
    }

    @Test
    fun `an unanswered-calls question reaches the missed intent`() {
        assertEquals(AskIntent.MISSED, AskIntent.of("how many unanswered calls do I have"))
        assertEquals(AskIntent.MISSED, AskIntent.of("which callers did not answer"))
    }

    // --- nothing on-device fits ---------------------------------------------

    @Test
    fun `an open question is left for the model`() {
        assertNull(AskIntent.of("what is the weather in mumbai"))
        assertNull(AskIntent.of("write me a poem"))
    }

    @Test
    fun `empty input classifies as nothing`() {
        assertNull(AskIntent.of(""))
        assertNull(AskIntent.of("   "))
    }

    @Test
    fun `every intent is reachable from some question`() {
        // Guards against an enum value that no keyword can ever select, which
        // would be a branch of the resolver nothing reaches.
        val reachable = listOf(
            "is this spam",
            "reply to him",
            "summarise my call with asha",
            "who called me most",
            "did I miss anything",
            "what is on my blocklist",
            "who else called from this area",
            "how many calls today",
            "what happened this week",
        ).mapNotNull { AskIntent.of(it) }.toSet()

        AskIntent.entries.forEach { intent ->
            assertTrue("no question selects $intent", intent in reachable)
        }
    }
}
