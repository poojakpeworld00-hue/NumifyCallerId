package com.contacts.callerid.number.lookup.feature.calllog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.monetize.delivery.NativeBannerPresenter
import com.contacts.callerid.number.lookup.foundation.BaseFragment
import com.contacts.callerid.number.lookup.common.ListDividerDecoration
import com.contacts.callerid.number.lookup.common.openActivity
import com.contacts.callerid.number.lookup.databinding.FragmentRecentsBinding
import com.contacts.callerid.number.lookup.feature.MainShellActivity
import com.contacts.callerid.number.lookup.feature.calldetails.CallDetailsActivity
import com.contacts.callerid.number.lookup.feature.settings.SettingsActivity
import com.contacts.callerid.number.lookup.repository.CallType
import com.contacts.callerid.number.lookup.repository.CallLogTotals
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.monetize.strategy.recordPermissionOutcome
import com.contacts.callerid.number.lookup.common.followAdContainer

class CallLogFragment : BaseFragment<FragmentRecentsBinding>() {

    /** Recents: native banner in the existing in-list slot. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: CallLogViewModel by viewModels()
    private val adapter = CallLogAdapter(
        ::dialNumber,
        ::openDetail,
        onIdentify = { number -> (activity as? MainShellActivity)?.showLookup(number) }
    )
    private val prefs by lazy { SettingsRepository(requireContext()) }


    /** Core-permission queue + its result launcher (see [withCorePermissions]). */
    private val corePermQueue = ArrayDeque<String>()
    private var corePermAction: (() -> Unit)? = null
    private var lastCorePermission: String? = null
    private val corePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastCorePermission?.let { context?.recordPermissionOutcome(it, granted) }
        advanceCorePermissions()
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentRecentsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        binding.buttonRecentsDial.setOnClickListener {
            (activity as? MainShellActivity)?.showDialer()
        }
        binding.buttonRecentsFilter.setOnClickListener { showSortMenu(it) }
        binding.buttonSettings.setOnClickListener {
            requireActivity().openActivity<SettingsActivity>()
        }
        binding.buttonPermManage.setOnClickListener {
            (activity as? MainShellActivity)?.showPermissionSheet()
        }

        binding.listRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.listRecents.adapter = adapter
        // Hairlines between rows inside the card, skipping the date headings —
        // the same treatment the contacts directory already gets.
        binding.listRecents.addItemDecoration(
            ListDividerDecoration(binding.listRecents) { adapter.isHeader(it) }
        )

        // Native banner at the bottom of the recents screen.
        // Ad slot + dividers are handled by BaseFragment.showScreenAd(), which
        // routes through ScreenPlacementPlan so this tab honours its own `ScreenAds`
        // entry (banner-first, native-banner fallback) instead of hard-coding a
        // native banner that Remote Config could not switch off.

        binding.tabAll.setOnClickListener { viewModel.applyFilter(CallLogFilter.ALL) }
        binding.tabIncoming.setOnClickListener { viewModel.applyFilter(CallLogFilter.INCOMING) }
        binding.tabOutgoing.setOnClickListener { viewModel.applyFilter(CallLogFilter.OUTGOING) }
        binding.tabMissed.setOnClickListener { viewModel.applyFilter(CallLogFilter.MISSED) }
        binding.buttonGrant.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CALL_LOG)
            ) {
                if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
                (activity as? MainShellActivity)?.startOverlayPermissionFlow()
            }
        }

        // The field that used to sit at the top of this screen typed a number for
        // the Lookup screen AND filtered the call log as you typed. It has moved to
        // Tools, which takes the call-log filter with it — the four chips below
        // (All / Incoming / Outgoing / Missed) are what narrows this list now.
        // viewModel.applyQuery is still there for whoever wants to put a
        // list-filter back, and is simply never called from here.

        if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
        refreshPermissionHint()
    }

    /** Re-evaluates when this tab becomes visible again (show/hide keeps the fragment resumed). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
            refreshPermissionHint()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings (or a system dialog) so a freshly
        // granted permission shows the list without needing to leave the screen.
        if (view != null) {
            if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
            refreshPermissionHint()
        }
    }

    /**
     * Raises the "Manage permissions" hint once the MainShellActivity permission
     * sheet has been dismissed with permissions still outstanding, and hides it
     * again as soon as everything is granted. It is safe to call whenever the
     * fragment is attached, since MainShellActivity owns the actual condition.
     */
    fun refreshPermissionHint() {
        if (view == null) return
        val show = (activity as? MainShellActivity)?.shouldShowPermissionHint() == true
        binding.columnPermHint.visibility = if (show) View.VISIBLE else View.GONE
    }


    /**
     * Core permissions nudged before opening a secondary screen: post-notifications
     * (Android 13+) so call alerts can show, and read-phone-state for call detection.
     */
    private fun corePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.READ_PHONE_STATE)
    }

    /**
     * Asks for the [corePermissions], skipping any already granted, and then runs
     * [action]. A permanently-denied permission is skipped rather than bouncing
     * the user out to App Settings, so the action still runs.
     */
    private fun withCorePermissions(action: () -> Unit) {
        corePermQueue.clear()
        corePermQueue.addAll(corePermissions())
        corePermAction = action
        advanceCorePermissions()
    }

    private fun advanceCorePermissions() {
        val ctx = context ?: return
        val vault = SettingsRepository(ctx)
        while (corePermQueue.isNotEmpty()) {
            val permission = corePermQueue.removeFirst()
            if (ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED
            ) continue

            when {
                !vault.hasRequestedPermission(permission) -> {
                    vault.markPermissionRequested(permission)
                    lastCorePermission = permission
                    corePermLauncher.launch(permission)
                    return
                }
                shouldShowRequestPermissionRationale(permission) -> {
                    lastCorePermission = permission
                    corePermLauncher.launch(permission)
                    return
                }
                else -> continue
            }
        }
        val action = corePermAction
        corePermAction = null
        action?.invoke()
    }

    // The first-run coach-mark that spotlighted the lookup card went with the card
    // itself: it pointed at a field that is no longer on this screen.

    override fun initObservers() {
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)
            binding.textEmpty.visibility =
                if (rows.isEmpty() && hasCallLogPermission()) View.VISIBLE else View.GONE
            showCounts(rows)
        }
        // Saved callers show their contact picture over the coloured initials,
        // so a caller you know looks the same here as in the directory.
        viewModel.photos.observe(viewLifecycleOwner) { adapter.setPhotos(it) }
        viewModel.filter.observe(viewLifecycleOwner) { active ->
            highlightTab(binding.tabAll, active == CallLogFilter.ALL)
            highlightTab(binding.tabIncoming, active == CallLogFilter.INCOMING)
            highlightTab(binding.tabOutgoing, active == CallLogFilter.OUTGOING)
            highlightTab(binding.tabMissed, active == CallLogFilter.MISSED)
        }
    }

    /**
     * Header subtitle: the number of calls, and how many of those were missed.
     *
     * With no filter applied it reports the entire call log through
     * [CallLogViewModel.totals]. Counting the rows on screen was the wrong move
     * here: the list is capped at a row window by `CallLogRepository.getCalls`, so
     * any device holding more calls than the cap displayed exactly the cap - a
     * fixed number that never moved - while the missed count next to it did move,
     * because it was counted inside that sliding window.
     *
     * Once a filter or search is active the rows themselves are the subject, so it
     * counts them instead and the header describes what is actually in view.
     */
    private fun showCounts(rows: List<HistoryRowUi>) {
        val filtering = (viewModel.filter.value ?: CallLogFilter.ALL) != CallLogFilter.ALL

        val (total, missed) = if (filtering) {
            val calls = rows.filterIsInstance<HistoryRowUi.Call>()
            calls.size to calls.count { it.entry.type == CallType.MISSED }
        } else {
            val t = viewModel.totals.value ?: CallLogTotals(0, 0)
            t.total to t.missed
        }

        binding.textRecentsCount.text = getString(
            R.string.recents_counts_fmt,
            resources.getQuantityString(R.plurals.recents_call_count, total, total),
            missed,
        )
    }

    private fun highlightTab(tab: TextView, active: Boolean) {
        tab.isActivated = active
        tab.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.white else R.color.on_surface_variant
            )
        )
        // Selected: tint the leading icon white. Unselected: clear the tint so the
        // icon keeps its own colour.
        TextViewCompat.setCompoundDrawableTintList(
            tab,
            if (active) {
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                null
            }
        )
    }

    private fun hasCallLogPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Custom sort popup: a styled card anchored under the filter button that changes the
     * list order (date / name). Type filtering stays on the tabs.
     */
    private fun showSortMenu(anchor: View) {
        val options = listOf(
            R.string.sort_newest to CallLogSort.NEWEST,
            R.string.sort_oldest to CallLogSort.OLDEST,
            R.string.sort_name_asc to CallLogSort.NAME_ASC,
            R.string.sort_name_desc to CallLogSort.NAME_DESC
        )
        val current = viewModel.sort.value ?: CallLogSort.NEWEST

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.popup_sort, null) as LinearLayout
        val container = content.findViewById<LinearLayout>(R.id.sortContainer)

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f * resources.displayMetrics.density
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }

        options.forEach { (titleRes, sort) ->
            val row = inflater.inflate(R.layout.item_sort_option, container, false)
            row.findViewById<TextView>(R.id.textSortLabel).setText(titleRes)
            row.findViewById<ImageView>(R.id.imageSortCheck).visibility =
                if (sort == current) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                viewModel.applySort(sort)
                popup.dismiss()
            }
            container.addView(row)
        }

        val yOffset = (4f * resources.displayMetrics.density).toInt()
        popup.showAsDropDown(anchor, 0, yOffset, Gravity.END)
    }

    private fun onPermissionGranted() {
        binding.permState.visibility = View.GONE
        binding.listRecents.visibility = View.VISIBLE
        binding.appBarRecents.setExpanded(true, false)
        viewModel.load()
    }

    /**
     * Shown when the call log cannot be read — including straight after the
     * permission sheet is dismissed with "Not now", which grants nothing.
     *
     * The header is collapsed with it. Everything above the list rides in the
     * AppBarLayout, and expanded it leaves a sliver at the bottom of the screen;
     * the card is what the user has to act on now, so it takes the space and the
     * header is a scroll away rather than the other way round.
     */
    private fun showPermissionState() {
        binding.permState.visibility = View.VISIBLE
        binding.listRecents.visibility = View.GONE
        binding.textEmpty.visibility = View.GONE
        // Posted: an AppBarLayout ignores setExpanded before it has been laid
        // out, and this runs from initView and onResume, both of which can beat
        // the first layout pass.
        binding.appBarRecents.post { binding.appBarRecents.setExpanded(false, false) }
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(entry: com.contacts.callerid.number.lookup.repository.CallRecord) {
        requireActivity().openActivity(CallDetailsActivity.newIntent(requireContext(), entry.number, entry.name))
    }
}
