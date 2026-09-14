package com.numify.callerid.lookup.feature.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.databinding.ActivityToolsBinding
import com.numify.callerid.lookup.common.openActivity
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.finder.CountryCatalog
import com.numify.callerid.lookup.feature.finder.CountryPickerActivity
import com.numify.callerid.lookup.repository.RegionDetector
import com.numify.callerid.lookup.repository.SettingsRepository
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mini-tools grouped into Assistant · Measure · Device · Time, each category a
 * horizontally scrolling rail, with instant search and a friendly empty state.
 * Each card launches its own activity.
 */
class ToolboxFragment : BaseFragment<ActivityToolsBinding>() {

    /** Tools: mid native above the tools grid. */
    override val screenAdFormat = ScreenAdFormat.MID_NATIVE

    private val prefs by lazy { SettingsRepository(requireContext()) }

    private val adapter = ToolboxAdapter { tool ->
        val intent = Intent(requireContext(), tool.target).apply {
            tool.mode?.let { putExtra(AiScanActivity.EXTRA_MODE, it) }
        }
        // The shell owns the overlay round-trip, because it owns the launcher the
        // Settings page returns through. Outside it — this fragment is only ever
        // a tab today — fall back to the plain ad-then-open path.
        val shell = activity as? MainShellActivity
        if (shell != null) shell.openToolGated(intent) else requireActivity().openActivity(intent)
    }

    /** Full tool set, in display order, with the controlled 6-hue palette. */
    private val tools: List<UtilityUi> by lazy {
        val assistant = getString(R.string.tools_cat_assistant)
        val measure = getString(R.string.tools_cat_measure)
        val device = getString(R.string.tools_cat_device)
        val time = getString(R.string.tools_cat_time)
        listOf(
            UtilityUi(getString(R.string.tools_spam_scan), getString(R.string.tools_spam_scan_sub),
                R.drawable.ic_ai_sparkle, R.drawable.bg_tool_chip_rose, R.color.tool_rose,
                assistant, AiScanActivity::class.java, AiScanActivity.MODE_SPAM),
            UtilityUi(getString(R.string.tools_unsaved), getString(R.string.tools_unsaved_sub),
                R.drawable.ic_ai_sparkle, R.drawable.bg_tool_chip_teal, R.color.tool_teal,
                assistant, AiScanActivity::class.java, AiScanActivity.MODE_UNSAVED),
            UtilityUi(getString(R.string.tools_compass), getString(R.string.tools_compass_sub),
                R.drawable.ic_tool_compass, R.drawable.bg_tool_chip_green, R.color.tool_green,
                measure, CompassActivity::class.java),
            UtilityUi(getString(R.string.tools_level), getString(R.string.tools_level_sub),
                R.drawable.ic_tool_level, R.drawable.bg_tool_chip_sky, R.color.tool_sky,
                measure, SpiritLevelActivity::class.java),
            UtilityUi(getString(R.string.tools_sound), getString(R.string.tools_sound_sub),
                R.drawable.ic_tool_sound, R.drawable.bg_tool_chip_rose, R.color.tool_rose,
                measure, NoiseMeterActivity::class.java),
            UtilityUi(getString(R.string.tools_light), getString(R.string.tools_light_sub),
                R.drawable.ic_tool_light, R.drawable.bg_tool_chip_amber, R.color.tool_amber,
                measure, LightMeterActivity::class.java),
            UtilityUi(getString(R.string.tools_flashlight), getString(R.string.tools_flashlight_sub),
                R.drawable.ic_tool_flashlight, R.drawable.bg_tool_chip_amber, R.color.tool_amber,
                device, FlashlightActivity::class.java),
            UtilityUi(getString(R.string.tools_battery), getString(R.string.tools_battery_sub),
                R.drawable.ic_tool_battery, R.drawable.bg_tool_chip_green, R.color.tool_green,
                device, BatteryInfoActivity::class.java),
            UtilityUi(getString(R.string.tools_sim), getString(R.string.tools_sim_sub),
                R.drawable.ic_tool_network, R.drawable.bg_tool_chip_teal, R.color.tool_teal,
                device, SimInfoActivity::class.java),
            UtilityUi(getString(R.string.tools_speedometer), getString(R.string.tools_speedometer_sub),
                R.drawable.ic_tool_speedometer, R.drawable.bg_tool_chip_rose, R.color.tool_rose,
                device, SpeedometerActivity::class.java),
            UtilityUi(getString(R.string.tools_stopwatch), getString(R.string.tools_stopwatch_sub),
                R.drawable.ic_tool_stopwatch, R.drawable.bg_tool_chip_green, R.color.tool_green,
                time, StopwatchActivity::class.java),
            UtilityUi(getString(R.string.timer_tool), getString(R.string.timer_tool_sub),
                R.drawable.ic_tool_timer, R.drawable.bg_tool_chip_sky, R.color.tool_sky,
                time, TimerActivity::class.java),
        )
    }

    /** Category display order for grouping. */
    private val categoryOrder: List<String> by lazy {
        listOf(
            getString(R.string.tools_cat_assistant),
            getString(R.string.tools_cat_measure),
            getString(R.string.tools_cat_device),
            getString(R.string.tools_cat_time),
        )
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        ActivityToolsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hosted as a tab: the shell owns the bottom nav, so only the top inset applies.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.buttonBack.visibility = View.GONE

        // Vertical list of categories; each row draws its own horizontal rail.
        binding.listTools.layoutManager = LinearLayoutManager(requireContext())
        binding.listTools.adapter = adapter

        setupSearch()
        setupNumberLookup()
        applyQuery("")
    }

    // ── Number lookup ───────────────────────────────────────────────────────
    //
    // Moved off the top of Recents. It never belonged over a call log — that is
    // a list you read, and a field above it competed with the list for the same
    // attention. Here it sits with the other things you go to Tools to *do*.
    //
    // The field does not search in place: submitting hands the number to the
    // Lookup screen, which owns the result card, the history and the reveal.

    /** Dialling code of the country chip (no leading '+'). */
    private var homeDial: String = ""

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(CountryPickerActivity.EXTRA_ISO)
                ?: return@registerForActivityResult
            val dial = data.getStringExtra(CountryPickerActivity.EXTRA_DIAL).orEmpty()
            prefs.homeCountryIso = iso // keep Tools and Lookup on the same country
            applyHomeCountry(iso, dial)
        }
    }

    private fun setupNumberLookup() {
        setupHomeCountry()
        binding.columnLookupCountry.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), CountryPickerActivity::class.java))
        }
        binding.buttonLookupSearch.setOnClickListener { submitLookup() }
        binding.inputLookupNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitLookup(); true
            } else false
        }
        binding.inputLookupNumber.doAfterTextChanged { text ->
            binding.buttonLookupClear.visibility =
                if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        binding.buttonLookupClear.setOnClickListener { binding.inputLookupNumber.setText("") }
    }

    /**
     * Picks the country chip: the saved choice if any, otherwise the device region
     * straight away, refined to the IP-detected country in the background.
     */
    private fun setupHomeCountry() {
        val saved = prefs.homeCountryIso
        if (saved.length == 2) {
            applyHomeCountry(saved, dialFor(saved))
            return
        }
        val sim = simCountryIso()
        if (sim != null) {
            applyHomeCountry(sim, dialFor(sim))
            return
        }
        val region = Locale.getDefault().country
        applyHomeCountry(if (region.length == 2) region else "US", dialFor(region))
        detectCountryByIp()
    }

    /** SIM (then network) registered country as an uppercase ISO-2, or null. */
    private fun simCountryIso(): String? {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        val iso = tm.simCountryIso?.takeIf { it.length == 2 }
            ?: tm.networkCountryIso?.takeIf { it.length == 2 }
        return iso?.uppercase()
    }

    private fun dialFor(iso: String): String =
        (CountryCatalog.byIso(iso)?.dial ?: CountryCatalog.dialOf(iso)).orEmpty()

    /** Resolves the country from the user's IP and updates the chip (best-effort). */
    private fun detectCountryByIp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val geo = RegionDetector.detectCountry(requireContext()) ?: return@launch
            if (view == null || prefs.homeCountryIso.isNotBlank()) return@launch
            applyHomeCountry(geo.iso, geo.dial.ifBlank { dialFor(geo.iso) })
        }
    }

    private fun applyHomeCountry(iso: String, dial: String) {
        homeDial = dial
        binding.textLookupFlag.text = CountryCatalog.flag(iso)
        binding.textLookupDial.text = if (dial.isBlank()) iso else "+$dial"
    }

    /** Hands the typed number to the Lookup screen and clears the field. */
    private fun submitLookup() {
        val typed = binding.inputLookupNumber.text?.toString()?.trim().orEmpty()
        val number = when {
            typed.isBlank() -> ""
            typed.startsWith("+") -> typed
            homeDial.isNotBlank() -> "+$homeDial" + typed.filter { it.isDigit() }
            else -> typed
        }
        (activity as? MainShellActivity)?.showLookup(number.ifBlank { null })
        binding.inputLookupNumber.setText("")
    }

    private fun setupSearch() {
        binding.inputSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.buttonClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            updateSearchChrome(query)
            applyQuery(query)
        }
        binding.inputSearch.setOnFocusChangeListener { _, _ ->
            updateSearchChrome(binding.inputSearch.text?.toString().orEmpty())
        }
        binding.buttonClearSearch.setOnClickListener { binding.inputSearch.setText("") }
        binding.buttonResetSearch.setOnClickListener { binding.inputSearch.setText("") }
    }

    /** Accent ring on the search pill while focused or typing. */
    private fun updateSearchChrome(query: String) {
        val active = query.isNotEmpty() || binding.inputSearch.hasFocus()
        binding.searchBar.setBackgroundResource(
            if (active) R.drawable.bg_search_bar_active else R.drawable.bg_search_bar
        )
    }

    private fun applyQuery(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) tools
        else tools.filter { it.name.contains(q, true) || it.hint.contains(q, true) }

        if (filtered.isEmpty()) {
            binding.textEmptyTitle.text = getString(R.string.tools_empty_title, q)
            binding.listTools.visibility = View.GONE
            binding.emptyTools.visibility = View.VISIBLE
        } else {
            binding.emptyTools.visibility = View.GONE
            binding.listTools.visibility = View.VISIBLE
            adapter.submit(buildCategories(filtered))
        }
    }

    /**
     * Groups filtered tools into their categories, in display order.
     *
     * A category with nothing left after a search is dropped rather than shown
     * empty — otherwise filtering to "compass" would leave three bare headers over
     * three empty rails.
     */
    private fun buildCategories(items: List<UtilityUi>): List<UtilityCategory> =
        categoryOrder.mapNotNull { category ->
            val inCategory = items.filter { it.category == category }
            if (inCategory.isEmpty()) null else UtilityCategory(category, inCategory)
        }
}
