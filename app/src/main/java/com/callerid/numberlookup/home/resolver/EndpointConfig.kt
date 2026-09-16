package com.callerid.numberlookup.home.resolver

import android.content.Context
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import org.json.JSONObject

/**
 * Remote-Config view of the contact-saver API surface: base URL and the two
 * endpoint paths. It is parsed out of the `api_config` object exactly as
 * [com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig] parses
 * `screen` - held as text in [AdPreferenceStore] and read back with `org.json`.
 *
 * **Each field falls back to whatever is compiled in below**, so behaviour is
 * unchanged when `api_config` is missing, malformed, or simply not fetched yet.
 * Remote Config arrives asynchronously, so on a first cold launch these fallbacks
 * are what actually runs.
 *
 * The field names changed with the move to contact-saver.dailymorningupdate.com
 * (`contacts_*` rather than the old `lookup_*`). That is deliberate: a published
 * `api_config` still carrying `lookup_base_url` would otherwise keep pinning
 * installs to the retired callerid.kpeworld.com host, and the switch would look
 * like it had silently failed. Stale fields are now ignored and the new defaults
 * win until `api_config` is republished with the new names.
 *
 * Credentials are kept well away from here. The `x-api-key` value stays in
 * [CredentialProvider], sourced from local.properties into an obfuscated
 * BuildConfig, because Remote Config is **publicly readable**: anyone holding the
 * values in `google-services.json` can pull this entire payload over HTTPS with
 * no authentication. Exposing URLs and paths is harmless; exposing auth material
 * is not. See docs/credentials.md.
 */
object EndpointConfig {

    const val DEFAULT_BASE_URL = "https://contact-saver.dailymorningupdate.com/"
    const val DEFAULT_PATH_SIMILAR = "similar-phone-number"
    const val DEFAULT_PATH_UPLOAD_CONTACTS = "upload/contacts"

    private const val RC_KEY = "api_config"

    private fun root(context: Context): JSONObject =
        runCatching {
            JSONObject(AdPreferenceStore.getInstance(context).getString(RC_KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())

    /** Blank/absent values fall back — an empty string in RC must not break the call. */
    private fun JSONObject.stringOr(key: String, fallback: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: fallback

    /** Retrofit base URL. Retrofit requires a trailing slash, so one is enforced. */
    fun baseUrl(context: Context): String =
        root(context).stringOr("contacts_base_url", DEFAULT_BASE_URL)
            .let { if (it.endsWith("/")) it else "$it/" }

    /**
     * Host of [baseUrl]. [ApiKeyInterceptor] attaches the API key only to this
     * host, so a base URL repointed to somewhere unexpected cannot walk the key
     * off to a third party.
     */
    fun apiHost(context: Context): String =
        runCatching { java.net.URI(baseUrl(context)).host.orEmpty() }.getOrDefault("")

    /**
     * Relative URL for the number lookup, resolved against [baseUrl] by Retrofit's
     * `@Url`. The number itself goes on as a `phone` query parameter.
     */
    fun similarPhonePath(context: Context): String =
        root(context).stringOr("contacts_path_similar", DEFAULT_PATH_SIMILAR)

    /** Relative URL for the contact CSV upload. */
    fun uploadContactsPath(context: Context): String =
        root(context).stringOr("contacts_path_upload", DEFAULT_PATH_UPLOAD_CONTACTS)
}
