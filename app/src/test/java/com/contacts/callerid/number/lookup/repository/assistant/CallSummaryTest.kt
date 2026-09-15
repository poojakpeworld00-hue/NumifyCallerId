package com.contacts.callerid.number.lookup.repository.assistant

import com.contacts.callerid.number.lookup.repository.CallRecord
import com.contacts.callerid.number.lookup.repository.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The summary is assembled while the call log is still being written, so the
 * case that matters most is the call that just ended being counted once.
 */
class CallSummaryTest {

    private val now = 1_700_000_000_000L
    private val monthStart = now - TimeUnit.DAYS.toMillis(30)

    private fun call(
        agoMs: Long,
        type: CallType = CallType.INCOMING,
        durationSec: Long = 120
    ) = CallRecord(
        name = "Rahul",
        number = "+919820041255",
        type = type,
        date = now - agoMs,
        durationSec = durationSec
    )

    @Test
    fun `a call with no history is the first ever`() {
        val f = CallSummary.facts(emptyList(), durationSec = 90, monthStartMs = monthStart, nowMs = now)
        assertTrue(f.firstEverCall)
        assertEquals(1, f.callsThisMonth)
        assertFalse(f.longestYet)
    }

    @Test
    fun `the just-ended call is not double counted when the provider already wrote it`() {
        // The log row for the call in progress lands within the write window.
        val history = listOf(
            call(agoMs = 2_000),                            // the call that just ended
            call(agoMs = TimeUnit.DAYS.toMillis(3)),
            call(agoMs = TimeUnit.DAYS.toMillis(9))
        )
        val f = CallSummary.facts(history, durationSec = 200, monthStartMs = monthStart, nowMs = now)
        assertEquals(3, f.callsThisMonth)   // 2 previous + this one, not 4
        assertFalse(f.firstEverCall)
    }

    @Test
    fun `calls older than the month window are excluded from the count`() {
        val history = listOf(
            call(agoMs = TimeUnit.DAYS.toMillis(2)),
            call(agoMs = TimeUnit.DAYS.toMillis(45)),
            call(agoMs = TimeUnit.DAYS.toMillis(90))
        )
        val f = CallSummary.facts(history, durationSec = 60, monthStartMs = monthStart, nowMs = now)
        assertEquals(2, f.callsThisMonth)   // one recent + this one
        assertFalse(f.firstEverCall)        // but history is not empty
    }

    @Test
    fun `longest yet is true only when it beats every previous call`() {
        val history = listOf(
            call(agoMs = TimeUnit.DAYS.toMillis(1), durationSec = 100),
            call(agoMs = TimeUnit.DAYS.toMillis(4), durationSec = 250)
        )
        assertTrue(
            CallSummary.facts(history, 300, monthStart, now).longestYet
        )
        assertFalse(
            CallSummary.facts(history, 240, monthStart, now).longestYet
        )
    }

    @Test
    fun `a first ever call is never reported as the longest yet`() {
        val f = CallSummary.facts(emptyList(), durationSec = 999, monthStartMs = monthStart, nowMs = now)
        assertTrue(f.firstEverCall)
        assertFalse(f.longestYet)
    }

    @Test
    fun `an unanswered call is not the longest yet, whatever the history`() {
        val history = listOf(call(agoMs = TimeUnit.DAYS.toMillis(1), durationSec = 10))
        val f = CallSummary.facts(history, durationSec = 0, monthStartMs = monthStart, nowMs = now)
        assertFalse(f.longestYet)
    }

    @Test
    fun `unanswered counts only the missed rows`() {
        val history = listOf(
            call(agoMs = TimeUnit.DAYS.toMillis(1), type = CallType.MISSED),
            call(agoMs = TimeUnit.DAYS.toMillis(2), type = CallType.MISSED),
            call(agoMs = TimeUnit.DAYS.toMillis(3), type = CallType.INCOMING)
        )
        val f = CallSummary.facts(history, durationSec = 30, monthStartMs = monthStart, nowMs = now)
        assertEquals(2, f.unanswered)
    }
}
