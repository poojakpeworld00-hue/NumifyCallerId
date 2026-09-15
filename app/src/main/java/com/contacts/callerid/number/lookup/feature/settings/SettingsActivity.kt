package com.contacts.callerid.number.lookup.feature.settings

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
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore
import com.contacts.callerid.number.lookup.monetize.delivery.AppOpenAdManager
import com.contacts.callerid.number.lookup.monetize.delivery.NativeAdPresenter
import com.contacts.callerid.number.lookup.BuildConfig
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.feature.MainShellActivity
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.databinding.ActivitySettingsBinding
import com.contacts.callerid.number.lookup.databinding.ItemPrefCardBinding
import com.contacts.callerid.number.lookup.databinding.ItemPrefTileBinding
import com.contacts.callerid.number.lookup.databinding.ItemSettingRowBinding
import com.contacts.callerid.number.lookup.feature.widgets.CoachMarkOverlay
import com.contacts.callerid.number.lookup.feature.language.LanguagePickerActivity
import com.contacts.callerid.number.lookup.feature.language.LocaleCatalog
import com.contacts.callerid.number.lookup.feature.tools.SimInfoActivity
import com.contacts.callerid.number.lookup.common.PreferenceStore
import com.contacts.callerid.number.lookup.common.CallerIdCoordinator
import com.contacts.callerid.number.lookup.common.openActivity
import com.contacts.callerid.number.lookup.common.openPolicyLink
import com.contacts.callerid.number.lookup.common.openTermLink
import com.contacts.callerid.number.lookup.common.rateApp
import com.contacts.callerid.number.lookup.common.shareApp
import com.contacts.callerid.number.lookup.feature.assistant.AiSettingsActivity

class SettingsActivity : BaseActivity<ActivitySettingsBinding>() {

    override val layoutId: Int = R.layout.activity_settings

    /** Theme segment order — must match cardTheme's segLight / segDark / segSystem. */
    private val themeOptions =
        listOf(PreferenceStore.THEME_LIGHT, PreferenceStore.THEME_DARK, PreferenceStore.THEME_SYSTEM)

    /** Guards the switch listener while we set its state programmatically. */
    private var isProgrammatic = false

    private val prefs by lazy { SettingsRepository(this) }

    /** Re-syncs the call-screening switch after the role-request dialog returns. */
    private val screeningLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshCallScreeningCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }

        // Native ad at the top of the settings list (bottom adaptive banner auto-loads via BaseActivity).
        NativeAdPresenter().displayMediumNative(this, binding.adNativeFrame, binding.adShimmer)

        // Preferences grid — Theme is an inline segmented toggle.
        setupThemeToggle()
        bindPanel(
            binding.cardLanguage, R.drawable.ic_language, R.drawable.bg_cid_chip_teal,
            R.color.cid_teal, R.string.settings_language, currentLanguageName()
        ) {
            openActivity(LanguagePickerActivity.newIntent(this, standalone = true))
        }
        bindPanel(
            binding.cardAiSettings, R.drawable.ic_ai_sparkle, R.drawable.bg_cid_chip_brand,
            R.color.primary, R.string.settings_ai, getString(R.string.settings_ai_sub)
        ) {
            openActivity(AiSettingsActivity.newIntent(this))
        }
        bindPanel(
            binding.cardBlocklist, R.drawable.settings_blocklist, R.drawable.bg_cid_chip_clay,
            R.color.cid_clay, R.string.settings_blocklist, getString(R.string.settings_blocklist_sub)
        ) {
            openActivity(
                Intent(this, MainShellActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainShellActivity.EXTRA_OPEN_BLOCKLIST, true)
            )
        }
        bindPanel(
            binding.cardSim, R.drawable.ic_sim_card, R.drawable.bg_cid_chip_amber,
            R.color.cid_amber, R.string.settings_sim, getString(R.string.settings_sim_sub)
        ) { openSimManagement() }

        // Call-screening toggle — backed by the Android 10+ CallScreening role.
        setupCallScreening()

        // Account & support
        // Rate-us row is gated by the `is_rateus` Remote Config flag: true (or
        // unset) → visible, false → gone.
        val showRate = AdPreferenceStore.getInstance(this).getBoolean("is_rateus", true)
        binding.columnRate.root.visibility = if (showRate) View.VISIBLE else View.GONE
        binding.rateDivider.visibility = if (showRate) View.VISIBLE else View.GONE
        if (showRate) {
            bindRow(
                binding.columnRate,
                R.drawable.ic_star,
                R.drawable.bg_cid_chip_amber,
                R.color.cid_amber,
                R.string.settings_rate,
                R.string.settings_rate_sub
            ) {
                rateApp()
            }
        }
        bindRow(
            binding.columnShare,
            R.drawable.settings_share,
            R.drawable.bg_cid_chip_teal,
            R.color.cid_teal,
            R.string.settings_share,
            R.string.settings_share_sub
        ) {
            shareApp()
        }

        // Legal
        binding.columnPrivacy.imageIcon.setImageResource(R.drawable.ic_policy)
        chipIcon(binding.columnPrivacy.imageIcon, R.drawable.bg_cid_chip_brand, R.color.primary)
        binding.columnPrivacy.textTitle.setText(R.string.settings_privacy)
        binding.columnPrivacy.root.setOnClickListener { openPolicyLink() }
        binding.columnTerms.imageIcon.setImageResource(R.drawable.ic_terms)
        chipIcon(binding.columnTerms.imageIcon, R.drawable.bg_cid_chip_clay, R.color.cid_clay)
        binding.columnTerms.textTitle.setText(R.string.settings_terms)
        binding.columnTerms.root.setOnClickListener { openTermLink() }

        binding.textVersion.text =
            getString(R.string.settings_version_fmt, getString(R.string.home_brand), BuildConfig.VERSION_NAME)

        // First-run coach-mark nudging the user to enable the call-screening toggle.
        maybeShowCallScreeningHint()
    }

    /**
     * Binds one preference tile in the 2x2 grid. The icon chip's tint travels
     * with its background, so a tile can never end up with, say, a teal glyph on
     * a clay chip.
     *
     * item_pref_tile keeps item_setting_row's view ids, so the two are bound the
     * same way and a destination can move between the grid and a list without
     * its binding changing — only the type of the include does.
     */
    private fun bindPanel(
        tile: ItemPrefTileBinding,
        @DrawableRes icon: Int,
        @DrawableRes chip: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        sub: String,
        onClick: () -> Unit
    ) {
        tile.imageIcon.setImageResource(icon)
        chipIcon(tile.imageIcon, chip, tint)
        tile.textTitle.setText(title)
        tile.textSub.text = sub
        tile.root.setOnClickListener { onClick() }
    }

    private fun bindRow(
        row: ItemSettingRowBinding,
        @DrawableRes icon: Int,
        @DrawableRes chip: Int,
        @ColorRes tint: Int,
        @StringRes title: Int,
        @StringRes sub: Int,
        onClick: () -> Unit
    ) {
        row.imageIcon.setImageResource(icon)
        chipIcon(row.imageIcon, chip, tint)
        row.textTitle.setText(title)
        row.textSub.setText(sub)
        row.root.setOnClickListener { onClick() }
    }

    /**
     * Places a glyph on a tinted chip.
     *
     * setBackgroundResource() swaps the view's padding for the new drawable's own,
     * which is none, so the padding has to be put back afterwards. Without that
     * the glyph fills the entire chip and reads as a solid coloured square.
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
        binding.switchCallScreening.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammatic) return@setOnCheckedChangeListener
            if (isChecked) requestCallScreening() else openDefaultAppsSettings()
        }
    }

    /**
     * First-run coach-mark: it dims the whole Settings screen, spotlights the
     * call-screening card through the scrim, and floats a hint bubble beneath it
     * encouraging the user to turn the toggle on. It appears only once, persisted
     * through [SettingsRepository.isCallScreeningHintShown], and a tap anywhere
     * dismisses it.
     *
     * It is skipped when the card is hidden - the role being unavailable, or a
     * pre-Android 10 device - and when the toggle is already on.
     */
    private fun maybeShowCallScreeningHint() {
        if (prefs.isCallScreeningHintShown) return
        if (binding.cardCallScreening.visibility != View.VISIBLE) return
        if (binding.switchCallScreening.isChecked) return

        val card = binding.cardCallScreening
        // Wait for layout (native ad above can shift positions), scroll the card
        // fully into view, then spotlight it on the next frame.
        binding.settingsScroll.post {
            if (isFinishing || isDestroyed) return@post
            val pad = (24 * resources.displayMetrics.density).toInt()
            binding.settingsScroll.scrollTo(0, (card.top - pad).coerceAtLeast(0))
            card.post {
                if (isFinishing || isDestroyed) return@post
                if (binding.switchCallScreening.isChecked) return@post
                prefs.isCallScreeningHintShown = true
                CoachMarkOverlay.show(this, card, R.layout.include_call_screening_hint)
            }
        }
    }

    /**
     * Brings the CallScreening card in line with the current role state. The
     * switch reflects whether the role is held, and once Caller ID is enabled the
     * card disappears entirely, there being nothing left to manage. It is on
     * screen only while Caller ID is still off, and stays hidden wherever the role
     * is unavailable.
     */
    private fun refreshCallScreeningCard() {
        if (!CallerIdCoordinator.isRoleAvailable(this)) {
            showCallScreeningSection(false)
            return
        }
        val enabled = CallerIdCoordinator.isCallerIdEnabled(this)
        showCallScreeningSection(!enabled)
        isProgrammatic = true
        binding.switchCallScreening.isChecked = enabled
        isProgrammatic = false
    }

    /**
     * Shows or hides the entire "Caller protection" section - its heading row and
     * the group card together, not merely the row inside. The card holds the
     * section's only row, so hiding just that row would leave the heading sitting
     * above an empty white card.
     */
    private fun showCallScreeningSection(show: Boolean) {
        val visibility = if (show) View.VISIBLE else View.GONE
        binding.secCallHeader.visibility = visibility
        binding.secCallCard.visibility = visibility
        binding.cardCallScreening.visibility = visibility
    }

    /** Launches the system role-request dialog for call screening. */
    private fun requestCallScreening() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val rm = getSystemService(RoleManager::class.java) ?: return
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            refreshCallScreeningCard(); return
        }
        AppOpenAdManager.skipNextAppOpenAd = true
        screeningLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
    }

    /** The role can't be revoked in-app — send the user to default-apps settings. */
    private fun openDefaultAppsSettings() {
        AppOpenAdManager.skipNextAppOpenAd = true
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun currentLanguageName(): String =
        LocaleCatalog.all.firstOrNull { it.tag == PreferenceStore.selectedLanguage(this) }?.nativeName
            ?: LocaleCatalog.all.first().nativeName

    /** Opens the system mobile-network screen, falling back to our in-app SIM info. */
    private fun openSimManagement() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
        }.isSuccess
        if (!opened) openActivity<SimInfoActivity>()
    }


    /** Inline Light / Dark / System segmented toggle inside the Theme card. */
    private fun setupThemeToggle() {
        val card = binding.cardTheme
        val cells =
            listOf(card.tabLight, card.tabDark, card.tabSystem) // matches themeOptions order
        val current = PreferenceStore.selectedTheme(this).ifEmpty { PreferenceStore.THEME_LIGHT }
        highlightTheme(cells, themeOptions.indexOf(current).coerceAtLeast(0))
        showThemeName(current)

        cells.forEachIndexed { index, cell ->
            cell.setOnClickListener {
                highlightTheme(cells, index)
                val theme = themeOptions[index]
                showThemeName(theme)
                if (theme != PreferenceStore.selectedTheme(this)) {
                    PreferenceStore.setTheme(
                        this,
                        theme
                    )              // store BaseActivity.applyTheme reads
                    AppCompatDelegate.setDefaultNightMode(nightModeFor(theme)) // recreates activities
                }
            }
        }
    }

    /** Names the active theme under the "Theme" title, so the row says what it is
     *  set to without the user having to read which segment looks selected. */
    private fun showThemeName(theme: String) {
        binding.textThemeValue.setText(
            when (theme) {
                PreferenceStore.THEME_DARK -> R.string.theme_dark
                PreferenceStore.THEME_SYSTEM -> R.string.theme_system
                else -> R.string.theme_light
            }
        )
    }

    /** Maps an PreferenceStore theme string to its AppCompat night-mode constant. */
    private fun nightModeFor(theme: String): Int = when (theme) {
        PreferenceStore.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        PreferenceStore.THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    private fun highlightTheme(cells: List<ImageView>, selected: Int) {
        cells.forEachIndexed { i, cell ->
            val active = i == selected
            cell.setBackgroundResource(if (active) R.drawable.bg_theme_selected else 0)
            val color = ContextCompat.getColor(
                this, if (active) R.color.white else R.color.on_surface_variant
            )
            cell.imageTintList = ColorStateList.valueOf(color)
        }
    }
}
