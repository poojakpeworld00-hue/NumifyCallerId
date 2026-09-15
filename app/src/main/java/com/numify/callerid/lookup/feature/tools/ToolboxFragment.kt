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
import com.numify.callerid.lookup.feature.finder.LookupActivity
import com.numify.callerid.lookup.feature.assistant.AiHubActivity
import com.numify.callerid.lookup.repository.assistant.AiFeatureConfig
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
        val shell = activity as? MainShellActivity
        val intent = Intent(requireContext(), tool.target).apply {
            tool.mode?.let { putExtra(AiScanActivity.EXTRA_MODE, it) }
        }
        // The shell owns the overlay round-trip, because it owns the launcher the
        // Settings page returns through. Outside it — this fragment is only ever
        // a tab today — fall back to the plain ad-then-open path.
        if (shell != null) shell.openToolGated(intent)
        else requireActivity().openActivity(intent)
    }

    /**
     * Full tool set, in display order, with the controlled 6-hue palette.
     *
     * Ask AI appears only when its Remote Config block and the user's own AI
     * setting both allow it — the feature ships dark, so the tile has to be able
     * to not exist rather than merely be greyed out.
     *
     * Rebuilt on every call rather than cached: the AI switch lives two screens
     * away in settings, and this is a show/hide tab that is not recreated on the
     * way back, so a list computed once would still be offering a tile the user
     * had just turned off.
     */
    private fun tools(): List<UtilityUi> {
        val assistant = getString(R.string.tools_cat_assistant)
        val measure = getString(R.string.tools_cat_measure)
        val device = getString(R.string.tools_cat_device)
        val time = getString(R.string.tools_cat_time)
        return listOfNotNull(
            // Lookup leads the Assistant rail: with the centre action gone this
            // tile is the app's number search, so it is the first thing here.
            UtilityUi(getString(R.string.nav_lookup), getString(R.string.tools_lookup_sub),
                R.drawable.ic_ds_nav_lookup, R.drawable.bg_tool_chip_sky, R.color.tool_sky,
                assistant, LookupActivity::class.java),
            // Ask AI moved here off the Recents filter row, where it cost the
            // filters a third of their width. It belongs with the other two
            // assistant entries anyway.
            askAiTile(assistant),
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

    /**
     * The Ask AI tile, or null when the assistant is switched off.
     *
     * Two gates, both of which have to pass: [AiFeatureConfig.isEnabled] is the
     * Remote Config master switch (off in release until an audience is turned
     * on), and `aiHomeButtonEnabled` is the user's own preference in AI settings.
     */
    private fun askAiTile(category: String): UtilityUi? {
        val ctx = requireContext()
        val enabled = AiFeatureConfig.isEnabled(ctx) &&
            SettingsRepository(ctx).aiHomeButtonEnabled
        if (!enabled) return null
        return UtilityUi(
            getString(R.string.ai_ask_button), getString(R.string.tools_ask_ai_sub),
            R.drawable.ic_ai_sparkle, R.drawable.bg_tool_chip_amber, R.color.tool_amber,
            category, AiHubActivity::class.java
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
        applyQuery("")
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

    /** Re-runs the current filter, picking up anything that changed while away. */
    private fun refreshTools() {
        if (view != null) applyQuery(binding.inputSearch.text?.toString().orEmpty())
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshTools()
    }

    override fun onResume() {
        super.onResume()
        refreshTools()
    }

    private fun applyQuery(query: String) {
        val q = query.trim()
        val all = tools()
        val filtered = if (q.isEmpty()) all
        else all.filter { it.name.contains(q, true) || it.hint.contains(q, true) }

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
