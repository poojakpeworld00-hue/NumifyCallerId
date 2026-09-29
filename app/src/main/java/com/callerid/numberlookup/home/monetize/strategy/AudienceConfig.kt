package com.callerid.numberlookup.home.monetize.strategy

import android.util.Log
import com.callerid.numberlookup.home.BuildConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import org.json.JSONObject

/**
 * Reads a feature's own block out of the audience-split Remote Config.
 *
 * The config is split by audience at its top level, so a feature's block is flat
 * and sits inside each segment:
 *
 * ```
 * GET_DATA_LIST = {
 *   "organic":   { ..., "paywall": { "close_delay": 5 } },
 *   "marketing": { ..., "paywall": { "close_delay": 3 } }
 * }
 * ```
 *
 * The segment is chosen by [AdConfigIngest.audienceRoot] - the resolver the ad
 * config itself uses, falling back to the other audience and then to a legacy
 * flat blob - so a feature can never disagree with the ads about who the user
 * is. A dedicated Remote Config parameter named after the block, if one is ever
 * added, wins and is resolved the same way.
 */
object AudienceConfig {

    private const val TAG = "AudienceConfig"

    /** This user's [key] block, or null when the config has none (or cannot be read). */
    fun block(key: String): JSONObject? = try {
        val marketing = AdPreferenceStore.getOrNull()?.getBoolean("OnMaketing") ?: false
        val rc = FirebaseRemoteConfig.getInstance()
        val dedicated = rc.getString(key).takeIf { it.isNotBlank() }
        if (dedicated != null) {
            AdConfigIngest.audienceRoot(JSONObject(dedicated), marketing)
        } else {
            val blob = rc.getString(if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST")
            if (blob.isBlank()) null
            else AdConfigIngest.audienceRoot(JSONObject(blob), marketing).optJSONObject(key)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Bad $key config", e)
        null
    }
}
