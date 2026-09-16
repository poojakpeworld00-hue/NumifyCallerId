package com.callerid.numberlookup.home.repository

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import org.json.JSONObject

/**
 * Remote-Config view of the data-deletion dialog's wording.
 *
 * Every line the dialog shows — its title, its body, both buttons and the
 * confirmation toast — is published rather than compiled, because this is the
 * copy a store review reads and it has to be changeable without a release. It
 * is parsed out of a `data_deletion` object exactly as [EndpointConfig] parses
 * `api_config`: held as text in [AdPreferenceStore] and read back with
 * `org.json`.
 *
 * ## Three places a line can come from
 *
 * Remote Config serves one value to every device regardless of locale, so a
 * published English blob would quietly overwrite eleven translations. Each line
 * is therefore resolved in order:
 *
 *  1. `data_deletion.<language>.<field>` — a per-language override, if published
 *  2. `data_deletion.<field>` — the blob's base value
 *  3. the string resource — which follows the user's language on its own
 *
 * Publish only what you want to change, in the languages you have it in;
 * everything else keeps the translation already in the APK. Publishing nothing
 * at all is a perfectly good state — the dialog is complete and translated
 * without it, which is also what runs on a first cold launch, before Remote
 * Config has arrived.
 *
 * Published shape:
 * ```json
 * "data_deletion": {
 *   "title":   "Delete your data?",
 *   "message": "This removes the data this app has stored about you. …",
 *   "confirm": "Delete",
 *   "cancel":  "Cancel",
 *   "toast":   "Deletion request sent",
 *   "hi": { "title": "…", "message": "…" },
 *   "es": { "title": "…", "message": "…" }
 * }
 * ```
 */
object DataDeletionConfig {

    private const val RC_KEY = "data_deletion"

    private fun root(context: Context): JSONObject =
        runCatching {
            JSONObject(AdPreferenceStore.getInstance(context).getString(RC_KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())

    /** The nested object for the display language, or an empty one. */
    private fun localised(context: Context): JSONObject {
        val language = ConfigurationCompat
            .getLocales(context.resources.configuration)
            .get(0)
            ?.language
            .orEmpty()
        if (language.isBlank()) return JSONObject()
        return root(context).optJSONObject(language) ?: JSONObject()
    }

    /**
     * Language override, then the blob's base value, then the resource.
     *
     * Blank counts as absent at every level: an empty string published by
     * accident must not blank the dialog.
     */
    private fun value(context: Context, key: String, fallback: String): String =
        localised(context).optString(key).takeIf { it.isNotBlank() }
            ?: root(context).optString(key).takeIf { it.isNotBlank() }
            ?: fallback

    fun title(context: Context): String =
        value(context, "title", context.getString(R.string.data_deletion_dialog_title))

    fun message(context: Context): String =
        value(context, "message", context.getString(R.string.data_deletion_dialog_body))

    fun confirm(context: Context): String =
        value(context, "confirm", context.getString(R.string.data_deletion_confirm))

    fun cancel(context: Context): String =
        value(context, "cancel", context.getString(R.string.data_deletion_cancel))

    fun toast(context: Context): String =
        value(context, "toast", context.getString(R.string.data_deletion_done))
}
