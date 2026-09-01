package com.numify.callerid.lookup.resolver

import android.content.Context
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import org.json.JSONObject

/**
 * Remote-Config view of the lookup API surface: base URL, the account path id,
 * and the two endpoint paths. It is parsed out of the `api_config` object exactly
 * as [com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig] parses
 * `screen` - held as text in [AdPreferenceStore] and read back with `org.json`.
 *
 * **Each field falls back to whatever used to be hardcoded**, so behaviour is
 * unchanged when `api_config` is missing, malformed, or simply not fetched yet.
 * Remote Config arrives asynchronously, so on a first cold launch these fallbacks
 * are what actually runs.
 *
 * Credentials are kept well away from here. `hash_key` and the bearer token stay
 * in [CredentialProvider], sourced from local.properties into an obfuscated
 * BuildConfig, because Remote Config is **publicly readable**: anyone holding the
 * values in `google-services.json` can pull this entire payload over HTTPS with
 * no authentication. Exposing URLs and a path id is harmless; exposing auth
 * material is not. See docs/credentials.md.
 */
object EndpointConfig {

    const val DEFAULT_BASE_URL = "https://callerid.kpeworld.com/"
    const val DEFAULT_PATH_SIMILAR = "api/similar-phone-number/"
    const val DEFAULT_PATH_SAVE_CONTACT = "/api/save_contact2"

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
        root(context).stringOr("lookup_base_url", DEFAULT_BASE_URL)
            .let { if (it.endsWith("/")) it else "$it/" }

    /**
     * Relative URL for the number lookup, resolved against [baseUrl] by Retrofit's
     * `@Url`. It falls back to [CredentialProvider.API_ID] when Remote Config
     * carries no override, so the account id keeps a single source of truth in
     * local.properties.
     */
    fun similarPhonePath(context: Context): String {
        val obj = root(context)
        val path = obj.stringOr("lookup_path_similar", DEFAULT_PATH_SIMILAR)
        val id = obj.stringOr("lookup_api_id", CredentialProvider.API_ID)
        return if (path.endsWith("/")) "$path$id" else "$path/$id"
    }

    /** Relative URL for the contact upload. */
    fun saveContactPath(context: Context): String =
        root(context).stringOr("lookup_path_save_contact", DEFAULT_PATH_SAVE_CONTACT)
}
