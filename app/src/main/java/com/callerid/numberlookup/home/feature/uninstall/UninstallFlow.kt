package com.callerid.numberlookup.home.feature.uninstall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.feature.MainShellActivity
import com.callerid.numberlookup.home.feature.splash.SplashActivity
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.AudienceConfig
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import org.json.JSONObject

/**
 * The launcher long-press "Uninstall" shortcut and the retention funnel behind it.
 *
 * Config is one flat `uninstall_flow` object inside each audience segment of
 * the already-split Remote Config:
 *
 * ```
 * "uninstall_flow": {
 *   "enabled": true, "type": "advance", "thanks_delay": 3,
 *   "splash_ad":   { "show": true, "type": "app_open", "id": "ca-app-pub-…" },
 *   "language_ad": { "show": true, "type": "big",      "id": "ca-app-pub-…" },
 *   "inter_ad":    { "show": true, "id": "ca-app-pub-…",
 *                    "on": ["language", "sorry", "reason", "progress", "thanks"] }
 * }
 * ```
 *
 * The three ad blocks give the funnel its own ad units, apart from the app-wide
 * ones; see [splashAd], [languageAd] and [interAd]. All are optional, and
 * `"splash_ad": true/false` is still read. They apply on either route. Debug
 * builds swap any id set here for Google's test unit of the same format.
 *
 * Routes - the shortcut always enters through [SplashActivity], which keeps the
 * ads SDK and config fresh:
 *
 * ```
 * advance → [splash ad] → Language → Sorry → Reason → Progress → Thanks → app closes
 * simple  → [splash ad] →            Sorry → Reason →            Thanks → App info
 * ```
 *
 * Simple ends on the system App-info page, where the real Uninstall button is.
 * Advance does not open it: the app closes and leaves Recents ([closeApp]), and
 * its screens say so, and that uninstalling is still in Settings. The app never
 * pretends to uninstall itself.
 *
 * With no config at all the shortcut is on and runs the simple route, so the
 * feature works on a first install before anything has been fetched.
 *
 * Each screen's native ad is the usual `ScreenAds` entry under its screen key:
 * UninstallSorryActivity, UninstallReasonActivity, UninstallThanksActivity,
 * UninstallProgressActivity.
 */
object UninstallFlow {

    private const val TAG = "UninstallFlow"
    private const val RC_KEY = "uninstall_flow"
    private const val SHORTCUT_ID = "uninstall"

    /** Intent extra naming the launcher shortcut a launch came from. */
    const val EXTRA_SHORTCUT = "SHORTCUT"

    /** [EXTRA_SHORTCUT]'s value on the Uninstall shortcut. */
    const val SHORTCUT_UNINSTALL = "uninstall"

    /** Intent extra every funnel screen forwards: true = the `advance` route. */
    const val EXTRA_ADVANCED = "uninstall_advanced"

    // ── Config ──────────────────────────────────────────────────────────────

    /** This user's `uninstall_flow` block, from their audience segment. */
    private fun config(): JSONObject? = AudienceConfig.block(RC_KEY)

    /** Publish the shortcut at all. On by default, so it works before the first fetch. */
    fun isEnabled(): Boolean = config()?.optBoolean("enabled", true) ?: true

    /** `advance` adds the splash ad, the language screen and an interstitial up front. */
    fun isAdvanced(): Boolean =
        config()?.optString("type", "simple").equals("advance", ignoreCase = true)

    /**
     * One of the funnel's own ad slots: on or off, its format, and its ad unit.
     * A null [type] or [id] falls back to the app-wide setting for that slot.
     */
    data class FlowAd(val show: Boolean, val type: String?, val id: String?)

    /** The funnel's interstitial: one unit, shown on leaving each page in [pages]. */
    data class InterAd(val show: Boolean, val id: String?, val pages: Set<String>)

    /** An AdMob unit id shape: `ca-app-pub-<digits>/<digits>`. */
    private val AD_UNIT_ID = Regex("""ca-app-pub-\d+/\d+""")

    /**
     * [key]'s ad unit id, or null when blank or not a unit id at all (say a
     * placeholder left in the config), so the slot falls back to the app-wide
     * unit rather than failing every request.
     */
    private fun JSONObject.adUnitId(testId: String, key: String = "id"): String? {
        val id = optString(key).trim().ifBlank { return null }
        // Debug builds never request a live unit: any id set here becomes
        // Google's test unit for the same format.
        if (BuildConfig.DEBUG) return testId
        if (AD_UNIT_ID.matches(id)) return id
        Log.w(TAG, "uninstall_flow: '$id' is not an ad unit id - using the app-wide unit")
        return null
    }

    /** Google's sample ad units, one per format, for debug builds. */
    private const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
    private const val TEST_INTER = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_NATIVE = "ca-app-pub-3940256099942544/2247696110"
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

    /**
     * `language_ad.type` → one of `big`, `big_top`, `mid`, `mid2`, `small`,
     * `banner`. The `ScreenAds` / onboarding spellings are accepted too, and plain
     * `native` means `mid`, the app's default native size. Unknown → null.
     */
    private fun languageAdType(raw: String): String? = when (raw.trim().lowercase()) {
        "big", "bignative", "big_native" -> "big"
        "big_top", "bignativetop" -> "big_top"
        "mid", "medium", "mediumnative", "native" -> "mid"
        "mid2", "mediumnativealt" -> "mid2"
        "small", "nativebanner", "native_banner" -> "small"
        "banner" -> "banner"
        else -> null
    }

    /** Page names for [InterAd.pages] — the screen the user is leaving. */
    const val PAGE_LANGUAGE = "language"
    const val PAGE_SORRY = "sorry"
    const val PAGE_REASON = "reason"
    const val PAGE_PROGRESS = "progress"
    const val PAGE_THANKS = "thanks"
    private val ALL_PAGES = setOf(PAGE_LANGUAGE, PAGE_SORRY, PAGE_REASON, PAGE_PROGRESS, PAGE_THANKS)

    /**
     * `splash_ad` — `{ "show": true, "type": "app_open" | "inter", "id": "…" }`.
     *
     * The old boolean form (`"splash_ad": true`) still works: on or off, with the
     * app-wide splash format and unit. Absent means off.
     */
    fun splashAd(): FlowAd = when (val raw = config()?.opt("splash_ad")) {
        is JSONObject -> {
            val type = raw.optString("type").trim().lowercase().takeIf { it == "app_open" || it == "inter" }
            FlowAd(
                show = raw.optBoolean("show", true),
                type = type,
                id = raw.adUnitId(if (type == "inter") TEST_INTER else TEST_APP_OPEN),
            )
        }
        is Boolean -> FlowAd(show = raw, type = null, id = null)
        else -> FlowAd(show = false, type = null, id = null)
    }

    /** Whether the funnel opens on a splash ad. */
    fun showSplashAd(): Boolean = splashAd().show

    /**
     * `language_ad` — `{ "show": true, "type": "big" | "big_top" | "mid" | "mid2" |
     * "small" | "banner", "id": "…" }`, the ad on the funnel's language page.
     * Null when not published: the page then uses its
     * `ScreenAds.UninstallLanguageActivity` entry as before.
     */
    fun languageAd(): FlowAd? {
        val raw = config()?.optJSONObject("language_ad") ?: return null
        val type = languageAdType(raw.optString("type")) ?: "mid"
        return FlowAd(
            show = raw.optBoolean("show", true),
            type = type,
            id = raw.adUnitId(if (type == "banner") TEST_BANNER else TEST_NATIVE),
        )
    }

    /**
     * `inter_ad` — `{ "show": true, "id": "…", "on": ["language", "sorry", …] }`.
     * An interstitial on leaving each listed page, outside InterCounter, so every
     * listed page really shows one when an ad is ready. `on` absent means every
     * page. Null when not published: only the language page shows one, through
     * the app-wide interstitial as before.
     */
    fun interAd(): InterAd? {
        val raw = config()?.optJSONObject("inter_ad") ?: return null
        val on = raw.optJSONArray("on")
        val pages = if (on == null) ALL_PAGES
        else (0 until on.length()).mapNotNull { on.optString(it).trim().lowercase().ifBlank { null } }.toSet()
        return InterAd(
            show = raw.optBoolean("show", true),
            id = raw.adUnitId(TEST_INTER),
            pages = pages,
        )
    }

    /** How long the thank-you screen holds before moving on, 1–10 s. */
    fun thanksDelayMs(): Long = (config()?.optInt("thanks_delay", 3) ?: 3).coerceIn(1, 10) * 1000L

    // ── Launcher shortcut ─────────────────────────────────────────────────

    /**
     * Adds or removes the dynamic "Uninstall" shortcut to match the config. Safe
     * on every launch: pushing an unchanged shortcut is a no-op.
     */
    fun syncShortcut(ctx: Context) {
        val app = ctx.applicationContext
        try {
            if (!isEnabled()) {
                ShortcutManagerCompat.removeDynamicShortcuts(app, listOf(SHORTCUT_ID))
                return
            }
            val intent = Intent(app, SplashActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(EXTRA_SHORTCUT, SHORTCUT_UNINSTALL)
            }
            val shortcut = ShortcutInfoCompat.Builder(app, SHORTCUT_ID)
                .setShortLabel(app.getString(R.string.un_shortcut))
                .setLongLabel(app.getString(R.string.un_shortcut))
                .setIcon(IconCompat.createWithResource(app, R.drawable.ic_shortcut_uninstall))
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
        } catch (e: Exception) {
            Log.w(TAG, "syncShortcut failed", e)
        }
    }

    /** Whether [intent] is a launch from the Uninstall shortcut. */
    fun isUninstallLaunch(intent: Intent?): Boolean =
        intent?.getStringExtra(EXTRA_SHORTCUT) == SHORTCUT_UNINSTALL

    // ── Navigation ──────────────────────────────────────────────────────────

    /**
     * Leaves the funnel back into the app, optionally landing on one of
     * [MainShellActivity]'s targets (Contacts, Lookup, the overlay grant).
     */
    fun keepApp(activity: Activity, target: String? = null) {
        activity.startActivity(Intent(activity, MainShellActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            target?.let { putExtra(MainShellActivity.EXTRA_OPEN_TARGET, it) }
        })
        activity.finish()
    }

    /** The end of the funnel: the system App-info page, with the real Uninstall button. */
    fun openAppInfo(activity: Activity) {
        // Returning from Settings is not a fresh open: no app-open ad over it.
        AppOpenAdManager.skipNextAppOpenAd = true
        try {
            activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.w(TAG, "App info unavailable", e)
            AppOpenAdManager.skipNextAppOpenAd = false
        }
        activity.finishAffinity()
    }

    /**
     * The end of the `advance` route: closes every one of the app's tasks and
     * takes them out of Recents. Nothing is uninstalled; the user can still do
     * that from Settings, as the Thanks screen says.
     */
    fun closeApp(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        runCatching { am?.appTasks?.forEach { it.finishAndRemoveTask() } }
            .onFailure { Log.w(TAG, "closeApp: appTasks failed", it) }
        activity.finishAndRemoveTask()
    }

    // ── Analytics ───────────────────────────────────────────────────────────

    /**
     * Logs an uninstall-funnel event with the parameters every one of them
     * carries: `route` (simple | advance) and `audience` (paid | organic), plus
     * any [extra] ones. One place, so the funnel can be split both ways in
     * Firebase.
     */
    fun track(context: Context, event: String, vararg extra: Pair<String, String>) {
        val paid = AdPreferenceStore.getOrNull()?.getBoolean("OnMaketing") ?: false
        context.recordEvent(
            event,
            mapOf(
                "route" to if (isAdvanced()) "advance" else "simple",
                "audience" to if (paid) "paid" else "organic",
            ) + extra
        )
    }

    /** Pads a funnel screen clear of the status bar and the gesture area. */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
