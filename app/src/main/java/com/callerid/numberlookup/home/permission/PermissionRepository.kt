package com.callerid.numberlookup.home.permission

import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.common.WindowInsetsHelper
import com.callerid.numberlookup.home.monetize.strategy.RemoteConfigSync
import org.json.JSONObject

/**
 * The engine's one access point for its configuration.
 *
 * It reads the `permission_engine` block out of Firebase Remote Config and holds
 * the parsed [PermissionRule]s in memory. Remote Config already persists
 * activated values to disk, so the last known configuration is on hand
 * immediately at the next cold start and the engine keeps working before any
 * fresh fetch completes.
 *
 * Configuration is resolved in order, first non-empty result winning:
 *  1. a Remote Config parameter named `permission_engine`, or
 *  2. the `permission_engine` key inside the app's existing data blob
 *     (`GET_DATA_LIST` / `DEBUG_GET_DATA_LIST`), so a new RC parameter is not
 *     strictly required at all.
 */
object PermissionRepository {

    private const val TAG = "PermissionCoordinator"
    private const val RC_KEY = "permission_engine"

    /**
     * Compiled-in fallback configuration, used **only** while Remote Config has
     * no `permission_engine` value - before the first successful fetch, or if the
     * parameter is never set server-side. Any Remote Config value replaces this
     * entirely.
     *
     * Notification and phone state are engine-driven but triggered explicitly,
     * from the splash flow in AdAwareActivity and from the Continue button on the
     * permission bottom sheet in MainShellActivity. The default therefore targets
     * both `SplashActivity` and `MainShellActivity` with no delay, since the
     * trigger point has already chosen the moment.
     */
    private const val DEFAULT_CONFIG = """
        {
          "permission_engine": {
            "notification": { "enabled": true, "activities": ["SplashActivity", "MainShellActivity"], "delay": 0, "priority": 1 },
            "phone_state":  { "enabled": true, "activities": ["SplashActivity", "MainShellActivity"], "delay": 0, "priority": 2 }
          }
        }
    """

    @Volatile
    private var cached: List<PermissionRule>? = null

    /** Returns cached rules, parsing from Remote Config on first access. */
    fun rules(): List<PermissionRule> = cached ?: reload()

    /** Re-reads (and re-parses) the currently activated Remote Config values. */
    @Synchronized
    fun reload(): List<PermissionRule> {
        val parsed = RemotePermissionParser.parse(rawConfig())
        cached = parsed
        return parsed
    }

    /**
     * Kicks off a fresh Remote Config fetch and then refreshes the cache. Safe to
     * call once at startup; a failure falls back silently to the cached, activated
     * values, so the flow is never blocked.
     */
    fun refreshFromRemote(onReady: (() -> Unit)? = null) {
        try {
            val rc = FirebaseRemoteConfig.getInstance()
            val settings = FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(RemoteConfigSync.fetchIntervalSeconds())
                .setFetchTimeoutInSeconds(10)
                .build()
            rc.setConfigSettingsAsync(settings)
            rc.fetchAndActivate().addOnCompleteListener { task ->
                WindowInsetsHelper.log(TAG, "Remote Config fetch success=${task.isSuccessful}")
                reload()
                onReady?.invoke()
            }
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "refreshFromRemote failed; using cached config", e)
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
            WindowInsetsHelper.log(TAG, "No remote permission_engine config; using compiled-in default")
            DEFAULT_CONFIG
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "Failed to read Remote Config; using compiled-in default", e)
            DEFAULT_CONFIG
        }
    }

    /** marketing/organic sub-object (by OnMaketing), else the flat blob. */
    private fun audienceRoot(obj: JSONObject): JSONObject {
        val isMarketing = AdPreferenceStore.getOrNull()?.getBoolean("OnMaketing") ?: false
        val preferred = if (isMarketing) "marketing" else "organic"
        val fallback = if (isMarketing) "organic" else "marketing"
        obj.optJSONObject(preferred)?.let { return it }
        obj.optJSONObject(fallback)?.let { return it }
        return obj
    }
}
