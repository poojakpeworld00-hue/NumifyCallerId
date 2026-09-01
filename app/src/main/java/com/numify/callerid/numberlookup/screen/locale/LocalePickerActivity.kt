package com.numify.callerid.numberlookup.screen.locale

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.adkit.runtime.interstitial.StandardInterstitial
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.store.RegionProbe
import com.numify.callerid.numberlookup.store.LanguageStore
import com.numify.callerid.numberlookup.store.SettingsVault
import com.numify.callerid.numberlookup.databinding.ScreenLanguageBinding
import com.numify.callerid.numberlookup.access.AccessEngine
import com.numify.callerid.numberlookup.screen.HomeShellActivity
import com.numify.callerid.numberlookup.screen.gate.FlowFooterAd
import com.numify.callerid.numberlookup.screen.gate.OnboardingFlowConfig
import com.numify.callerid.numberlookup.kit.LocalPrefs
import com.numify.callerid.numberlookup.kit.EdgeInsets
import kotlinx.coroutines.launch
import com.numify.callerid.numberlookup.kit.followAdContainer

class LocalePickerActivity : CoreActivity<ScreenLanguageBinding>() {

    override val layoutId: Int = R.layout.screen_language

    private val viewModel: LocalePickerViewModel by viewModels()
    private val prefs by lazy { SettingsVault(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    // Two lists share one selection: a compact "Suggested" group and the full
    // "All languages" group. Both adapters observe the same selectedTag.
    private lateinit var suggestedAdapter: LocaleAdapter
    private lateinit var allAdapter: LocaleAdapter

    /** One-shot guard so Continue/back/autonext can't fire the forward flow twice. */
    private var forwarding = false

    private val autonextHandler = Handler(Looper.getMainLooper())

    override fun initView() {
        // Count this as an intro show only in the first-run flow (not when opened
        // from Settings to change language) — drives the once/count frequency gate.
        if (!standalone) OnboardingFlowConfig.markShown(this, OnboardingFlowConfig.LANGUAGE_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(binding.languageRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Current language comes from LocalPrefs (the store CoreActivity.applyLocale reads).
        // First launch (no saved language) → "Default" (follow system).
        val current = LocalPrefs.language(this) ?: LocalPrefs.LANGUAGE_DEFAULT
        viewModel.init(current)

        // Name the language that is live right now, under the title. Falls back to
        // the raw tag if the current one is not in the catalogue (a locale carried
        // over from an older build, say) — better than showing nothing.
        val currentName = LocaleCatalog.all.firstOrNull { it.tag == current }?.nativeName ?: current
        binding.lblSubtitle.text = "${getString(R.string.language_current)}: $currentName"

        // Mid native ad shown above the Continue button.
        FlowFooterAd.render(
            activity = this,
            screenKey = OnboardingFlowConfig.LANGUAGE_KEY,
            container = binding.adNativeFrameVw,
            shimmer = binding.adShimmerVw,
            divider = binding.adNativeDividerVw,
            fallbackType = "BigNative",
        )

        // 1) Resolve the region FIRST, before the lists exist. The device seed is
        //    synchronous, so viewModel.suggested/others already hold the correct,
        //    region-specific groups by the time the adapters observe them — the
        //    Suggested list is right on the very first frame (no default flash).
        //    The IP refine is async and updates the lists if it disagrees.
        resolveRegion()

        // 2) Now build the lists. The adapters observe suggested/others (wired in
        //    initObservers), so they render the region-correct list immediately.
        val onPick: (LocaleOption) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }
        allAdapter = LocaleAdapter(onPick).apply { setCurrent(current) }

        binding.rollSuggested.layoutManager = LinearLayoutManager(this)
        binding.rollSuggested.adapter = suggestedAdapter
        binding.rollSuggested.addItemDecoration(
            LocaleDividerDecoration(binding.rollSuggested)
        )

        binding.rollLanguages.layoutManager = LinearLayoutManager(this)
        binding.rollLanguages.adapter = allAdapter
        binding.rollLanguages.addItemDecoration(
            LocaleDividerDecoration(binding.rollLanguages)
        )

        binding.padBack.setOnClickListener { goBack() }
        binding.padInfo.setOnClickListener { showInfoDialog() }
        binding.padContinue.setOnClickListener {
            if (forwarding) return@setOnClickListener
            forwarding = true
            onContinue()
        }

        val step = OnboardingFlowConfig.stepConfig(this, OnboardingFlowConfig.LANGUAGE_KEY)

        // First-run flow: `screen.language.onBackPerformNext` (Remote Config) decides
        // whether system back advances forward exactly like Continue (true — the
        // callback stays enabled so back never falls through to CoreActivity's exit
        // handler; `forwarding` blocks re-entry) or is left to behave normally
        // (false). Standalone (opened from Settings) always keeps normal back = return.
        if (!standalone && (step?.onBackPerformNext ?: true)) {
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (forwarding) return
                    forwarding = true
                    onContinue()
                }
            })
        }

        // `screen.language.autonext` (seconds, 0 = disabled): auto-advance exactly
        // like Continue if the user hasn't interacted by then.
        val autonextSec = step?.autonextSec ?: 0
        if (!standalone && autonextSec > 0) {
            autonextHandler.postDelayed({
                if (forwarding) return@postDelayed
                forwarding = true
                onContinue()
            }, autonextSec * 1000L)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        autonextHandler.removeCallbacksAndMessages(null)
    }

    override fun initObservers() {
        viewModel.selectedTag.observe(this) { tag ->
            suggestedAdapter.setSelected(tag)
            allAdapter.setSelected(tag)
            popConfirm()
        }
        // GEO-driven groups: Suggested reflects the user's region, All holds the rest.
        viewModel.suggested.observe(this) { suggestedAdapter.submitList(it) }
        viewModel.others.observe(this) { allAdapter.submitList(it) }
    }

    /**
     * Resolves the region that drives the Suggested group, checking the country
     * BEFORE the lists are populated:
     *  1. Seed synchronously from the device (SIM/network/locale) — offline, instant,
     *     so the first rendered list is already region-correct.
     *  2. Refine asynchronously from IP geo; updates the lists only if it differs
     *     (commitCountry is idempotent per country).
     */
    private fun resolveRegion() {
        val device = deviceCountry()
        EdgeInsets.log(TAG, "resolveRegion: device=$device (sync seed)")
        viewModel.commitCountry(device)
        detectCountryByIp()
    }

    /**
     * The device's region as an ISO-3166 alpha-2 code, preferring the SIM/network
     * country (strongest offline geo signal) and falling back to the app locale.
     * Null when nothing usable is available.
     */
    private fun deviceCountry(): String? {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.takeIf { it.isNotBlank() }
        val network = tm?.networkCountryIso?.takeIf { it.isNotBlank() }
        val locale = resources.configuration.locales[0].country.takeIf { it.isNotBlank() }
        return (sim ?: network ?: locale)?.uppercase()
    }

    /** Best-effort IP geolocation to refine the suggested languages for this region. */
    private fun detectCountryByIp() {
        lifecycleScope.launch {
            val geo = RegionProbe.detectCountry(this@LocalePickerActivity) ?: run {
                EdgeInsets.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            EdgeInsets.log(TAG, "IP refine → country=${geo.iso}")
            viewModel.commitCountry(geo.iso)
        }
    }

    /** Small spring on the confirm button each time the selection changes. */
    private fun popConfirm() {
        binding.padContinue.animate().cancel()
        binding.padContinue.scaleX = 0.8f
        binding.padContinue.scaleY = 0.8f
        binding.padContinue.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f))
            .setDuration(260L)
            .start()
    }

    private fun showInfoDialog() {
        val view =
            layoutInflater.inflate(R.layout.sheet_language_info, binding.languageRootVw, false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.padGotIt).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onContinue() {
        val tag = viewModel.selectedTag.value ?: LocalPrefs.LANGUAGE_DEFAULT
        LocalPrefs.setLanguage(this, tag)  // source of truth for Splash + CoreActivity.applyLocale
        prefs.isLanguageSelected = true

        // Opened from Settings: just apply and return; don't drive the first-run flow.
        if (standalone) {
            LanguageStore.apply(tag) // recreates activities with the new locale
            finish()
            return
        }

        // First-run flow. Show the permission(s) FIRST, then apply the locale and
        // navigate in the completion callback. Applying the locale recreates this
        // Activity, and finishing it early tears it down — either one aborts an
        // in-flight permission request (that's why nothing showed and the app
        // closed). So we defer BOTH until the engine reports it's done.
        AccessEngine.checkScreenPermissions(this, OnboardingFlowConfig.LANGUAGE_KEY) {
            // Next screen: the first eligible entry in screen_order after "language"
            // (walks straight past a disabled/ineligible fsi_permission etc.), else Home.
            val nextKey = OnboardingFlowConfig.nextEligibleAfter(this, OnboardingFlowConfig.LANGUAGE_KEY)
            val nextClass = nextKey?.let { OnboardingFlowConfig.classFor(it) } ?: HomeShellActivity::class.java
            val intent = Intent(this, nextClass)
            val proceed: () -> Unit = {
                LanguageStore.apply(tag) // recreates activities with the new locale
                startActivity(intent)
                finish()
            }
            // Permission done → show the interstitial (only when
            // `screen.language.isInterShow` is on) → THEN apply the locale and
            // navigate. LanguageStore.apply recreates this Activity, so it must run
            // after the ad (doing it earlier would tear the ad down).
            val isInterShow = OnboardingFlowConfig.stepConfig(this, OnboardingFlowConfig.LANGUAGE_KEY)
                ?.isInterShow ?: false
            if (isInterShow) {
                StandardInterstitial().presentInterstitial(this) { proceed() }
            } else {
                proceed()
            }
        }
    }

    companion object {
        private const val TAG = "LocalePickerActivity"
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** Standalone = opened from Settings to change language (returns on Continue). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, LocalePickerActivity::class.java)
                .putExtra(EXTRA_STANDALONE, standalone)
    }
}
