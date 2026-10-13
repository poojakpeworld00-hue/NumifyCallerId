package com.callerid.numberlookup.home.monetize.strategy

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Remote Config `rewarded` (in the GET_DATA_LIST audience block, ingested into
 * AdPreferenceStore by AdConfigIngest):
 *
 * ```
 * { "nofill_mode": "free", "nofill_cooldown_sec": 120, "nofill_backoff": true,
 *   "nofill_cooldown_max_sec": 900, "max_retries": 2 }
 * ```
 *
 * - `nofill_mode`: what a Watch Ad tap does while the no-fill cooldown runs.
 *   `"free"` unlocks at once (the timer stays hidden); `"wait"` shows the
 *   countdown ring, and "Unlock now" asks for the ad again once it reaches zero.
 * - `nofill_cooldown_sec`: the cooldown after a no-fill, during which no rewarded
 *   request is made. 0 turns the cooldown off - a no-fill is then handled like any
 *   other load error.
 * - `nofill_backoff`: each no-fill in a row doubles the cooldown, up to
 *   `nofill_cooldown_max_sec`. An ad that fills starts the count over.
 * - `max_retries`: "Ad not ready" Retries (other load errors) before the content
 *   unlocks for free. 0 unlocks without the dialog; a negative value never gives up.
 *
 * Missing or malformed → the defaults below.
 */
data class RewardedConfig(
    val mode: Mode = Mode.FREE,
    val cooldownSec: Int = 120,
    val backoff: Boolean = true,
    val cooldownMaxSec: Int = 900,
    val maxRetries: Int = 2,
) {
    enum class Mode { FREE, WAIT }

    companion object {
        const val KEY = "rewarded"

        fun read(context: Context): RewardedConfig {
            val raw = AdPreferenceStore.getInstance(context).getString(KEY, null)
            val o = raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return RewardedConfig()
            val defaults = RewardedConfig()
            val cooldown = o.optInt("nofill_cooldown_sec", defaults.cooldownSec).coerceAtLeast(0)
            return RewardedConfig(
                mode = if (o.optString("nofill_mode").equals("wait", ignoreCase = true)) Mode.WAIT else Mode.FREE,
                cooldownSec = cooldown,
                backoff = o.optBoolean("nofill_backoff", defaults.backoff),
                cooldownMaxSec = o.optInt("nofill_cooldown_max_sec", defaults.cooldownMaxSec).coerceAtLeast(cooldown),
                maxRetries = o.optInt("max_retries", defaults.maxRetries),
            )
        }
    }
}

/**
 * The no-fill cooldown: when it ends and how many no-fills came in a row. Kept in
 * prefs on the wall clock, so closing and reopening the app does not cut it short.
 */
object RewardedCooldown {

    private const val TAG = "RewardedAdPresenter"
    private const val PREFS = "rewarded_nofill"
    private const val KEY_UNTIL = "until"
    private const val KEY_STREAK = "streak"
    private const val KEY_TOTAL = "total"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Milliseconds left, 0 when none is running (or the config turned it off). */
    fun remainingMs(context: Context): Long {
        val config = RewardedConfig.read(context)
        if (config.cooldownSec <= 0) return 0
        val left = prefs(context).getLong(KEY_UNTIL, 0L) - System.currentTimeMillis()
        // Never more than the longest cooldown: a clock moved backwards cannot park the user.
        return left.coerceIn(0L, config.cooldownMaxSec * 1000L)
    }

    fun isActive(context: Context): Boolean = remainingMs(context) > 0

    /** Full length of the cooldown now running, for the countdown ring. */
    fun totalMs(context: Context): Long =
        prefs(context).getLong(KEY_TOTAL, 0L).coerceAtLeast(remainingMs(context)).coerceAtLeast(1L)

    /** A no-fill: start the next cooldown, doubled per no-fill in a row when backoff is on. */
    fun start(context: Context) {
        val config = RewardedConfig.read(context)
        if (config.cooldownSec <= 0) return
        val p = prefs(context)
        val streak = p.getInt(KEY_STREAK, 0)
        val seconds = if (config.backoff) {
            (config.cooldownSec.toLong() shl streak.coerceAtMost(20))
                .coerceAtMost(config.cooldownMaxSec.toLong())
        } else {
            config.cooldownSec.toLong()
        }
        p.edit()
            .putLong(KEY_UNTIL, System.currentTimeMillis() + seconds * 1000L)
            .putLong(KEY_TOTAL, seconds * 1000L)
            .putInt(KEY_STREAK, streak + 1)
            .apply()
        Log.d(TAG, "no-fill → cooldown ${seconds}s (streak ${streak + 1})")
    }

    /** An ad filled: the next no-fill starts from the base cooldown again. */
    fun reset(context: Context) {
        prefs(context).edit().putLong(KEY_UNTIL, 0L).putInt(KEY_STREAK, 0).apply()
    }
}
