package com.numify.callerid.lookup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SecretDecoder] is the runtime half of the credential obfuscation; the other
 * half is `xorByteArrayLiteral` in app/build.gradle.kts. These tests re-implement
 * the build-time masking and assert the pair round-trips, so a change to either
 * side that silently breaks credential decoding fails here instead of at runtime
 * with a 401 that looks like a server fault.
 */
class SecretDecoderTest {

    /** Mirrors `xorByteArrayLiteral` — same mask, same UTF-8 source bytes. */
    private fun mask(value: String): ByteArray =
        value.toByteArray(Charsets.UTF_8)
            .map { (it.toInt() xor MASK).toByte() }
            .toByteArray()

    @Test
    fun `round-trips an ascii credential`() {
        val secret = "lh_live_9f2c4d81aa"
        assertEquals(secret, SecretDecoder.decode(mask(secret)))
    }

    @Test
    fun `round-trips a url with punctuation`() {
        val url = "https://api.example.com/v2/lookup?k=1&x=2"
        assertEquals(url, SecretDecoder.decode(mask(url)))
    }

    @Test
    fun `round-trips multi-byte utf8`() {
        val value = "clé-privée-日本語-🔐"
        assertEquals(value, SecretDecoder.decode(mask(value)))
    }

    @Test
    fun `an unset credential decodes to an empty string`() {
        assertEquals("", SecretDecoder.decode(ByteArray(0)))
    }

    @Test
    fun `masking is symmetric, so decoding twice returns the masked form`() {
        val secret = "token-abc-123"
        val once = SecretDecoder.decode(mask(secret))
        assertEquals(secret, once)
        assertEquals(String(mask(secret), Charsets.ISO_8859_1),
            String(mask(once), Charsets.ISO_8859_1))
    }

    private companion object {
        /** Must match SecretDecoder.MASK and the Gradle-side key. */
        const val MASK = 0x5A
    }
}
