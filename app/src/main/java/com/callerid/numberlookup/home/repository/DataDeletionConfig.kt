package com.callerid.numberlookup.home.repository

import android.content.Context
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
 * **Each field falls back to the string resource below**, so the dialog is
 * complete and translated when `data_deletion` is missing, malformed, or simply
 * not fetched yet. Remote Config arrives asynchronously, so on a first cold
 * launch these fallbacks are what actually runs — and because they are string
 * resources they follow the user's language, which a published English blob
 * would not.
 *
 * Published shape:
 * ```json
 * "data_deletion": {
 *   "title":   "Delete your data?",
 *   "message": "This removes the data this app has stored about you. …",
 *   "confirm": "Delete",
 *   "cancel":  "Cancel",
 *   "toast":   "Deletion request sent"
 * }
 * ```
 */
object DataDeletionConfig {

    private const val RC_KEY = "data_deletion"

    private fun root(context: Context): JSONObject =
        runCatching {
            JSONObject(AdPreferenceStore.getInstance(context).getString(RC_KEY, "{}") ?: "{}")
        }.getOrDefault(JSONObject())

    /** Blank/absent values fall back — an empty string in RC must not blank the dialog. */
    private fun JSONObject.stringOr(key: String, fallback: String): String =
        optString(key).takeIf { it.isNotBlank() } ?: fallback

    fun title(context: Context): String =
        root(context).stringOr("title", context.getString(R.string.data_deletion_dialog_title))

    fun message(context: Context): String =
        root(context).stringOr("message", context.getString(R.string.data_deletion_dialog_body))

    fun confirm(context: Context): String =
        root(context).stringOr("confirm", context.getString(R.string.data_deletion_confirm))

    fun cancel(context: Context): String =
        root(context).stringOr("cancel", context.getString(R.string.data_deletion_cancel))

    fun toast(context: Context): String =
        root(context).stringOr("toast", context.getString(R.string.data_deletion_done))
}
