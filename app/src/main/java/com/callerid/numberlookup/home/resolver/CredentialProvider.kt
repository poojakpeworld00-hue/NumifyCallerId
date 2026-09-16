package com.callerid.numberlookup.home.resolver

import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.SecretDecoder

/**
 * The API key for the contact-saver service consumed by [NumberLookupService].
 *
 * It lives in `local.properties`, which is gitignored (see docs/credentials.md),
 * is XOR-obfuscated into `BuildConfig` as a `byte[]` at build time, and is
 * decoded here at runtime — the same handling the LightHouse key gets. It is
 * attached by [ApiKeyInterceptor] as the `x-api-key` header, never as a query
 * parameter: query strings end up in server access logs, proxy logs and browser
 * history in a way headers do not.
 *
 * Declared `val` with `by lazy` rather than `const val` on purpose: the compiler
 * inlines `const` String constants at every call site, which would put the key
 * right back into the decompiled output and undo the whole exercise.
 *
 * **This raises the bar; it does not make the secret secret.** Anything shipped
 * inside an APK can be recovered by a determined reader. The lasting protection
 * is on the server: per-key rate limiting and a key that can be revoked.
 *
 * The key deliberately does **not** come from Remote Config, even though that
 * would allow rotation without a release. Remote Config is publicly readable —
 * anyone holding the values in `google-services.json` can pull the whole payload
 * over HTTPS with no authentication — so a key placed there would be easier to
 * lift than one baked into the APK, not harder. URLs and paths live there;
 * credentials do not. See [EndpointConfig].
 */
object CredentialProvider {

    /** `x-api-key` header value for contact-saver.dailymorningupdate.com. */
    val CONTACTS_API_KEY: String by lazy { SecretDecoder.decode(BuildConfig.CONTACTS_API_KEY) }
}
