package com.contacts.callerid.number.lookup.repository.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * These thresholds decide what the app says about a stranger while the phone is
 * ringing, so the cases that matter most are the ones where it must stay quiet.
 */
class CallerRiskTest {

    @Test
    fun `a blocked number outranks everything else`() {
        assertEquals(
            CallerRisk.BLOCKED,
            CallerRisk.assess(blocked = true, knownContact = true, totalCalls = 9, unanswered = 9)
        )
    }

    @Test
    fun `a saved contact is never called a nuisance, however often they miss you`() {
        assertEquals(
            CallerRisk.KNOWN,
            CallerRisk.assess(blocked = false, knownContact = true, totalCalls = 8, unanswered = 8)
        )
    }

    @Test
    fun `three unanswered calls and nothing else reads as a nuisance`() {
        assertEquals(
            CallerRisk.NUISANCE,
            CallerRisk.assess(blocked = false, knownContact = false, totalCalls = 3, unanswered = 3)
        )
    }

    @Test
    fun `two unanswered calls is not enough — that is just a friend calling twice`() {
        assertEquals(
            CallerRisk.ORDINARY,
            CallerRisk.assess(blocked = false, knownContact = false, totalCalls = 2, unanswered = 2)
        )
    }

    @Test
    fun `one answered call among many clears the number`() {
        assertEquals(
            CallerRisk.ORDINARY,
            CallerRisk.assess(blocked = false, knownContact = false, totalCalls = 6, unanswered = 5)
        )
    }

    @Test
    fun `a number with no history is new, not suspicious`() {
        assertEquals(
            CallerRisk.FIRST_TIME,
            CallerRisk.assess(blocked = false, knownContact = false, totalCalls = 0, unanswered = 0)
        )
    }

    @Test
    fun `well past the threshold is still a nuisance`() {
        assertEquals(
            CallerRisk.NUISANCE,
            CallerRisk.assess(blocked = false, knownContact = false, totalCalls = 20, unanswered = 20)
        )
    }
}
