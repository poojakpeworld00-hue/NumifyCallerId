package com.callerid.numberlookup.home.foundation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.os.LocaleListCompat
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.facebook.shimmer.ShimmerFrameLayout
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.monetize.strategy.recordPermissionOutcome
import com.callerid.numberlookup.home.monetize.delivery.AdAwareActivity
import com.callerid.numberlookup.home.monetize.delivery.fullpage.ExitInterstitialAd
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.common.PreferenceStore
import com.callerid.numberlookup.home.common.Typography
import com.callerid.numberlookup.home.common.followAdContainer
import com.callerid.numberlookup.home.common.PreferenceStore.THEME_DARK
import com.callerid.numberlookup.home.common.PreferenceStore.THEME_LIGHT
import com.callerid.numberlookup.home.common.PreferenceStore.THEME_SYSTEM
import org.json.JSONObject
import java.util.Locale
import kotlin.and
import kotlin.sequences.ifEmpty

/**
 * Base class behind every Activity in the app.
 *
 * It inflates the DataBinding, binds the lifecycle owner, and offers [initView]
 * and [initObservers] hooks so subclasses stay small.
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

    /**
     * Applies the app-wide text scale ([Typography.scaled]) before anything in
     * this Activity resolves a dimension.
     *
     * Here rather than in `onCreate` because the theme, the window decor and
     * every inflated layout read their sizes from the base context, and by the
     * time `onCreate` runs the first of those has already happened.
     *
     * The four screens that draw over other apps — the incoming-call card, the
     * lock-screen alert, the overlay tutorial and the reply picker — extend
     * AppCompatActivity directly and so are deliberately left out: they are
     * fixed-size surfaces sitting on someone else's UI, where growing the type
     * has nowhere to go.
     *
     * A screen built to a pixel-exact design handoff can opt out through
     * [appliesAppTextScale]; the user's own system font size still applies to it.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(if (appliesAppTextScale) Typography.scaled(newBase) else newBase)
    }

    /**
     * Whether this screen takes the app-wide text enlargement.
     *
     * True everywhere except screens transcribed dimension-for-dimension from a
     * design, where growing the type by 8% is the one change that stops it
     * matching. Overriding this does not ignore the user's accessibility font
     * size — it only drops this app's own multiplier on top of it.
     */
    protected open val appliesAppTextScale: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLocale()
        applyTheme(PreferenceStore.selectedTheme(this).ifEmpty { THEME_LIGHT })
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, layoutId)

        recordEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
        binding.lifecycleOwner = this
        applyImmersiveNavigation()
        applySystemBarIcons()

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

    /**
     * Hides the system navigation bar, leaving the status bar alone.
     *
     * `WindowInsetsControllerCompat`, not the `SYSTEM_UI_FLAG_*` constants: those
     * have been deprecated since API 30 and were the only reason the old
     * behaviour needed a per-version branch. The compat controller does the right
     * thing from API 21 up through 36 with one call.
     *
     * **BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE is the half that matters.** Without
     * it the bar is hidden and the first swipe from the bottom edge brings it
     * back permanently, so "immersive" lasts exactly one gesture. With it, the
     * swipe reveals a translucent bar that retreats on its own — the user can
     * always reach Back and Home, they just are not paying for them the rest of
     * the time.
     *
     * Only `navigationBars()`. The status bar carries the clock, the signal and
     * the battery, every screen already pads its header down past it, and hiding
     * it would be a different request.
     *
     * Paired with `setDecorFitsSystemWindows(false)` so the content occupies the
     * space the bar gave up. Every screen here already lays out edge-to-edge and
     * pads itself from the insets it is handed; with the bar gone the bottom
     * inset simply reports 0 and those paddings collapse on their own.
     */
    private fun applyImmersiveNavigation() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Set before hiding: the behaviour governs how the hidden bar comes back,
        // and a hide() issued under the default behaviour is the sticky one.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    }

    /**
     * Re-hides the bar after anything that takes the window's focus away.
     *
     * A permission dialog, the IME, the notification shade, an interstitial, a
     * trip to system Settings — each of them restores the navigation bar on the
     * way out, and without this the app comes back with the bar up and stays
     * that way. Only on regaining focus: re-asserting it while focus is
     * elsewhere fights whatever has it.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveNavigation()
    }

    /**
     * Dark status/navigation icons on a light theme, light ones on dark.
     *
     * The themes already declare `windowLightStatusBar`, but several screens call
     * `enableEdgeToEdge()` in onCreate, and that installs its own SystemBarStyle
     * over whatever the theme asked for. The result was a screen-by-screen
     * lottery: whichever ran last won, so some screens showed white icons on a
     * white bar and were simply invisible.
     *
     * Resolved from the *configuration* rather than from the stored preference,
     * because "System" is a valid choice and only the configuration knows what
     * the system currently is.
     *
     * Screens that deliberately put a dark surface under the status bar — the
     * splash, the blue Lookup hero — override [usesLightSystemBarIcons].
     */
    protected fun applySystemBarIcons() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val light = usesLightSystemBarIcons
        // "Appearance light bars" means the BAR is light, so its icons are dark.
        controller.isAppearanceLightStatusBars = !light
        controller.isAppearanceLightNavigationBars = !light
    }

    /**
     * Whether this screen wants light (white) system-bar icons — true when the
     * surface behind the bars is dark. Defaults to following the theme.
     */
    protected open val usesLightSystemBarIcons: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Default back-press handler for the whole app: back-ad then [performBack]. */
    private val backAdCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    /**
     * Shows the back interstitial, where the Remote Config gates permit it, and
     * then runs [performBack]. Toolbar back buttons can call this in place of
     * `finish()` when they should surface a back ad too.
     */
    protected fun goBack() {
        ExitInterstitialAd().showExitInterstitial(this) { performBack() }
    }

    /** What "back" does after the ad — defaults to finishing. Override for custom nav. */
    protected open fun performBack() {
        if (!isFinishing) finish()
    }

    /**
     * Loads the on-load bottom banner automatically, but only where this screen's
     * layout pulls in `@layout/include_bottom_banner` (ids `bannerAdFrame` and
     * `bannerShimmer`). It runs straight after [initView], and screens without the
     * include do nothing. The screen key is the Activity's simple class name -
     * "BlocklistActivity", say - which has to match a key beneath `ScreenAds` in
     * Remote Config, falling back to `ScreenAds.default` when it does not. Banner
     * first, with a native banner shown if the banner fails.
     *
     * Adding a banner to any screen is therefore just a matter of adding the
     * include to its layout; no Kotlin change is needed. Override to customise.
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
     * Requests [permission] through [launcher]. Once the user has refused twice -
     * the permanently-denied state where the system stops showing its dialog - it
     * opens the app's settings page so they can enable it by hand.
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
            else -> showPermissionBlockedDialog()
        }
    }

    /**
     * Explains a permanently-denied permission without leaving the app.
     *
     * This used to call [openAppSettings] outright, so a third tap threw the user
     * into Android's App info page with no warning. The settings page is still
     * offered — once Android stops showing its dialog it is the only way the
     * permission can be turned back on — but the user chooses to go there rather
     * than arriving.
     */
    protected fun showPermissionBlockedDialog() {
        if (isFinishing || isDestroyed) return
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_blocked_title)
                .setMessage(R.string.perm_blocked_message)
                .setNegativeButton(R.string.perm_blocked_dismiss, null)
                .setPositiveButton(R.string.perm_blocked_open) { _, _ -> openAppSettings() }
                .show()
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
        recordPermissionOutcome(Manifest.permission.CALL_PHONE, granted)
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
        TransitionInterstitialAd.handleSettingsReturn(this)
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
