package com.numify.callerid.lookup.feature.language

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
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
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.monetize.delivery.fullpage.TransitionInterstitialAd
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.RegionDetector
import com.numify.callerid.lookup.repository.LanguageRepository
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.databinding.ActivityLanguageBinding
import com.numify.callerid.lookup.permission.PermissionCoordinator
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.onboarding.OnboardingFooterAd
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.common.PreferenceStore
import com.numify.callerid.lookup.common.WindowInsetsHelper
import kotlinx.coroutines.launch
import java.util.Locale
import com.numify.callerid.lookup.common.followAdContainer

class LanguagePickerActivity : BaseActivity<ActivityLanguageBinding>() {

    override val layoutId: Int = R.layout.activity_language

    private val viewModel: LanguagePickerViewModel by viewModels()
    private val prefs by lazy { SettingsRepository(this) }

    /** True when opened from Settings to change language (vs. the first-run flow). */
    private val standalone by lazy { intent.getBooleanExtra(EXTRA_STANDALONE, false) }

    // Two lists share one selection: a compact "Suggested" group and the full
    // "All languages" group. Both adapters observe the same selectedTag.
    private lateinit var suggestedAdapter: LanguageAdapter
    private lateinit var allAdapter: LanguageAdapter

    /** One-shot guard so Continue/back/autonext can't fire the forward flow twice. */
    private var forwarding = false

    private val autonextHandler = Handler(Looper.getMainLooper())

    override fun initView() {
        // Count this as an intro show only in the first-run flow (not when opened
        // from Settings to change language) — drives the once/count frequency gate.
        if (!standalone) OnboardingStepConfig.markShown(this, OnboardingStepConfig.LANGUAGE_KEY)

        ViewCompat.setOnApplyWindowInsetsListener(binding.languageRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Current language comes from PreferenceStore (the store BaseActivity.applyLocale reads).
        // First launch (no saved language) → "Default" (follow system).
        val current = PreferenceStore.language(this) ?: PreferenceStore.LANGUAGE_DEFAULT
        viewModel.init(current)

        // The live language is named in the title itself rather than on a line of
        // its own, so the header is one thing instead of two.
        binding.textTitle.text = titleWith(currentLanguageName(current))

        // Mid native ad shown above the Continue button.
        OnboardingFooterAd.render(
            activity = this,
            screenKey = OnboardingStepConfig.LANGUAGE_KEY,
            container = binding.adNativeFrame,
            shimmer = binding.adShimmer,
            divider = binding.adNativeDivider,
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
        val onPick: (LanguageOption) -> Unit = { viewModel.select(it.tag) }
        suggestedAdapter = LanguageAdapter(onPick).apply { setCurrent(current) }
        // The row cascade runs once down the whole page rather than restarting per
        // card, so the "all languages" list picks the stagger up two rows in —
        // exactly the `60 * (i + 2)` the design gives its own rows.
        allAdapter = LanguageAdapter(onPick).apply {
            setCurrent(current)
            staggerOffset = SUGGESTED_ROW_COUNT
        }

        binding.listSuggested.layoutManager = LinearLayoutManager(this)
        binding.listSuggested.adapter = suggestedAdapter
        binding.listSuggested.addItemDecoration(
            LanguageDividerDecoration(binding.listSuggested)
        )

        binding.listLanguages.layoutManager = LinearLayoutManager(this)
        binding.listLanguages.adapter = allAdapter
        binding.listLanguages.addItemDecoration(
            LanguageDividerDecoration(binding.listLanguages)
        )

        binding.buttonBack.setOnClickListener { goBack() }
        binding.buttonInfo.setOnClickListener { showInfoDialog() }
        binding.buttonContinue.setOnClickListener {
            if (forwarding) return@setOnClickListener
            forwarding = true
            onContinue()
        }

        val step = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.LANGUAGE_KEY)

        // First-run flow: `screen.language.onBackPerformNext` (Remote Config) decides
        // whether system back advances forward exactly like Continue (true — the
        // callback stays enabled so back never falls through to BaseActivity's exit
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
     * Works out the region behind the Suggested group, settling the country
     * BEFORE the lists are filled:
     *  1. Seed synchronously from the device - SIM, network or locale - which is
     *     offline and instant, so the very first list rendered is already correct
     *     for the region.
     *  2. Refine asynchronously from IP geo, updating the lists only when it
     *     disagrees; confirmCountry is idempotent per country.
     */
    /**
     * "Language: English", with the language name at a smaller size and in the
     * subtle ink — one header line that still reads as a title first.
     *
     * The name is spanned rather than put in its own TextView so the two can
     * never disagree about wrapping: a long endonym flows under the word
     * "Language" instead of being clipped by the Continue pill.
     */
    private fun titleWith(languageName: String?): CharSequence {
        val title = getString(R.string.language_title)
        if (languageName.isNullOrBlank()) return title

        val suffix = ": $languageName"
        return SpannableString(title + suffix).apply {
            val from = title.length
            val to = length
            setSpan(RelativeSizeSpan(TITLE_NAME_SCALE), from, to, SPAN_FLAGS)
            setSpan(ForegroundColorSpan(getColor(R.color.ds_ink_subtle)), from, to, SPAN_FLAGS)
        }
    }

    /**
     * The language the app is actually running in.
     *
     * [PreferenceStore.LANGUAGE_DEFAULT] is the empty string — "follow the
     * system" — and on a first launch that is what is stored. Looking an empty
     * tag up in the catalogue finds nothing, which is why the header used to
     * read "Current language:" with the name missing. So an unset preference
     * resolves through the device locale instead, and only a locale outside the
     * catalogue falls back to the platform's own display name.
     */
    private fun currentLanguageName(current: String): String? {
        val tag = current.takeIf { it.isNotBlank() } ?: Locale.getDefault().language
        LocaleCatalog.all.firstOrNull { it.tag == tag }?.let { return it.nativeName }

        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayLanguage(locale).takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase(locale) }
    }

    private fun resolveRegion() {
        val device = deviceCountry()
        WindowInsetsHelper.log(TAG, "resolveRegion: device=$device (sync seed)")
        viewModel.confirmCountry(device)
        detectCountryByIp()
    }

    /**
     * The device's region as an ISO-3166 alpha-2 code, preferring the SIM or
     * network country as the strongest offline geo signal and falling back to the
     * app locale. Null when nothing usable is available.
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
            val geo = RegionDetector.detectCountry(this@LanguagePickerActivity) ?: run {
                WindowInsetsHelper.log(TAG, "IP geo unavailable → keeping device seed")
                return@launch
            }
            WindowInsetsHelper.log(TAG, "IP refine → country=${geo.iso}")
            viewModel.confirmCountry(geo.iso)
        }
    }

    /** Small spring on the confirm button each time the selection changes. */
    private fun popConfirm() {
        binding.buttonContinue.animate().cancel()
        binding.buttonContinue.scaleX = 0.8f
        binding.buttonContinue.scaleY = 0.8f
        binding.buttonContinue.animate()
            .scaleX(1f).scaleY(1f)
            .setInterpolator(OvershootInterpolator(3f))
            .setDuration(260L)
            .start()
    }

    private fun showInfoDialog() {
        val view =
            layoutInflater.inflate(R.layout.dialog_language_info, binding.languageRoot, false)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        view.findViewById<View>(R.id.buttonGotIt).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.85f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun onContinue() {
        val tag = viewModel.selectedTag.value ?: PreferenceStore.LANGUAGE_DEFAULT
        PreferenceStore.setLanguage(this, tag)  // source of truth for Splash + BaseActivity.applyLocale
        prefs.isLanguageSelected = true

        // Opened from Settings: just apply and return; don't drive the first-run flow.
        if (standalone) {
            LanguageRepository.apply(tag) // recreates activities with the new locale
            finish()
            return
        }

        // First-run flow. Show the permission(s) FIRST, then apply the locale and
        // navigate in the completion callback. Applying the locale recreates this
        // Activity, and finishing it early tears it down — either one aborts an
        // in-flight permission request (that's why nothing showed and the app
        // closed). So we defer BOTH until the engine reports it's done.
        PermissionCoordinator.checkScreenPermissions(this, OnboardingStepConfig.LANGUAGE_KEY) {
            // Next screen: the first eligible entry in screen_order after "language"
            // (walks straight past a disabled/ineligible fsi_permission etc.), else Home.
            val nextKey = OnboardingStepConfig.nextEligibleAfter(this, OnboardingStepConfig.LANGUAGE_KEY)
            val nextClass = nextKey?.let { OnboardingStepConfig.classFor(it) } ?: MainShellActivity::class.java
            val intent = Intent(this, nextClass)
            val proceed: () -> Unit = {
                LanguageRepository.apply(tag) // recreates activities with the new locale
                startActivity(intent)
                finish()
            }
            // Permission done → show the interstitial (only when
            // `screen.language.isInterShow` is on) → THEN apply the locale and
            // navigate. LanguageRepository.apply recreates this Activity, so it must run
            // after the ad (doing it earlier would tear the ad down).
            val isInterShow = OnboardingStepConfig.stepConfig(this, OnboardingStepConfig.LANGUAGE_KEY)
                ?.isInterShow ?: false
            if (isInterShow) {
                TransitionInterstitialAd().showInterstitial(this) { proceed() }
            } else {
                proceed()
            }
        }
    }

    companion object {
        private const val TAG = "LanguagePickerActivity"

        /** Rows in the "Suggested" card, which the second list's stagger follows on from. */
        private const val SUGGESTED_ROW_COUNT = 2
        private const val EXTRA_STANDALONE = "extra_standalone"

        /** The language name against the 26sp title: small enough to read as a note. */
        private const val TITLE_NAME_SCALE = 0.5f

        private const val SPAN_FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

        /** Standalone = opened from Settings to change language (returns on Continue). */
        fun newIntent(context: Context, standalone: Boolean = false): Intent =
            Intent(context, LanguagePickerActivity::class.java)
                .putExtra(EXTRA_STANDALONE, standalone)
    }
}
