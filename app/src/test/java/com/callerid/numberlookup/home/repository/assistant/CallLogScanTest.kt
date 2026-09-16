package com.callerid.numberlookup.home.repository.assistant

import com.callerid.numberlookup.home.repository.CallRecord
import com.callerid.numberlookup.home.repository.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Both screens end in a bulk action, so a wrong rule here blocks someone the
 * user wanted to hear from, or nags them about a number they deliberately never
 * saved. These pin the thresholds and the exclusions.
 */
class CallLogScanTest {

    private val now = 1_700_000_000_000L
    private val none: (String) -> Boolean = { false }

    private fun calls(number: String, count: Int, type: CallType, startDaysAgo: Long = 0) =
        List(count) { i ->
            CallRecord(
                name = null,
                number = number,
                type = type,
                date = now - TimeUnit.DAYS.toMillis(startDaysAgo + i),
                durationSec = if (type == CallType.MISSED) 0 else 45
            )
        }

    // --- spam candidates ----------------------------------------------------

    @Test
    fun `an unknown number that rang four times unanswered is a candidate`() {
        val result = CallLogScan.spamCandidates(calls("+911", 4, CallType.MISSED), none, none, now)
        assertEquals(1, result.size)
        assertEquals(4, result.first().unanswered)
    }

    @Test
    fun `below the call floor it is not proposed`() {
        val result = CallLogScan.spamCandidates(calls("+911", 2, CallType.MISSED), none, none, now)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a saved contact is never a spam candidate`() {
        val result = CallLogScan.spamCandidates(
            calls("+911", 6, CallType.MISSED), isBlocked = none, isKnownContact = { true }, nowMs = now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an already blocked number is excluded, since Block would be a dead row`() {
        val result = CallLogScan.spamCandidates(
            calls("+911", 6, CallType.MISSED), isBlocked = { true }, isKnownContact = none, nowMs = now
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an unknown number you have answered is not a scanner result`() {
        val history = calls("+911", 4, CallType.INCOMING)
        assertTrue(CallLogScan.spamCandidates(history, none, none, now).isEmpty())
    }

    @Test
    fun `worst offender sorts first`() {
        val history = calls("+911", 3, CallType.MISSED) + calls("+922", 7, CallType.MISSED)
        val result = CallLogScan.spamCandidates(history, none, none, now)
        assertEquals("+922", result.first().number)
        assertEquals(2, result.size)
    }

    // --- unsaved callers ----------------------------------------------------

    @Test
    fun `a number you speak to often but never saved is surfaced`() {
        val result = CallLogScan.unsavedCallers(calls("+911", 5, CallType.INCOMING), none, none)
        assertEquals(1, result.size)
        assertEquals(5, result.first().calls)
    }

    @Test
    fun `below the call floor it is left alone`() {
        assertTrue(CallLogScan.unsavedCallers(calls("+911", 3, CallType.INCOMING), none, none).isEmpty())
    }

    @Test
    fun `a number that only ever rang unanswered is not a save suggestion`() {
        assertTrue(CallLogScan.unsavedCallers(calls("+911", 9, CallType.MISSED), none, none).isEmpty())
    }

    @Test
    fun `an already saved contact is excluded`() {
        val result = CallLogScan.unsavedCallers(
            calls("+911", 9, CallType.INCOMING), isKnownContact = { true }, isBlocked = none
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a blocked number is never suggested for saving`() {
        val result = CallLogScan.unsavedCallers(
            calls("+911", 9, CallType.INCOMING), isKnownContact = none, isBlocked = { true }
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a number the scanner would flag is never also a save suggestion`() {
        // Seen on a real device: 6 calls, 1 answered appeared in BOTH tools, so
        // the app advised blocking and saving the same number at once.
        val history = calls("+911", 5, CallType.MISSED) + calls("+911", 1, CallType.INCOMING, 5)
        assertTrue(CallLogScan.unsavedCallers(history, none, none).isEmpty())
        assertTrue(CallLogScan.spamCandidates(history, none, none, now).isNotEmpty())
    }

    @Test
    fun `answering exactly half is enough to suggest saving`() {
        val history = calls("+911", 3, CallType.INCOMING) + calls("+911", 3, CallType.MISSED, 3)
        assertEquals(1, CallLogScan.unsavedCallers(history, none, none).size)
    }

    @Test
    fun `most contacted sorts first`() {
        val history = calls("+911", 4, CallType.INCOMING) + calls("+922", 8, CallType.OUTGOING)
        assertEquals("+922", CallLogScan.unsavedCallers(history, none, none).first().number)
    }
}
