package com.callora.callerid.numberlookup

/**
 * Decodes the XOR-encoded credentials embedded in BuildConfig as `byte[]` (see
 * app/build.gradle.kts) — the LightHouse push keys and the callerid.kpeworld.com
 * lookup credentials. `static final String` constants are inlined at every call
 * site by the compiler — leaking the key in a decompiled APK — whereas
 * `static final byte[]` are not. Decode at runtime, here.
 */
internal object CipherVeil {
    private const val K: Int = 0x5A

    fun s(b: ByteArray): String {
        val out = ByteArray(b.size)
        for (i in b.indices) out[i] = (b[i].toInt() xor K).toByte()
        return String(out, Charsets.UTF_8)
    }
}
