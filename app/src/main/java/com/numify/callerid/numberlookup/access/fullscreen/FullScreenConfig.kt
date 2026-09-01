package com.numify.callerid.numberlookup.access.fullscreen

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.numify.callerid.adkit.policy.AdsPrefStore
import com.numify.callerid.numberlookup.BuildConfig
import com.numify.callerid.numberlookup.kit.EdgeInsets
import org.json.JSONObject

/**
 * Parsed, audience-resolved view of the `screen.fsi_permission` Remote Config
 * block (part of the Onboarding Dynamic Flow — see
 * [com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig]) that
 * drives the whole Full-Screen-Intent flow.
 *
 * Everything the flow does — whether it runs at all, the min SDK, the country
 * block-list, and the Screen / Dialog behaviour and copy — comes from here. No
 * country, screen, dialog, or on/off logic is hardcoded in the app.
 *
 * Read directly from Remote Config (not via `AdsPrefStore`), doing its own
 * top-level `marketing`/`organic` split resolution — same pattern as
 * [com.numify.callerid.numberlookup.access.AccessRepository]:
 *  1. a dedicated `screen` Remote Config parameter, or
 *  2. the `screen.fsi_permission` key nested in the app's `GET_DATA_LIST` /
 *     `DEBUG_GET_DATA_LIST` blob (inside the resolved audience segment).
 */
data class FullScreenConfig(
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
        private const val TAG = "FullScreenConfig"
        private const val LOG = "FSI" // shared debug tag with FullScreenAccess (adb logcat -s FSI)
        private const val RC_KEY = "screen"
        private const val BLOCK = "fsi_permission"

        // Copy fallbacks — used only when the RC copy field is blank. Matches the
        // approved Screen design; Remote Config overrides them at runtime.
        private const val DEF_TITLE = "Never miss who's calling"
        private const val DEF_DESC =
            "Show verified caller details on your lock screen — the instant a call comes in."
        private const val DEF_BUTTON = "Enable Now"

        /** Fully-off config — returned whenever the block is missing or unreadable. */
        val DISABLED = FullScreenConfig(
            enabled = false,
            minSdk = 34,
            countryFilterEnabled = false,
            excludedCountries = emptyList(),
            screen = Screen(false, true, 0, 1, DEF_TITLE, DEF_DESC, DEF_BUTTON),
            dialog = Dialog(false, 500, 2, 3, 1, DEF_TITLE, DEF_DESC, DEF_BUTTON),
        )

        /** Reads + parses the current, already audience-resolved config. */
        fun load(@Suppress("UNUSED_PARAMETER") context: Context): FullScreenConfig {
            return try {
                val block = rawBlock()
                if (block == null) {
                    EdgeInsets.log(LOG, "config: no screen.fsi_permission block in Remote Config → DISABLED")
                    return DISABLED
                }
                val resolved = parse(block)
                EdgeInsets.log(
                    LOG,
                    "config: enabled=${resolved.enabled}, minSdk=${resolved.minSdk}, " +
                        "screen=${resolved.screen.enabled}, dialog=${resolved.dialog.enabled}, " +
                        "countryFilter=${resolved.countryFilterEnabled}, excluded=${resolved.excludedCountries}"
                )
                resolved
            } catch (e: Exception) {
                EdgeInsets.error(TAG, "Failed to parse $BLOCK; feature disabled", e)
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
                EdgeInsets.error(TAG, "Remote Config read failed", e)
                null
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

        /**
         * Maps the `screen.fsi_permission` shape onto [FullScreenConfig]:
         * `isEnable` → [enabled]; `prompt.*` → [Screen] copy; `session == "once"` →
         * [Screen.showOnce] (any other value repeats every launch, matching
         * [com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig]'s
         * generic session gate); `is_screenListCountryCheck` / `screen_excluded_countries`
         * → the country gate; `dialog.*` maps 1:1. `screen`/`dialog` priority and the
         * screen's own delay are unused by [FullScreenAccess] today — kept as fixed
         * defaults rather than parsed.
         */
        private fun parse(o: JSONObject): FullScreenConfig {
            val promptObj = o.optJSONObject("prompt") ?: JSONObject()
            val dialogObj = o.optJSONObject("dialog") ?: JSONObject()
            val excluded = o.optJSONArray("screen_excluded_countries")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().uppercase().ifBlank { null } }
            } ?: emptyList()
            return FullScreenConfig(
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
