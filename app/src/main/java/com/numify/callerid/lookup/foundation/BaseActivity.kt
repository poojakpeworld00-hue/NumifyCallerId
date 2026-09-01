package com.numify.callerid.lookup.foundation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.facebook.shimmer.ShimmerFrameLayout
import com.numify.callerid.monetize.strategy.ScreenPlacementPlan
import com.numify.callerid.lookup.R
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.monetize.strategy.logPermissionResult
import com.numify.callerid.monetize.delivery.AdAwareActivity
import com.numify.callerid.monetize.delivery.fullpage.ExitInterstitialAd
import com.numify.callerid.monetize.delivery.fullpage.TransitionInterstitialAd
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.common.PreferenceStore
import com.numify.callerid.lookup.common.followAdContainer
import com.numify.callerid.lookup.common.PreferenceStore.THEME_DARK
import com.numify.callerid.lookup.common.PreferenceStore.THEME_LIGHT
import com.numify.callerid.lookup.common.PreferenceStore.THEME_SYSTEM
import org.json.JSONObject
import java.util.Locale
import kotlin.and
import kotlin.sequences.ifEmpty

/**
 * Base class for every Activity in the app.
 *
 * Handles DataBinding inflation, binds the lifecycle owner and exposes
 * [initView] / [initObservers] hooks so subclasses stay lean.
 *
 * Usage:
 * ```
 * class MainShellActivity : BaseActivity<ActivityMainShellBinding>() {
 *     override val layoutId = R.layout.activity_main_shell
 *     override fun initView() { ... }
 * }
 * ```
 */
abstract class BaseActivity<DB : ViewDataBinding> : AdAwareActivity() {

    protected lateinit var binding: DB
        private set

    /** Layout resource that is wrapped in a `<layout>` tag for DataBinding. */
    @get:LayoutRes
    protected abstract val layoutId: Int

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLocale()
        applyTheme(PreferenceStore.selectedTheme(this).ifEmpty { THEME_LIGHT })
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, layoutId)

        logKeyEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
        binding.lifecycleOwner = this

        // Keep native-ad colors in sync with the active light/dark mode.
        updateNativeAdTheme(AdPreferenceStore.getInstance(this), PreferenceStore.selectedTheme(this).ifEmpty { THEME_SYSTEM })

        // Whole-app back-press → back interstitial, then finish. Registered here
        // (before initView) so any custom OnBackPressedCallback a subclass adds
        // in initView() is enqueued later and takes priority over this fallback.
        // Note: forward/back interstitials are preloaded in AdAwareActivity, so we
        // don't preload again here.
        onBackPressedDispatcher.addCallback(this, backAdCallback)

        initView()
        initObservers()

        // Auto on-load bottom banner for any screen whose layout includes
        // @layout/include_bottom_banner (no-op otherwise).
        showBottomBanner()
    }

    /** Default back-press handler for the whole app: back-ad then [performBack]. */
    private val backAdCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    /**
     * Shows the back interstitial (when the remote-config gates allow it) and
     * then runs [performBack]. Toolbar back buttons can call this instead of
     * `finish()` to also surface a back ad.
     */
    protected fun goBack() {
        ExitInterstitialAd().presentBackInterstitial(this) { performBack() }
    }

    /** What "back" does after the ad — defaults to finishing. Override for custom nav. */
    protected open fun performBack() {
        if (!isFinishing) finish()
    }

    /**
     * Auto-loads the on-load bottom banner — but only if this screen's layout
     * includes `@layout/include_bottom_banner` (ids `bannerAdFrame` + `bannerShimmer`).
     * Called automatically after [initView]; screens without the include are a
     * no-op. The screen key is the activity's simple class name (e.g.
     * "SpamListActivity"), which must match a key under `ScreenAds` in Remote
     * Config — otherwise it falls back to `ScreenAds.default`. Banner-first; a
     * native banner is shown if the banner fails.
     *
     * To put a banner on any screen: just add the include to its layout. No
     * Kotlin change needed. Override to customise.
     */
    protected open fun showBottomBanner() {
        val container = binding.root.findViewById<FrameLayout>(R.id.bannerAdFrame) ?: return
        val shimmer = binding.root.findViewById<ShimmerFrameLayout>(R.id.bannerShimmer)
        ScreenPlacementPlan.showAd(this::class.java.simpleName, this, container, shimmer)
        // The hairline above the slot only exists to fence off an advert — drop it
        // whenever the slot ends up empty (ads off, show:false, load failure).
        binding.root.findViewById<View>(R.id.adBannerDivider)?.followAdContainer(container)
    }

    /** Set up views, listeners, adapters. */
    protected open fun initView() {}

    /** Subscribe to ViewModel LiveData / Flows. */
    protected open fun initObservers() {}

    // --- Shared runtime-permission handling ---

    /**
     * Requests [permission] through [launcher], but once the user has denied it twice
     * (permanently denied — the system no longer shows its dialog), opens the app's
     * settings page so they can enable it manually.
     */
    protected fun requestPermissionManaged(
        permission: String,
        launcher: ActivityResultLauncher<String>
    ) {
        val prefs = SettingsRepository(this)
        when {
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                launcher.launch(permission)
            }
            ActivityCompat.shouldShowRequestPermissionRationale(this, permission) ->
                launcher.launch(permission)
            else -> openAppSettings()
        }
    }

    /** Opens this app's system settings (App info) screen. */
    protected fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    // --- Shared direct-calling (CALL_PHONE) ---

    private var pendingCallNumber: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        logPermissionResult(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) startCall(number) else openDialer(number)
    }

    /** Places the call directly (CALL_PHONE), requesting the permission if needed. */
    protected fun placeCall(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCall(number)
        } else {
            pendingCallNumber = number
            requestPermissionManaged(Manifest.permission.CALL_PHONE, callPermissionLauncher)
        }
    }

    private fun startCall(number: String) {
        val placed = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))); true
        }.getOrDefault(false)
        if (!placed) openDialer(number)
    }

    private fun openDialer(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }

    override fun onResume() {
        super.onResume()
        applyLocale()
        TransitionInterstitialAd.resumeAfterSettings(this)
        // NOTE: no LightHouse.syncPermissionsAsync() here — the SDK syncs
        // permissions internally (≥0.6.4), so an explicit call is redundant.
    }
    /** Applies the saved app language ([SettingsRepository.languageTag]) via AppCompat. */
    protected fun applyLocale() {
        val savedLang = PreferenceStore.selectedLanguage(this)
        if (savedLang.isEmpty()) return

        val normalizedLang = if (savedLang == "in") "id" else savedLang
        val current = resources.configuration.locales[0].language

        if (normalizedLang != current) {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(normalizedLang)
            )
        }
    }

    /** Applies the saved night-mode ([SettingsRepository.themeMode]) app-wide. */
    protected open fun applyTheme(theme: String) {
        when (theme) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
    private fun updateNativeAdTheme(adsPref: AdPreferenceStore, theme: String) {
        val modeKey = when (theme) {
            THEME_LIGHT -> "NativeLight"
            THEME_DARK -> "NativeDark"
            THEME_SYSTEM -> {
                val isSystemDark =
                    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                            Configuration.UI_MODE_NIGHT_YES
                if (isSystemDark) "NativeDark" else "NativeLight"
            }

            else -> "NativeLight"
        }

        try {
            // Load saved marketing and default theme JSONs
            val marketingJson = JSONObject(adsPref.getString("NativeTheme_marketing", "{}"))
            val defaultJson = JSONObject(adsPref.getString("NativeTheme_default", "{}"))

            // Choose which theme to apply (marketing preferred if enabled)
            val themeJson = if (adsPref.getBoolean("OnMaketing") && marketingJson.has(modeKey))
                marketingJson.optJSONObject(modeKey)
            else
                defaultJson.optJSONObject(modeKey)

            themeJson?.let {
                adsPref.putString("NativebtnColor", it.optString("btnColor"))
                adsPref.putString("NativebtntxtColor", it.optString("btnText"))
                adsPref.putString("NativeBgColor", it.optString("bgColor"))
                adsPref.putString("NativetxtColor", it.optString("textColor"))
            }

            Log.d("NativeTheme", "Applied $modeKey theme to ads dynamically")
        } catch (e: Exception) {
            Log.e("NativeTheme", "Error applying native theme dynamically", e)
        }
    }

}
