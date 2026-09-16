package com.callerid.numberlookup.home.repository.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The failure that matters here is a false positive. If a quantity in an
 * ordinary question is read as a phone number, [LocalIntentResolver] routes the
 * whole question into the spam-verdict branch and answers confidently about a
 * number the user never mentioned. These tests pin that boundary.
 */
class NumberInTextTest {

    // --- should find a number ----------------------------------------------

    @Test
    fun `finds a plain ten-digit number`() {
        assertEquals("7016414568", NumberInText.find("is 7016414568 spam?"))
    }

    @Test
    fun `finds a spaced number with a country code`() {
        assertEquals("+91 70164 14568", NumberInText.find("is +91 70164 14568 spam?"))
    }

    @Test
    fun `finds a number written with dashes`() {
        assertEquals("988-200-4125", NumberInText.find("who is 988-200-4125"))
    }

    @Test
    fun `finds a number written with brackets`() {
        assertEquals("(020) 7946 0958", NumberInText.find("is (020) 7946 0958 a scam"))
    }

    // --- should NOT find a number ------------------------------------------

    @Test
    fun `ignores a small quantity`() {
        assertNull(NumberInText.find("who called me most in the last 7 days"))
    }

    @Test
    fun `ignores several small quantities`() {
        assertNull(NumberInText.find("show my top 3 callers from the last 30 days"))
    }

    @Test
    fun `ignores a year`() {
        assertNull(NumberInText.find("how many spam calls since 2024"))
    }

    @Test
    fun `ignores a question with no digits at all`() {
        assertNull(NumberInText.find("how many numbers have I blocked?"))
    }

    @Test
    fun `ignores a six-digit run, one short of the floor`() {
        assertNull(NumberInText.find("reference 123456 please"))
    }

    // --- tail comparison ----------------------------------------------------

    @Test
    fun `tail strips formatting so one number has one identity`() {
        assertEquals("7016414568", NumberInText.tail("+91 70164 14568"))
        assertEquals("7016414568", NumberInText.tail("7016414568"))
        assertEquals("7016414568", NumberInText.tail("(701) 641-4568"))
    }

    @Test
    fun `tail of a short number returns what there is, without padding`() {
        assertEquals("12345", NumberInText.tail("12345"))
    }
}
