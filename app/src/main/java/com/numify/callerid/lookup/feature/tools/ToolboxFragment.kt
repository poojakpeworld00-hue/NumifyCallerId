package com.numify.callerid.lookup.feature.tools

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.databinding.ActivityToolsBinding
import com.numify.callerid.lookup.common.openActivity

/**
 * Grid of mini-tools grouped into Measure · Device · Time, with instant search
 * and a friendly empty state. Each tile launches its own activity.
 */
class ToolboxFragment : BaseFragment<ActivityToolsBinding>() {

    /** Tools: mid native above the tools grid. */
    override val screenAdFormat = ScreenAdFormat.MID_NATIVE

    private val adapter = ToolboxAdapter { tool ->
        requireActivity().openActivity(Intent(requireContext(), tool.target))
    }

    /** Full tool set, in display order, with the controlled 6-hue palette. */
    private val tools: List<UtilityUi> by lazy {
        val measure = getString(R.string.tools_cat_measure)
        val device = getString(R.string.tools_cat_device)
        val time = getString(R.string.tools_cat_time)
        listOf(
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
            getString(R.string.tools_cat_measure),
            getString(R.string.tools_cat_device),
            getString(R.string.tools_cat_time),
        )
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        ActivityToolsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hosted as a tab: the shell owns the bottom nav, so only the top inset applies.
        ViewCompat.setOnApplyWindowInsetsListener(binding.toolsRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.padBack.visibility = View.GONE

        val gridManager = GridLayoutManager(requireContext(), TOOL_COLUMNS)
        gridManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) TOOL_COLUMNS else 1
        }
        binding.rollTools.layoutManager = gridManager
        binding.rollTools.adapter = adapter

        setupSearch()
        applyQuery("")
    }

    private fun setupSearch() {
        binding.inpSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.padClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            updateSearchChrome(query)
            applyQuery(query)
        }
        binding.inpSearch.setOnFocusChangeListener { _, _ ->
            updateSearchChrome(binding.inpSearch.text?.toString().orEmpty())
        }
        binding.padClearSearch.setOnClickListener { binding.inpSearch.setText("") }
        binding.padResetSearch.setOnClickListener { binding.inpSearch.setText("") }
    }

    /** Accent ring on the search pill while focused or typing. */
    private fun updateSearchChrome(query: String) {
        val active = query.isNotEmpty() || binding.inpSearch.hasFocus()
        binding.searchBarVw.setBackgroundResource(
            if (active) R.drawable.bg_search_bar_active else R.drawable.bg_search_bar
        )
    }

    private fun applyQuery(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) tools
        else tools.filter { it.name.contains(q, true) || it.hint.contains(q, true) }

        if (filtered.isEmpty()) {
            binding.lblEmptyTitle.text = getString(R.string.tools_empty_title, q)
            binding.rollTools.visibility = View.GONE
            binding.emptyToolsVw.visibility = View.VISIBLE
        } else {
            binding.emptyToolsVw.visibility = View.GONE
            binding.rollTools.visibility = View.VISIBLE
            adapter.submit(buildRows(filtered))
        }
    }

    /** Groups filtered tools under their category headers, in display order. */
    private fun buildRows(items: List<UtilityUi>): List<UtilityRow> {
        val rows = mutableListOf<UtilityRow>()
        for (category in categoryOrder) {
            val inCategory = items.filter { it.category == category }
            if (inCategory.isEmpty()) continue
            rows.add(UtilityRow.Header(category))
            inCategory.forEach { rows.add(UtilityRow.Tool(it)) }
        }
        return rows
    }

    private companion object {
        /** Grid width. Category headers span all of it — keep the two in step. */
        const val TOOL_COLUMNS = 3
    }
}
