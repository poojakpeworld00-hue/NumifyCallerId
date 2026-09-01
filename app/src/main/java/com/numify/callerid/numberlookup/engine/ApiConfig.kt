package com.numify.callerid.numberlookup.engine

import android.content.Context
import com.numify.callerid.adkit.policy.AdsPrefStore
import org.json.JSONObject

/**
 * Remote-Config view of the callerid.kpeworld.com endpoints — base URL, the
 * account path id, and the two endpoint paths. Parsed from the `api_config`
 * object the same way [com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig]
 * reads `screen`: stored as text in [AdsPrefStore], read back with `org.json`.
 *
 * **Every field falls back to the value that used to be hardcoded**, so the app
 * behaves identically when `api_config` is absent, malformed, or simply hasn't
 * been fetched yet (Remote Config lands asynchronously — on a first cold launch
 * these fallbacks are what actually run).
 *
 * Credentials deliberately live elsewhere. `hash_key` and the bearer token stay
 * in [ApiSecrets] (local.properties → obfuscated BuildConfig) because Remote
 * Config is **publicly readable** — anyone with the values from
 * `google-services.json` can fetch this whole payload over HTTPS without
 * authenticating. URLs and a path id are fine to expose; auth material is not.
 * See docs/credentials.md.
 */
object ApiConfig {

    const val DEFAULT_BASE_URL = "https://callerid.kpeworld.com/"
    const val DEFAULT_PATH_SIMILAR = "api/similar-phone-number/"
    const val DEFAULT_PATH_SAVE_CONTACT = "/api/save_contact2"

    private const val RC_KEY = "api_config"

    private fun root(context: Context): JSONObject =
        runCatching {
            JSONObject(AdsPrefStore.getInstance(context).getString(RC_KEY, "{}") ?: "{}")
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
     * `@Url`. Falls back to [ApiSecrets.API_ID] when RC carries no override, so the
     * account id still has a single source of truth in local.properties.
     */
    fun similarPhonePath(context: Context): String {
        val obj = root(context)
        val path = obj.stringOr("lookup_path_similar", DEFAULT_PATH_SIMILAR)
        val id = obj.stringOr("lookup_api_id", ApiSecrets.API_ID)
        return if (path.endsWith("/")) "$path$id" else "$path/$id"
    }

    /** Relative URL for the contact upload. */
    fun saveContactPath(context: Context): String =
        root(context).stringOr("lookup_path_save_contact", DEFAULT_PATH_SAVE_CONTACT)
}
