package com.numify.callerid.lookup.resolver

import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.SecretDecoder

/**
 * Credentials for the callerid.kpeworld.com API used by [NumberLookupService].
 *
 * These used to be plaintext `const val` literals in this file — committed to git
 * and trivially recoverable from any APK. They now live in `local.properties`
 * (gitignored, see docs/credentials.md), get XOR-obfuscated into `BuildConfig`
 * as `byte[]` at build time, and are decoded here at runtime — the same
 * treatment as the LightHouse key.
 *
 * `val` + `by lazy`, not `const val`: the compiler inlines `const` String
 * constants at every call site, which would put them straight back into the
 * decompiled output and defeat the point.
 *
 * **This raises the bar; it does not make the secret secret.** Anything shipped
 * in an APK is recoverable by a determined reader. The durable fix is server
 * side — an `exp` claim on the token (the current one has none) plus rate
 * limiting per account.
 */
object CredentialProvider {
    /** Path id for /api/similar-phone-number/{id}. */
    val API_ID: String by lazy { SecretDecoder.s(BuildConfig.LOOKUP_API_ID) }

    /** hash_key query parameter. */
    val API_HASH: String by lazy { SecretDecoder.s(BuildConfig.LOOKUP_API_HASH) }

    /** Authorization header value (already includes the "Bearer " prefix). */
    val API_TOKEN: String by lazy { SecretDecoder.s(BuildConfig.LOOKUP_API_TOKEN) }
}
