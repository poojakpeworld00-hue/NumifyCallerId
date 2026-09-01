package com.callora.callerid.numberlookup.access

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.numberlookup.BuildConfig
import com.callora.callerid.numberlookup.kit.EdgeInsets
import org.json.JSONObject

/**
 * Single access point for the engine's configuration.
 *
 * Reads the `permission_engine` block from Firebase Remote Config and caches the
 * parsed [AccessRule]s in memory. Remote Config already persists activated
 * values to disk, so the last-known config is available immediately on the next
 * cold start — the engine works even before a fresh fetch completes.
 *
 * Config resolution order (first non-empty wins):
 *  1. A dedicated Remote Config parameter named `permission_engine`.
 *  2. The `permission_engine` key inside the app's existing data blob
 *     (`GET_DATA_LIST` / `DEBUG_GET_DATA_LIST`), so no new RC parameter is
 *     strictly required.
 */
object AccessRepository {

    private const val TAG = "AccessEngine"
    private const val RC_KEY = "permission_engine"

    /**
     * Compiled-in safety-net configuration. Used **only** when Remote Config
     * supplies no `permission_engine` value (before the first successful fetch,
     * or if the parameter is never set on the server). Any Remote Config value
     * completely overrides this.
     *
     * Notification + phone state are driven by the engine and triggered
     * explicitly — from the splash flow (AdHostActivity) and from the permission
     * bottom sheet's Continue button on HomeShellActivity. So the default targets
     * both `LaunchActivity` and `HomeShellActivity` with no delay (the trigger point
     * already picks the moment). Remote Config fully overrides this.
     */
    private const val DEFAULT_CONFIG = """
        {
          "permission_engine": {
            "notification": { "enabled": true, "activities": ["LaunchActivity", "HomeShellActivity"], "delay": 0, "priority": 1 },
            "phone_state":  { "enabled": true, "activities": ["LaunchActivity", "HomeShellActivity"], "delay": 0, "priority": 2 }
          }
        }
    """

    @Volatile
    private var cached: List<AccessRule>? = null

    /** Returns cached rules, parsing from Remote Config on first access. */
    fun rules(): List<AccessRule> = cached ?: reload()

    /** Re-reads (and re-parses) the currently activated Remote Config values. */
    @Synchronized
    fun reload(): List<AccessRule> {
        val parsed = RemoteAccessParser.parse(rawConfig())
        cached = parsed
        return parsed
    }

    /**
     * Triggers a fresh Remote Config fetch, then refreshes the cache. Safe to
     * call once at startup; failures fall back silently to cached/activated
     * values so the flow is never blocked.
     */
    fun refreshFromRemote(onReady: (() -> Unit)? = null) {
        try {
            val rc = FirebaseRemoteConfig.getInstance()
            val settings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(if (BuildConfig.DEBUG) 0 else 3600)
                .setFetchTimeoutInSeconds(10)
                .build()
            rc.setConfigSettingsAsync(settings)
            rc.fetchAndActivate().addOnCompleteListener { task ->
                EdgeInsets.log(TAG, "Remote Config fetch success=${task.isSuccessful}")
                reload()
                onReady?.invoke()
            }
        } catch (e: Exception) {
            EdgeInsets.error(TAG, "refreshFromRemote failed; using cached config", e)
            reload()
            onReady?.invoke()
        }
    }

    /** Resolves the raw JSON for the engine from Remote Config (see class doc). */
    private fun rawConfig(): String {
        return try {
            val rc = FirebaseRemoteConfig.getInstance()

            // 1) Dedicated parameter.
            rc.getString(RC_KEY).takeIf { it.isNotBlank() }?.let { return it }

            // 2) Nested inside the app's existing data blob.
            val blobKey = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"
            val blob = rc.getString(blobKey)
            if (blob.isNotBlank()) {
                val obj = JSONObject(blob)
                // Top-level audience split: descend into marketing/organic first,
                // then fall back to the flat top level (legacy config).
                val root = audienceRoot(obj)
                if (root.has(RC_KEY)) return root.getJSONObject(RC_KEY).toString()
                if (obj.has(RC_KEY)) return obj.getJSONObject(RC_KEY).toString()
            }

            // Nothing configured remotely → fall back to the compiled-in default.
            EdgeInsets.log(TAG, "No remote permission_engine config; using compiled-in default")
            DEFAULT_CONFIG
        } catch (e: Exception) {
            EdgeInsets.error(TAG, "Failed to read Remote Config; using compiled-in default", e)
            DEFAULT_CONFIG
        }
    }

    /** marketing/organic sub-object (by OnMaketing), else the flat blob. */
    private fun audienceRoot(obj: JSONObject): JSONObject {
        val isMarketing = AdsPrefStore.getOrNull()?.getBoolean("OnMaketing") ?: false
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        obj.optJSONObject(preferred)?.let { return it }
        obj.optJSONObject(fallback)?.let { return it }
        return obj
    }
}
