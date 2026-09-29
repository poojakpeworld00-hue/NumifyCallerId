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
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.feature.MainShellActivity
import com.callerid.numberlookup.home.feature.splash.SplashActivity
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager
import com.callerid.numberlookup.home.monetize.strategy.AudienceConfig
import org.json.JSONObject

/**
 * The launcher long-press "Uninstall" shortcut and the retention funnel behind it.
 *
 * Config is one flat `uninstall_flow` object inside each audience segment of
 * the already-split Remote Config:
 *
 * ```
 * "organic":   { ..., "uninstall_flow": { "enabled": true, "type": "simple",  "splash_ad": false, "thanks_delay": 3 } }
 * "marketing": { ..., "uninstall_flow": { "enabled": true, "type": "advance", "splash_ad": true,  "thanks_delay": 3 } }
 * ```
 *
 * Routes - the shortcut always enters through [SplashActivity], which keeps the
 * ads SDK and config fresh:
 *
 * ```
 * advance → splash ad → Language → interstitial → Sorry → Reason → Thanks → Progress → App info
 * simple  →                                        Sorry → Reason → Thanks →            App info
 * ```
 *
 * Both end on the system App-info page, where the real Uninstall button is. The
 * app never pretends to uninstall itself.
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

    /** Whether the advance route opens on the splash ad. */
    fun showSplashAd(): Boolean = config()?.optBoolean("splash_ad", false) ?: false

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

    /** Pads a funnel screen clear of the status bar and the gesture area. */
    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
