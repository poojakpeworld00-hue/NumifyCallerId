package com.callerid.numberlookup.home.entity

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsed through Gson, as Retrofit does, from responses the live API returned. */
class LookupPayloadTest {

    private fun parse(json: String) = Gson().fromJson(json, LookupPayload::class.java)

    private val delhi = """
        {"success":true,"count":12,"country":"IN",
         "location":{"city":"Delhi","state":"Delhi","pincode":"110007"},
         "spam":false,"spamCount":0,
         "data":[{"name":"Dev Jyoti"},{"name":"MR DEEPAK"},{"name":"MR. DEEPAK"},
                 {"name":"+919876543210"},{"name":"9876543210"},{"name":"98"},
                 {"name":"+91 98765"},{"name":"Llllll"},{"name":"इंग्लैंड England"},
                 {"name":"  Rohit   Bhai "},{"name":""},{"name":null}]}
    """.trimIndent()

    @Test
    fun `top-level verdict is read`() {
        val p = parse(delhi)
        assertEquals("Delhi", p.city)
        assertEquals("IN", p.country)
        assertTrue(p.countryName!!.isNotBlank())
        assertFalse(p.isSpam)
    }

    @Test
    fun `junk names are dropped and duplicates folded`() {
        assertEquals(
            listOf("Dev Jyoti", "MR DEEPAK", "इंग्लैंड England", "Rohit Bhai"),
            parse(delhi).meaningfulNames()
        )
        assertEquals("Dev Jyoti", parse(delhi).primaryName())
    }

    @Test
    fun `spam flag and count reach the merged facts`() {
        val p = parse(
            """{"success":true,"count":1,"country":"IN","location":{"city":null},
               "spam":true,"spamCount":37,"data":[{"name":"Loan Offer"}]}"""
        )
        val facts = p.toFacts()!!
        assertTrue(facts.is_spam)
        assertEquals(37, facts.spamReportCounter)
        assertEquals("Loan Offer", facts.name)
        assertNull(facts.city)
    }

    @Test
    fun `spam with no usable name still yields facts`() {
        val facts = parse(
            """{"success":true,"count":1,"spam":true,"spamCount":5,"data":[{"name":"98"}]}"""
        ).toFacts()!!
        assertNull(facts.name)
        assertTrue(facts.is_spam)
    }

    @Test
    fun `nothing known gives no facts`() {
        assertNull(parse("""{"success":true,"count":0,"data":[]}""").toFacts())
        assertNull(parse("""{"success":true,"count":0,"location":{"city":null},"data":null}""").toFacts())
    }
}
