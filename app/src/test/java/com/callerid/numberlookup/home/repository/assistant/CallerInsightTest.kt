package com.callerid.numberlookup.home.repository.assistant

import com.callerid.numberlookup.home.repository.CallRecord
import com.callerid.numberlookup.home.repository.CallType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Only one insight reaches the card, so the ranking *is* the feature. These pin
 * the order and the two thresholds.
 */
class CallerInsightTest {

    private val now = 1_700_000_000_000L

    private fun call(daysAgo: Long, type: CallType) = CallRecord(
        name = null,
        number = "+919820041255",
        type = type,
        date = now - TimeUnit.DAYS.toMillis(daysAgo),
        durationSec = if (type == CallType.MISSED) 0 else 60
    )

    private fun insight(
        history: List<CallRecord>,
        blocked: Boolean = false,
        known: Boolean = false
    ) = CallerInsight.of(history, blocked, known, now)

    @Test
    fun `blocked outranks everything`() {
        val history = List(5) { call(it.toLong(), CallType.MISSED) }
        assertEquals(CallerInsight.Blocked, insight(history, blocked = true, known = true))
    }

    @Test
    fun `an unknown number that never gets answered is a nuisance`() {
        val history = List(4) { call(it.toLong(), CallType.MISSED) }
        assertEquals(CallerInsight.Nuisance(4), insight(history))
    }

    @Test
    fun `a saved contact is never a nuisance — it reports the missed streak instead`() {
        val history = List(4) { call(it.toLong(), CallType.MISSED) }
        assertEquals(CallerInsight.MissedStreak(4), insight(history, known = true))
    }

    @Test
    fun `the streak counts only consecutive misses from the newest call`() {
        val history = listOf(
            call(0, CallType.MISSED),
            call(1, CallType.MISSED),
            call(2, CallType.INCOMING),
            call(3, CallType.MISSED)
        )
        assertEquals(CallerInsight.MissedStreak(2), insight(history, known = true))
    }

    @Test
    fun `one missed call is not a streak`() {
        val history = listOf(call(0, CallType.MISSED), call(1, CallType.INCOMING))
        assertEquals(CallerInsight.AnswerRate(2, 1), insight(history))
    }

    @Test
    fun `an answered call at the top breaks the streak entirely`() {
        val history = listOf(
            call(0, CallType.INCOMING),
            call(1, CallType.MISSED),
            call(2, CallType.MISSED)
        )
        assertEquals(CallerInsight.AnswerRate(3, 1), insight(history))
    }

    @Test
    fun `no history at all is a first-time caller`() {
        assertEquals(CallerInsight.FirstTime, insight(emptyList()))
    }

    @Test
    fun `a contact you speak to often is reported as frequent`() {
        val history = List(6) { call(it.toLong(), CallType.INCOMING) }
        assertEquals(CallerInsight.Frequent(6), insight(history, known = true))
    }

    @Test
    fun `a quiet contact gets no line rather than a filler one`() {
        val history = List(3) { call(it.toLong(), CallType.INCOMING) }
        assertEquals(CallerInsight.None, insight(history, known = true))
    }

    @Test
    fun `frequent counts only the last 30 days`() {
        val history = List(6) { call(40L + it, CallType.INCOMING) }
        assertEquals(CallerInsight.None, insight(history, known = true))
    }
}
