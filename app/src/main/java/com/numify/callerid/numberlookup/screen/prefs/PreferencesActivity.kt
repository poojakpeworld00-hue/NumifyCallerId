package com.numify.callerid.numberlookup.screen.prefs

import android.app.role.RoleManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.adkit.policy.AdsPrefStore
import com.numify.callerid.adkit.runtime.OpenAdManager
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.numberlookup.BuildConfig
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.screen.HomeShellActivity
import com.numify.callerid.numberlookup.store.SettingsVault
import com.numify.callerid.numberlookup.databinding.ScreenSettingsBinding
import com.numify.callerid.numberlookup.databinding.CellPrefCardBinding
import com.numify.callerid.numberlookup.databinding.CellSettingRowBinding
import com.numify.callerid.numberlookup.screen.shared.SpotlightOverlay
import com.numify.callerid.numberlookup.screen.locale.LocalePickerActivity
import com.numify.callerid.numberlookup.screen.locale.LocaleCatalog
import com.numify.callerid.numberlookup.screen.utility.SimDetailsActivity
import com.numify.callerid.numberlookup.kit.LocalPrefs
import com.numify.callerid.numberlookup.kit.CallerIdController
import com.numify.callerid.numberlookup.kit.openActivity
import com.numify.callerid.numberlookup.kit.openPolicyLink
import com.numify.callerid.numberlookup.kit.openTermLink
import com.numify.callerid.numberlookup.kit.rateApp
import com.numify.callerid.numberlookup.kit.shareApp

class PreferencesActivity : CoreActivity<ScreenSettingsBinding>() {

    override val layoutId: Int = R.layout.screen_settings

    /** Theme segment order — must match cardTheme's segLight / segDark / segSystem. */
    private val themeOptions =
        listOf(LocalPrefs.THEME_LIGHT, LocalPrefs.THEME_DARK, LocalPrefs.THEME_SYSTEM)

    /** Guards the switch listener while we set its state programmatically. */
    private var isProgrammatic = false

    private val prefs by lazy { SettingsVault(this) }

    /** Re-syncs the call-screening switch after the role-request dialog returns. */
    private val screeningLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshCallScreeningCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Native ad at the top of the settings list (bottom adaptive banner auto-loads via CoreActivity).
        NativeAdLoader().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)

        // Preferences grid — Theme is an inline segmented toggle.
        setupThemeToggle()
        bindPanel(
            binding.panelLanguage, R.drawable.glyph_language, R.drawable.shape_cid_chip_teal,
            R.color.cid_teal, R.string.settings_language, currentLanguageName()
        ) {
            openActivity(LocalePickerActivity.newIntent(this, standalone = true))
        }
        bindPanel(
            binding.panelBlocklist, R.drawable.prefs_blocklist, R.drawable.shape_cid_chip_clay,
            R.color.cid_clay, R.string.settings_blocklist, getString(R.string.settings_blocklist_sub)
        ) {
            openActivity(
                Intent(this, HomeShellActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(HomeShellActivity.EXTRA_OPEN_BLOCKLIST, true)
            )
        }
        bindPanel(
            binding.panelSim, R.drawable.glyph_sim_card, R.drawable.shape_cid_chip_amber,
            R.color.cid_amber, R.string.settings_sim, getString(R.string.settings_sim_sub)
        ) { openSimManagement() }

        // Call-screening toggle — backed by the Android 10+ CallScreening role.
        setupCallScreening()

        // Account & support
        // Rate-us row is gated by the `is_rateus` Remote Config flag: true (or
        // unset) → visible, false → gone.
        val showRate = AdsPrefStore.getInstance(this).getBoolean("is_rateus", true)
        binding.rowRateVw.root.visibility = if (showRate) View.VISIBLE else View.GONE
        binding.rateDividerVw.visibility = if (showRate) View.VISIBLE else View.GONE
        if (showRate) {
            bindRow(
                binding.rowRateVw,
                R.drawable.glyph_star,
                R.drawable.shape_cid_chip_amber,
                R.color.cid_amber,
                R.string.settings_rate,
                R.string.settings_rate_sub
            ) {
                rateApp()
            }
        }
        bindRow(
            binding.rowShareVw,
            R.drawable.prefs_share,
            R.drawable.shape_cid_chip_teal,
            R.color.cid_teal,
            R.string.settings_share,
            R.string.settings_share_sub
        ) {
            shareApp()
        }

        // Legal
        binding.rowPrivacyVw.picIcon.setImageResource(R.drawable.glyph_policy)
        chipIcon(binding.rowPrivacyVw.picIcon, R.drawable.shape_cid_chip_brand, R.color.primary)
        binding.rowPrivacyVw.lblTitle.setText(R.string.settings_privacy)
        binding.rowPrivacyVw.root.setOnClickListener { openPolicyLink() }
        binding.rowTermsVw.picIcon.setImageResource(R.drawable.glyph_terms)
        chipIcon(binding.rowTermsVw.picIcon, R.drawable.shape_cid_chip_clay, R.color.cid_clay)
        binding.rowTermsVw.lblTitle.setText(R.string.settings_terms)
        binding.rowTermsVw.root.setOnClickListener { openTermLink() }

        binding.lblVersion.text =
            getString(R.string.settings_version_fmt, getString(R.string.home_brand), BuildConfig.VERSION_NAME)

        // First-run coach-mark nudging the user to enable the call-screening toggle.
        maybeShowCallScreeningHint()
    }

    /**
     * Binds one navigable preference row. The icon chip's tint travels with its
     * background so a row can never end up with, say, a teal glyph on a clay chip.
     */
    private fun bindPanel(
        row: CellSettingRowBinding,
        @DrawableRes icon: Int,
        @DrawableRes chip: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        sub: String,
        onClick: () -> Unit
    ) {
        row.picIcon.setImageResource(icon)
        chipIcon(row.picIcon, chip, tint)
        row.lblTitle.setText(title)
        row.lblSub.text = sub
        row.root.setOnClickListener { onClick() }
    }

    private fun bindRow(
        row: CellSettingRowBinding,
        @DrawableRes icon: Int,
        @DrawableRes chip: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        @StringRes sub: Int,
        onClick: () -> Unit
    ) {
        row.picIcon.setImageResource(icon)
        chipIcon(row.picIcon, chip, tint)
        row.lblTitle.setText(title)
        row.lblSub.setText(sub)
        row.root.setOnClickListener { onClick() }
    }

    /**
     * Puts a glyph on a tinted chip.
     *
     * setBackgroundResource() replaces the view's padding with the new drawable's
     * (i.e. none), so the padding has to be re-applied afterwards — without it the
     * glyph fills the whole chip and reads as a solid coloured square.
     */
    private fun chipIcon(view: ImageView, @DrawableRes chip: Int, @ColorRes tint: Int) {
        val pad = view.paddingLeft.takeIf { it > 0 }
            ?: (8f * resources.displayMetrics.density).toInt()
        view.setBackgroundResource(chip)
        view.setPadding(pad, pad, pad, pad)
        view.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, tint))
    }

    override fun onResume() {
        super.onResume()
        refreshCallScreeningCard()
    }

    // ── Call Screening (Android 10+ CallScreening role) ───────────────────

    /** Wires the switch, hiding the whole card where the role isn't available. */
    private fun setupCallScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            showCallScreeningSection(false)
            return
        }
        val rm = getSystemService(RoleManager::class.java)
        if (rm == null || !rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            showCallScreeningSection(false)
            return
        }
        refreshCallScreeningCard()
        binding.swcCallScreening.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammatic) return@setOnCheckedChangeListener
            if (isChecked) requestCallScreening() else openDefaultAppsSettings()
        }
    }

    /**
     * First-run coach-mark: dims the whole Settings screen, spotlights the
     * call-screening card through the scrim, and shows a hint bubble beneath it
     * nudging the user to turn the toggle on. Shown only once (persisted via
     * [SettingsVault.isCallScreeningHintShown]); a tap anywhere dismisses it.
     *
     * Skipped when the card is hidden (role unavailable / pre-Android 10) or the
     * toggle is already on.
     */
    private fun maybeShowCallScreeningHint() {
        if (prefs.isCallScreeningHintShown) return
        if (binding.panelCallScreening.visibility != View.VISIBLE) return
        if (binding.swcCallScreening.isChecked) return

        val card = binding.panelCallScreening
        // Wait for layout (native ad above can shift positions), scroll the card
        // fully into view, then spotlight it on the next frame.
        binding.settingsScrollVw.post {
            if (isFinishing || isDestroyed) return@post
            val pad = (24 * resources.displayMetrics.density).toInt()
            binding.settingsScrollVw.scrollTo(0, (card.top - pad).coerceAtLeast(0))
            card.post {
                if (isFinishing || isDestroyed) return@post
                if (binding.swcCallScreening.isChecked) return@post
                prefs.isCallScreeningHintShown = true
                SpotlightOverlay.show(this, card, R.layout.part_call_screening_hint)
            }
        }
    }

    /**
     * Syncs the CallScreening card to the current role state: the switch mirrors
     * whether the role is held, and — once Caller ID is enabled — the whole card
     * is hidden (nothing left to manage). It shows only while Caller ID is still
     * off, and stays hidden where the role isn't available at all.
     */
    private fun refreshCallScreeningCard() {
        if (!CallerIdController.isRoleAvailable(this)) {
            showCallScreeningSection(false)
            return
        }
        val enabled = CallerIdController.isCallerIdEnabled(this)
        showCallScreeningSection(!enabled)
        isProgrammatic = true
        binding.swcCallScreening.isChecked = enabled
        isProgrammatic = false
    }

    /**
     * Shows/hides the whole "Caller protection" section — its heading row and the
     * group card, not just the row inside it. The card is the section's only row,
     * so hiding the row alone leaves the heading above an empty white card.
     */
    private fun showCallScreeningSection(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        binding.secCallHeaderVw.visibility = visibility
        binding.secCallCardVw.visibility = visibility
        binding.panelCallScreening.visibility = visibility
    }

    /** Launches the system role-request dialog for call screening. */
    private fun requestCallScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            refreshCallScreeningCard(); return
        }
        OpenAdManager.skipNextAppOpenAd = true
        screeningLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    /** The role can't be revoked in-app — send the user to default-apps settings. */
    private fun openDefaultAppsSettings() {
        OpenAdManager.skipNextAppOpenAd = true
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun currentLanguageName(): String =
        LocaleCatalog.all.firstOrNull { it.tag == LocalPrefs.selectedLanguage(this) }?.nativeName
            ?: LocaleCatalog.all.first().nativeName

    /** Opens the system mobile-network screen, falling back to our in-app SIM info. */
    private fun openSimManagement() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        }.isSuccess
        if (!opened) openActivity<SimDetailsActivity>()
    }


    /** Inline Light / Dark / System segmented toggle inside the Theme card. */
    private fun setupThemeToggle() {
        val card = binding.panelTheme
        val cells =
            listOf(card.segLightVw, card.segDarkVw, card.segSystemVw) // matches themeOptions order
        val current = LocalPrefs.selectedTheme(this).ifEmpty { LocalPrefs.THEME_LIGHT }
        highlightTheme(cells, themeOptions.indexOf(current).coerceAtLeast(0))
        showThemeName(current)

        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener {
                highlightTheme(cells, index)
                val theme = themeOptions[index]
                showThemeName(theme)
                if (theme != LocalPrefs.selectedTheme(this)) {
                    LocalPrefs.setTheme(
                        this,
                        theme
                    )              // store CoreActivity.applyTheme reads
                    AppCompatDelegate.setDefaultNightMode(nightModeFor(theme)) // recreates activities
                }
            }
        }
    }

    /** Names the active theme under the "Theme" title, so the row says what it is
     *  set to without the user having to read which segment looks selected. */
    private fun showThemeName(theme: String) {
        binding.lblThemeValue.setText(
            when (theme) {
                LocalPrefs.THEME_DARK -> R.string.theme_dark
                LocalPrefs.THEME_SYSTEM -> R.string.theme_system
                else -> R.string.theme_light
            }
        )
    }

    /** Maps an LocalPrefs theme string to its AppCompat night-mode constant. */
    private fun nightModeFor(theme: String): Int = when (theme) {
        LocalPrefs.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        LocalPrefs.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    private fun highlightTheme(cells: List<ImageView>, selected: Int) {
        cells.forEachIndexed { i, cell ->
            val active = i == selected
            cell.setBackgroundResource(if (active) R.drawable.shape_theme_selected else 0)
            val color = ContextCompat.getColor(
                this, if (active) R.color.white else R.color.on_surface_variant
            )
            cell.imageTintList = ColorStateList.valueOf(color)
        }
    }
}
