package com.callerid.numberlookup.home.monetize.strategy

import android.content.Context
import android.util.Log
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.permission.PermissionRepository
import org.json.JSONObject

/**
 * Refreshes everything this app caches out of Remote Config, without needing an
 * Activity.
 *
 * Two jobs. [fetchIntervalSeconds] is the single answer to "how often may we
 * fetch" — every call site asks here, because `setConfigSettingsAsync` writes to
 * one process-wide FirebaseRemoteConfig singleton and the last writer wins. Two
 * call sites with different intervals do not average out; one of them silently
 * loses, and which one depends on start-up ordering.
 *
 * [apply] re-reads the activated config and rebuilds the caches. It is what a
 * config change reaches devices through now that the interval is measured in
 * hours: the push arrives, the SDK activates the new values, this puts them
 * where the app actually reads them from.
 */
object RemoteConfigSync {

    private const val TAG = "RemoteConfigSync"

    /**
     * Twelve hours in release. This used to be one second, which made every
     * launch on every device a billed fetch — the cost scaled with sessions
     * rather than with how often anyone published.
     *
     * Debug stays at zero for iteration, with one consequence worth knowing: at
     * zero every fetch hits the network anyway, so an rc_sync push cannot be
     * meaningfully tested on a debug build. Test that path on release.
     */
    fun fetchIntervalSeconds(): Long =
        if (BuildConfig.DEBUG) 0L else 12L * 60L * 60L

    /**
     * Rebuilds the caches from the already-activated config. Safe to call from
     * any thread and at any time; a blank or malformed blob leaves the previous
     * values in place rather than clearing them.
     */
    fun apply(context: Context) {
        val remoteConfig = runCatching { FirebaseRemoteConfig.getInstance() }.getOrNull() ?: return
        val blobKey = if (BuildConfig.DEBUG) "DEBUG_GET_DATA_LIST" else "GET_DATA_LIST"
        val raw = runCatching { remoteConfig.getString(blobKey) }.getOrDefault("")

        if (raw.isBlank()) {
            Log.w(TAG, "$blobKey empty on sync; keeping cached config")
        } else {
            runCatching {
                val store = AdPreferenceStore.getInstance(context)
                store.putString("GET_DATA_RAW", raw)
                val onMarketing = store.getBoolean("OnMaketing")
                AdConfigIngest.ingestConfig(
                    context,
                    AdConfigIngest.audienceRoot(JSONObject(raw), onMarketing)
                )
            }.onFailure { Log.e(TAG, "ads config re-ingest failed", it) }
        }

        runCatching { PermissionRepository.reload() }
            .onFailure { Log.e(TAG, "permission config reload failed", it) }
    }
}
