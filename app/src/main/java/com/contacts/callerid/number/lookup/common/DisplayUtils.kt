package com.contacts.callerid.number.lookup.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore
import com.contacts.callerid.number.lookup.R
import java.io.InputStream


fun isNightMode(context: Context): Boolean {
    val mode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
    return mode == Configuration.UI_MODE_NIGHT_YES
}

fun Activity.setTransparentStatusBarWhiteText() {

    // Make status bar transparent
    window.statusBarColor = Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = window.insetsController
        controller?.setSystemBarsAppearance(
            0, // ❌ remove LIGHT_STATUS_BARS
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        // ⚠️ DO NOT add LIGHT_STATUS_BAR → keeps icons WHITE
    }
}

fun Activity.setTransparentStatusBarDarkText() {

    window.statusBarColor = Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

}

fun Fragment.getStatusBarHeight(): Int {
    return requireActivity().getStatusBarHeight()
}

fun Context.getStatusBarHeight(): Int {
    val resourceId =
        resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0)
        resources.getDimensionPixelSize(resourceId)
    else 0
}

fun Context.rateApp() {
    val appPackageName = packageName
    val playStoreUri = Uri.parse("market://details?id=$appPackageName")
    val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")

    try {
        val intent = Intent(Intent.ACTION_VIEW, playStoreUri)
        startActivity(intent)
    } catch (e: Exception) {
        startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

/**
 * Opens the URL held under [configKey] in a Chrome Custom Tab, falling back to
 * whatever browser the device actually has.
 *
 * Chrome is *preferred* but never required. Pinning
 * `setPackage("com.android.chrome")` unconditionally turned the link into a
 * silent no-op on every device without Chrome, because the
 * ActivityNotFoundException was swallowed. Play requires the privacy-policy link
 * to genuinely open, which makes the un-pinned retry the half that matters.
 */
private fun Context.openConfigLink(configKey: String) {
    val url = AdPreferenceStore.getInstance(this).getString(configKey).orEmpty()
    if (url.isBlank()) return

    fun buildIntent() = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        val bundle = Bundle()
        bundle.putBinder("android.support.customtabs.extra.SESSION", null)
        putExtras(bundle)

        putExtra(
            "android.support.customtabs.extra.TOOLBAR_COLOR",
            ContextCompat.getColor(this@openConfigLink, R.color.black)
        )
        putExtra("android.support.customtabs.extra.EXTRA_ENABLE_INSTANT_APPS", true)
    }

    // Preferred: Chrome Custom Tab.
    try {
        startActivity(buildIntent().apply { setPackage("com.android.chrome") })
        return
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // Fallback: any browser that handles https VIEW (declared in the manifest's
    // <queries> block, so it stays resolvable on Android 11+).
    try {
        startActivity(buildIntent())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/** Open privacy policy link via Chrome Custom Tabs (if available) */
fun Context.openPolicyLink() = openConfigLink("PrivacyPolicy")

/** Open terms link via Chrome Custom Tabs (if available) */
fun Context.openTermLink() = openConfigLink("TermLink")

/** Share app via other apps */
fun Context.shareApp() {
    val appPackageName = packageName
    val shareText =
        "Check out this app: https://play.google.com/store/apps/details?id=$appPackageName"
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    startActivity(Intent.createChooser(shareIntent, "Share app via"))
}

/**
 * Open an asset image — tries .webp first, falls back to .png.
 * Usage: openThemeAsset(assets, "theme/th_1/background")
 */
fun openThemeAsset(am: AssetManager, basePath: String): InputStream? {
    return try {
        am.open("$basePath.webp")
    } catch (_: Exception) {
        try {
            am.open("$basePath.png")
        } catch (_: Exception) {
            null
        }
    }
}
