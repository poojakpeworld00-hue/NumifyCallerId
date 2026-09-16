package com.callerid.numberlookup.home.permission.lockscreen

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.common.WindowInsetsHelper
import org.json.JSONObject

/**
 * Parsed, audience-resolved view of the `screen.fsi_permission` Remote Config
 * block - part of the Onboarding Dynamic Flow, see
 * [com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig] - that
 * drives the entire Full-Screen-Intent flow.
 *
 * Whether the flow runs at all, the minimum SDK, the blocked countries, and the
 * behaviour and copy of both the Screen and the Dialog all originate here.
 * Nothing about countries, screens, dialogs or on/off state is hardcoded.
 *
 * It reads Remote Config directly rather than going through `AdPreferenceStore`,
 * resolving the top-level `marketing`/`organic` split itself, in the same shape
 * as [com.callerid.numberlookup.home.permission.PermissionRepository]:
 *  1. a dedicated `screen` Remote Config parameter, or
 *  2. the `screen.fsi_permission` key nested inside the app's `GET_DATA_LIST` /
 *     `DEBUG_GET_DATA_LIST` blob, within the resolved audience segment.
 */
data class LockScreenConfig(
    val enabled: Boolean,
    val minSdk: Int,
    val countryFilterEnabled: Boolean,
    val excludedCountries: List<String>,
    val screen: Screen,
    val dialog: Dialog,
) {
    data class Screen(
        val enabled: Boolean,
        val showOnce: Boolean,
        val delayMs: Long,
        val priority: Int,
        val title: String,
        val desc: String,
        val button: String,
    )

    data class Dialog(
        val enabled: Boolean,
        val delayMs: Long,
        val priority: Int,
        val showAfterDays: Int,
        val maxShowCount: Int,
        val title: String,
        val desc: String,
        val button: String,
    )

    companion object {
        private const val TAG = "LockScreenConfig"
        private const val LOG = "FSI" // shared debug tag with LockScreenPermission (adb logcat -s FSI)
        private const val RC_KEY = "screen"
        private const val BLOCK = "fsi_permission"

        // Copy fallbacks — used only when the RC copy field is blank. Matches the
        // approved Screen design; Remote Config overrides them at runtime.
        private const val DEF_TITLE = "Never miss who's calling"
        private const val DEF_DESC =
            "Show verified caller details on your lock screen — the instant a call comes in."
        private const val DEF_BUTTON = "Enable Now"

        /** Fully-off config — returned whenever the block is missing or unreadable. */
        val DISABLED = LockScreenConfig(
            enabled = false,
            minSdk = 34,
            countryFilterEnabled = false,
            excludedCountries = emptyList(),
            screen = Screen(false, true, 0, 1, DEF_TITLE, DEF_DESC, DEF_BUTTON),
            dialog = Dialog(false, 500, 2, 3, 1, DEF_TITLE, DEF_DESC, DEF_BUTTON),
        )

        /** Reads + parses the current, already audience-resolved config. */
        fun load(@Suppress("UNUSED_PARAMETER") context: Context): LockScreenConfig {
            return try {
                val block = rawBlock()
                if (block == null) {
                    WindowInsetsHelper.log(LOG, "config: no screen.fsi_permission block in Remote Config → DISABLED")
                    return DISABLED
                }
                val resolved = parse(block)
                WindowInsetsHelper.log(
                    LOG,
                    "config: enabled=${resolved.enabled}, minSdk=${resolved.minSdk}, " +
                        "screen=${resolved.screen.enabled}, dialog=${resolved.dialog.enabled}, " +
                        "countryFilter=${resolved.countryFilterEnabled}, excluded=${resolved.excludedCountries}"
                )
                resolved
            } catch (e: Exception) {
                WindowInsetsHelper.error(TAG, "Failed to parse $BLOCK; feature disabled", e)
                DISABLED
            }
        }

        /** The `screen.fsi_permission` object from Remote Config, or null when absent. */
        private fun rawBlock(): JSONObject? {
            val engine = rawEngineJson() ?: return null
            val obj = JSONObject(engine)
            return if (obj.has(BLOCK)) obj.getJSONObject(BLOCK) else null
        }

        private fun rawEngineJson(): String? {
            return try {
                val rc = FirebaseRemoteConfig.getInstance()
                rc.getString(RC_KEY).takeIf { it.isNotBlank() }?.let { return it }
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
                null
            } catch (e: Exception) {
                WindowInsetsHelper.error(TAG, "Remote Config read failed", e)
                null
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

        /**
         * Projects the `screen.fsi_permission` shape onto [LockScreenConfig]:
         * `isEnable` becomes [enabled]; `prompt.*` becomes the [Screen] copy;
         * `session == "once"` becomes [Screen.showOnce], while any other value
         * repeats every launch, matching the generic session gate in
         * [com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig];
         * `is_screenListCountryCheck` and `screen_excluded_countries` become the
         * country gate; and `dialog.*` maps across one to one. The `screen` and
         * `dialog` priorities and the screen's own delay are not consumed by
         * [LockScreenPermission] today, so they are held as fixed defaults rather
         * than parsed.
         */
        private fun parse(o: JSONObject): LockScreenConfig {
            val promptObj = o.optJSONObject("prompt") ?: JSONObject()
            val dialogObj = o.optJSONObject("dialog") ?: JSONObject()
            val excluded = o.optJSONArray("screen_excluded_countries")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().uppercase().ifBlank { null } }
            } ?: emptyList()
            return LockScreenConfig(
                enabled = o.optBoolean("isEnable", false),
                minSdk = o.optInt("android_min_sdk", 34),
                countryFilterEnabled = o.optBoolean("is_screenListCountryCheck", false),
                excludedCountries = excluded,
                screen = Screen(
                    enabled = o.optBoolean("isEnable", false),
                    showOnce = o.optString("session", "every").equals("once", ignoreCase = true),
                    delayMs = 0,
                    priority = 1,
                    title = promptObj.optString("title").ifBlank { DEF_TITLE },
                    desc = promptObj.optString("desc").ifBlank { DEF_DESC },
                    button = promptObj.optString("button").ifBlank { DEF_BUTTON },
                ),
                dialog = Dialog(
                    enabled = dialogObj.optBoolean("isEnable", false),
                    delayMs = dialogObj.optLong("delay_ms", 500),
                    priority = 2,
                    showAfterDays = dialogObj.optInt("show_after_days", 3),
                    maxShowCount = dialogObj.optInt("max_show_count", 1),
                    title = dialogObj.optString("title").ifBlank { DEF_TITLE },
                    desc = dialogObj.optString("desc").ifBlank { DEF_DESC },
                    button = dialogObj.optString("button").ifBlank { DEF_BUTTON },
                ),
            )
        }
    }
}
