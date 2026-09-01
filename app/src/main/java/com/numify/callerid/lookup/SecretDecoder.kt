package com.numify.callerid.lookup

/**
 * Decodes the XOR-encoded credentials embedded in BuildConfig as `byte[]` (see
 * app/build.gradle.kts) — the LightHouse push keys and the number-lookup
 * credentials. `static final String` constants are inlined at every call site by
 * the compiler — leaking the key in a decompiled APK — whereas `static final
 * byte[]` are not. Decode at runtime, here.
 */
internal object SecretDecoder {

    /** Must match the mask used by `xorByteArrayLiteral` in app/build.gradle.kts. */
    private const val MASK: Int = 0x5A

    /**
     * Unmasks [encoded] and reads it back as UTF-8. Returns an empty string for
     * an empty array, which is what an unset credential compiles down to.
     */
    fun decode(encoded: ByteArray): String {
        if (encoded.isEmpty()) return ""
        return ByteArray(encoded.size) { index ->
            (encoded[index].toInt() xor MASK).toByte()
        }.toString(Charsets.UTF_8)
    }
}
