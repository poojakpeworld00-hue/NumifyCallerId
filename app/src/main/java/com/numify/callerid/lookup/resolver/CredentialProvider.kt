package com.numify.callerid.lookup.resolver

import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.SecretDecoder

/**
 * Credentials for the lookup API consumed by [NumberLookupService].
 *
 * They were once plaintext `const val` literals in this file, committed to git
 * and trivially lifted from any APK. They now live in `local.properties`, which
 * is gitignored (see docs/credentials.md), are XOR-obfuscated into `BuildConfig`
 * as `byte[]` at build time, and are decoded here at runtime - the same handling
 * the LightHouse key gets.
 *
 * They are declared `val` with `by lazy` rather than `const val` on purpose: the
 * compiler inlines `const` String constants at every call site, which would put
 * them right back into the decompiled output and undo the whole exercise.
 *
 * **This raises the bar; it does not make the secret secret.** Anything shipped
 * inside an APK can be recovered by a determined reader. The lasting fix is on
 * the server: an `exp` claim on the token, which the current one lacks, together
 * with per-account rate limiting.
 */
object CredentialProvider {
    /** Path id for /api/similar-phone-number/{id}. */
    val API_ID: String by lazy { SecretDecoder.decode(BuildConfig.LOOKUP_API_ID) }

    /** hash_key query parameter. */
    val API_HASH: String by lazy { SecretDecoder.decode(BuildConfig.LOOKUP_API_HASH) }

    /** Authorization header value (already includes the "Bearer " prefix). */
    val API_TOKEN: String by lazy { SecretDecoder.decode(BuildConfig.LOOKUP_API_TOKEN) }
}
