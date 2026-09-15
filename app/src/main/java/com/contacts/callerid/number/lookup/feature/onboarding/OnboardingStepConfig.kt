package com.contacts.callerid.number.lookup.feature.onboarding

import android.content.Context
import android.telephony.TelephonyManager
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore
import com.contacts.callerid.number.lookup.permission.lockscreen.LockScreenPermission
import com.contacts.callerid.number.lookup.permission.lockscreen.LockScreenAlertActivity
import com.contacts.callerid.number.lookup.feature.MainShellActivity
import com.contacts.callerid.number.lookup.feature.language.LanguagePickerActivity
import com.contacts.callerid.number.lookup.feature.intro.IntroActivity
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Parsed view of the Remote Config `screen_order` / `screen` / `exit` block: the
 * "Onboarding Dynamic Flow" that superseded `intro_display` plus
 * `permission_engine`. It is the single source of truth for
 *  - which onboarding screens run, and in what order ([screenOrder],
 *    [nextEligibleAfter]),
 *  - each screen's enabled flag, repeat frequency and country gate ([isEligible]),
 *  - the permissions each screen asks for ([stepConfig] / [splashConfig] `.permissions`),
 *  - the managed app-exit behaviour ([exitConfig]).
 *
 * `fsi_permission` is routed to the pre-existing and more detailed
 * [LockScreenPermission] / `LockScreenConfig` gate, with its grant state and its
 * own ledger, instead of being reimplemented here.
 */
object OnboardingStepConfig {

    /** One parsed `screen.<key>` entry (language / onboarding / fsi_permission-shaped screens). */
    data class ScreenStep(
        val key: String,
        val isEnable: Boolean,
        val session: String,
        val autonextSec: Int,
        val isSkipShow: Boolean,
        val countryCheckEnabled: Boolean,
        val excludedCountries: List<String>,
        val onBackPerformNext: Boolean,
        val isInterShow: Boolean,
        val isBottomAds: Boolean,
        val isBottomAdsType: String,
        val permissions: List<ScreenPermission>,
    )

    /** One entry of a screen's `permissions[]` array. */
    data class ScreenPermission(
        val countryCheckEnabled: Boolean,
        val excludedCountries: List<String>,
        val permissionNames: List<String>,
    )

    /** The `screen.splash` entry — always runs first, unconditionally. */
    data class SplashStep(
        val adType: String, // "app_open" | "inter" | "none"
        val bannerAdShow: Boolean,
        val bannerAdId: String,
        val bannerAdType: String,
        val permissions: List<ScreenPermission>,
    )

    /** The `exit` block — Home's managed back/exit behaviour. */
    data class ExitConfig(
        val isEnable: Boolean,
        val exitType: String, // "double_back" | "dialog"
        val doubleBackIntervalSec: Int,
        val doubleBackToastText: String,
        val isInterShow: Boolean,
        val countryCheckEnabled: Boolean,
        val excludedCountries: List<String>,
        val dialogEnabled: Boolean,
        val dialogTitle: String,
        val dialogDesc: String,
        val dialogPositive: String,
        val dialogNegative: String,
        /** `dialog.isNativeAdShow` — render an ad inside the exit dialog. */
        val dialogAdShow: Boolean,
        /** `dialog.isBottomAdsType` — "MediumNative" | "BigNative" | "Banner". */
        val dialogAdType: String,
    )

    // --- Raw JSON access (stored as strings in AdPreferenceStore, like ScreenAds) ---

    fun screenOrder(context: Context): List<String> {
        val raw = AdPreferenceStore.getInstance(context).getString("screen_order", "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }

    private fun screenRoot(context: Context): JSONObject =
        runCatching {
            JSONObject(AdPreferenceStore.getInstance(context).getString("screen", "{}") ?: "{}")
        }.getOrDefault(JSONObject())

    private fun stringList(obj: JSONObject?, field: String): List<String> =
        obj?.optJSONArray(field)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().uppercase().ifBlank { null } }
        } ?: emptyList()

    private fun parsePermissions(obj: JSONObject?): List<ScreenPermission> {
        val arr = obj?.optJSONArray("permissions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            val names = p.optJSONArray("permission_name")?.let { na ->
                (0 until na.length()).mapNotNull { na.optString(it).takeIf(String::isNotBlank) }
            } ?: emptyList()
            if (names.isEmpty()) return@mapNotNull null
            ScreenPermission(
                countryCheckEnabled = p.optBoolean("is_permissionListCountryCheck", false),
                excludedCountries = stringList(p, "permission_excluded_countries"),
                permissionNames = names,
            )
        }
    }

    /** Parses `screen.<key>` — null when the key isn't present at all. */
    fun stepConfig(context: Context, key: String): ScreenStep? {
        val obj = screenRoot(context).optJSONObject(key) ?: return null
        return ScreenStep(
            key = key,
            isEnable = obj.optBoolean("isEnable", true),
            session = obj.optString("session", "every"),
            autonextSec = obj.optInt("autonext", 0),
            isSkipShow = obj.optBoolean("isSkipShow", false),
            countryCheckEnabled = obj.optBoolean("is_screenListCountryCheck", false),
            excludedCountries = stringList(obj, "screen_excluded_countries"),
            onBackPerformNext = obj.optBoolean("onBackPerformNext", false),
            isInterShow = obj.optBoolean("isInterShow", false),
            isBottomAds = obj.optBoolean("isBottomAds", false),
            isBottomAdsType = obj.optString("isBottomAdsType", "Banner"),
            permissions = parsePermissions(obj),
        )
    }

    fun splashConfig(context: Context): SplashStep {
        val obj = screenRoot(context).optJSONObject("splash")
        val bannerAd = obj?.optJSONObject("banner_ad")
        return SplashStep(
            adType = obj?.optString("ad_type", "app_open")?.ifBlank { "app_open" } ?: "app_open",
            bannerAdShow = bannerAd?.optBoolean("show", false) ?: false,
            bannerAdId = bannerAd?.optString("id", "") ?: "",
            bannerAdType = bannerAd?.optString("type", "adaptive") ?: "adaptive",
            permissions = parsePermissions(obj),
        )
    }

    fun exitConfig(context: Context): ExitConfig {
        val obj = runCatching {
            JSONObject(AdPreferenceStore.getInstance(context).getString("exit", "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val dialog = obj.optJSONObject("dialog")
        return ExitConfig(
            isEnable = obj.optBoolean("isEnable", false),
            exitType = obj.optString("exitType", "double_back"),
            doubleBackIntervalSec = obj.optInt("doubleBackIntervalSec", 2),
            doubleBackToastText = obj.optString("doubleBackToastText", ""),
            isInterShow = obj.optBoolean("isInterShow", false),
            countryCheckEnabled = obj.optBoolean("is_screenListCountryCheck", false),
            excludedCountries = stringList(obj, "screen_excluded_countries"),
            dialogEnabled = dialog?.optBoolean("isEnable", false) ?: false,
            dialogTitle = dialog?.optString("title", "") ?: "",
            dialogDesc = dialog?.optString("desc", "") ?: "",
            dialogPositive = dialog?.optString("positiveButton", "") ?: "",
            dialogNegative = dialog?.optString("negativeButton", "") ?: "",
            dialogAdShow = dialog?.optBoolean("isNativeAdShow", false) ?: false,
            dialogAdType = dialog?.optString("isBottomAdsType", "MediumNative")
                ?.ifBlank { "MediumNative" } ?: "MediumNative",
        )
    }

    // --- Gating ---

    /** Best-effort country signals: IP country, SIM ISO, network ISO, locale — no permission needed. */
    private fun deviceCountrySignals(context: Context): List<String> {
        val out = mutableListOf<String>()
        AdPreferenceStore.getInstance(context).userCountry.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        runCatching {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.simCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.trim() }
            tm?.networkCountryIso?.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        }
        Locale.getDefault().country.takeIf { it.isNotBlank() }?.let { out += it.trim() }
        return out.distinct()
    }

    /** Fail-open: no filter, empty list, or no signal → allowed. */
    fun isCountryAllowed(context: Context, enabled: Boolean, excluded: List<String>): Boolean {
        if (!enabled || excluded.isEmpty()) return true
        val signals = deviceCountrySignals(context)
        if (signals.isEmpty()) return true
        return signals.none { sig -> excluded.any { it.equals(sig, ignoreCase = true) } }
    }

    /** `"every"` always passes; `"once"` passes only before the first show; `"<N>"` passes every N launches. */
    private fun sessionGatePasses(context: Context, key: String, session: String): Boolean {
        val prefs = SettingsRepository(context)
        val trimmed = session.trim()
        return when {
            trimmed.equals("every", ignoreCase = true) -> true
            trimmed.equals("once", ignoreCase = true) -> prefs.introShownCount(key) == 0
            // "<N>d" — every N days (a day-based cadence some screens need, e.g.
            // the permission sheet's old `every_days` mode).
            trimmed.endsWith("d", ignoreCase = true) &&
                trimmed.dropLast(1).toIntOrNull()?.let { it > 0 } == true -> {
                val days = trimmed.dropLast(1).toInt()
                val last = prefs.introLastShownMs(key)
                last == 0L || (System.currentTimeMillis() - last) / DAY_MS >= days
            }
            // "<N>" — every N app sessions/launches.
            trimmed.toIntOrNull()?.let { it > 0 } == true -> prefs.appLaunchCount % trimmed.toInt() == 0
            else -> true
        }
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * Whether [key] is due to show right now. `fsi_permission` hands off entirely
     * to [LockScreenPermission.shouldShowScreen], whose grant state and ledger are
     * more specific than the generic gate below.
     *
     * **Behaviour when config is missing differs by key, deliberately.** A
     * `screen_order` screen with no matching `screen.<key>` entry stays hidden:
     * `screen_order` is the source of truth, and showing an unconfigured screen is
     * worse than skipping it. `permission_sheet` is the reverse case - it is not
     * part of `screen_order`, it is core Home behaviour, and the old
     * `IntroGateConfig.permissionSheet()` defaulted it to `enabled=true` plus
     * `ALWAYS`. Failing closed here quietly killed the sheet whenever
     * `screen.permission_sheet` was absent, which included every first launch,
     * since Remote Config lands asynchronously and there are no local RC defaults.
     * So it fails open, matching that old default.
     */
    fun isEligible(context: Context, key: String): Boolean {
        if (key == FSI_PERMISSION_KEY) return LockScreenPermission.shouldShowScreen(context)
        val step = stepConfig(context, key) ?: return key == PERMISSION_SHEET_KEY
        if (!step.isEnable) return false
        if (!isCountryAllowed(context, step.countryCheckEnabled, step.excludedCountries)) return false
        return sessionGatePasses(context, key, step.session)
    }

    /** First eligible screen strictly after [afterKey] in `screen_order`, or null (→ Home). */
    fun nextEligibleAfter(context: Context, afterKey: String): String? {
        val order = screenOrder(context)
        val fromIndex = order.indexOf(afterKey)
        return order.drop(fromIndex + 1).firstOrNull { isEligible(context, it) }
    }

    /** First eligible screen in the whole order — splash calls this. */
    fun firstEligible(context: Context): String? =
        screenOrder(context).firstOrNull { isEligible(context, it) }

    /** Records that [key] was shown this launch — de-dupes within one cold start. */
    fun markShown(context: Context, key: String) {
        val prefs = SettingsRepository(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(key) == session) return
        prefs.recordIntroShown(key, session)
    }

    /** Maps a `screen_order` key to the Activity that implements it. */
    fun classFor(key: String): Class<*> = when (key) {
        LANGUAGE_KEY -> LanguagePickerActivity::class.java
        ONBOARDING_KEY -> IntroActivity::class.java
        FSI_PERMISSION_KEY -> LockScreenAlertActivity::class.java
        else -> MainShellActivity::class.java
    }

    const val LANGUAGE_KEY = "language"
    const val ONBOARDING_KEY = "onboarding"
    const val FSI_PERMISSION_KEY = "fsi_permission"
    const val SPLASH_KEY = "splash"

    /**
     * Not part of `screen_order`: this is the auto-show frequency gate for Home's
     * permission bottom sheet. Only `isEnable`, `session` and `isCountryAllowed`,
     * should it ever be set, carry any meaning from its `screen.permission_sheet`
     * entry; the rest of [ScreenStep] describes a sequenced screen and does not
     * apply to a sheet.
     */
    const val PERMISSION_SHEET_KEY = "permission_sheet"
}
