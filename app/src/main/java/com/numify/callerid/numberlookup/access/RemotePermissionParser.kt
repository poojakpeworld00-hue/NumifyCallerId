package com.numify.callerid.numberlookup.access

import com.numify.callerid.numberlookup.kit.WindowInsetsHelper
import org.json.JSONObject

/**
 * Parses the `permission_engine` Remote Config JSON into [PermissionRule]s.
 *
 * Accepts either shape, so it works whether the value is stored as its own
 * Remote Config parameter or nested inside a larger config blob:
 *
 * Wrapped:
 * ```
 * { "permission_engine": { "notification": { ... }, "phone_state": { ... } } }
 * ```
 * Unwrapped (the object itself):
 * ```
 * { "notification": { ... }, "phone_state": { ... } }
 * ```
 *
 * Malformed input never throws — it logs and returns an empty list so the app
 * simply behaves as if no permissions were configured.
 */
object RemotePermissionParser {

    private const val TAG = "PermissionCoordinator"
    private const val ROOT_KEY = "permission_engine"

    fun parse(json: String?): List<PermissionRule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(json)
            // Tolerate both wrapped and unwrapped shapes.
            val engine = root.optJSONObject(ROOT_KEY) ?: root

            val rules = ArrayList<PermissionRule>()
            val keys = engine.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = engine.optJSONObject(key) ?: continue

                val activitiesArr = obj.optJSONArray("activities")
                val activities = if (activitiesArr != null) {
                    (0 until activitiesArr.length())
                        .mapNotNull { activitiesArr.optString(it).takeIf(String::isNotBlank) }
                } else emptyList()

                rules += PermissionRule(
                    key = key,
                    enabled = obj.optBoolean("enabled", false),
                    activities = activities,
                    delayMs = obj.optLong("delay", 0L),
                    priority = obj.optInt("priority", Int.MAX_VALUE),
                    showOnce = obj.optBoolean("show_once", obj.optBoolean("showOnce", false)),
                )
            }
            WindowInsetsHelper.log(TAG, "Parsed ${rules.size} permission rule(s) from Remote Config")
            rules
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "Failed to parse permission_engine config", e)
            emptyList()
        }
    }
}
