package com.callerid.numberlookup.home.feature.calllog

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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.monetize.delivery.NativeBannerPresenter
import com.callerid.numberlookup.home.foundation.BaseFragment
import com.callerid.numberlookup.home.common.ListDividerDecoration
import com.callerid.numberlookup.home.common.openActivity
import com.callerid.numberlookup.home.feature.contacts.ContactSearchActivity
import com.callerid.numberlookup.home.databinding.FragmentRecentsBinding
import com.callerid.numberlookup.home.feature.MainShellActivity
import com.callerid.numberlookup.home.feature.calldetails.CallDetailsActivity
import com.callerid.numberlookup.home.feature.premium.PremiumActivity
import com.callerid.numberlookup.home.feature.settings.SettingsActivity
import com.callerid.numberlookup.home.repository.CallType
import com.callerid.numberlookup.home.repository.CallLogTotals
import com.callerid.numberlookup.home.feature.premium.PaywallConfig
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.monetize.strategy.recordPermissionOutcome
import com.callerid.numberlookup.home.common.followAdContainer

class CallLogFragment : BaseFragment<FragmentRecentsBinding>() {

    /** @see BaseFragment.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "CallLogFragment"

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
        setupSearch()
        bindPremiumBadge()
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
            // Notifications, then the call log, then — if Remote Config still
            // allows the ask — the overlay. Someone who tapped "Not now" on the
            // splash sheet meets notifications again here, ahead of the
            // permission this screen needs, rather than never being asked twice.
            requestPermissionChain(
                notificationFirst(Manifest.permission.READ_CALL_LOG)
            ) {
                if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
                (activity as? MainShellActivity)?.startOverlayPermissionFlow()
            }
        }


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

    /**
     * The Premium chip at the head of the row.
     *
     * Hidden outright once the entitlement is held: a paying user has nothing
     * to buy, and a permanent upgrade badge is what makes people feel they paid
     * for nothing. Re-evaluated in [onResume] too, so buying from the paywall
     * and coming back does not leave the offer sitting there.
     */
    private fun bindPremiumBadge() {
        binding.buttonRecentsPremium.isVisible = PaywallConfig.isOffered(requireContext())
        binding.buttonRecentsPremium.setOnClickListener {
            startActivity(PremiumActivity.newIntent(requireContext()))
        }
    }

    override fun onResume() {
        super.onResume()
        if (view != null) bindPremiumBadge()
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
            val showEmpty = rows.isEmpty() && hasCallLogPermission()
            if (showEmpty) {
                binding.textEmpty.show(
                    R.drawable.ic_history, R.string.recents_empty, R.string.recents_empty_sub
                )
            }
            binding.textEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
            // An empty card is just a white rectangle; the empty state replaces
            // it rather than floating over it. Left alone without permission,
            // where the permission prompt owns this space.
            if (hasCallLogPermission()) {
                binding.listRecents.visibility = if (showEmpty) View.GONE else View.VISIBLE
            }
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
            revealTab(
                when (active) {
                    CallLogFilter.INCOMING -> binding.tabIncoming
                    CallLogFilter.OUTGOING -> binding.tabOutgoing
                    CallLogFilter.MISSED -> binding.tabMissed
                    else -> binding.tabAll
                }
            )
        }
    }

    /**
     * Scrolls the chip row so the active chip is fully on screen, as the
     * Contacts tab does - "Missed" sits half off the right edge, and tapping it
     * used to leave it there.
     */
    private fun revealTab(tab: View) {
        val scroll = binding.tabScroll
        scroll.post {
            // Chip bounds are relative to the row, which sits inside the padding.
            val left = tab.left
            val right = tab.right + scroll.paddingLeft + scroll.paddingRight - scroll.width
            when {
                left < scroll.scrollX -> scroll.smoothScrollTo(left, 0)
                right > scroll.scrollX -> scroll.smoothScrollTo(right, 0)
            }
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
        // The label colour comes from selector_ds_chip_text, which the chip style
        // already points textColor at, so this only has to set the state. Setting
        // it here as well meant two sources of truth for one colour, and they had
        // drifted: the selector says ds_on_accent / ds_chip_ink, the code said
        // white / on_surface_variant.
        tab.isActivated = active
        // Selected: tint the leading icon white. Unselected: clear the tint so the
        // icon keeps its own colour - green in, blue out, red missed.
        TextViewCompat.setCompoundDrawableTintList(
            tab,
            if (active) {
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.ds_on_accent))
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
     * The header search button opens the search page, as Google’s dialer does:
     * recent callers under "Suggested" at once, then every contact matching
     * what is typed - name, number or email. The call-type chips stay this
     * screen’s own filter.
     */
    private fun setupSearch() {
        binding.buttonRecentsSearch.setOnClickListener {
            requireActivity().openActivity(
                ContactSearchActivity.newIntent(requireContext(), suggestRecent = true)
            )
        }
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

    private fun openDetail(entry: com.callerid.numberlookup.home.repository.CallRecord) {
        requireActivity().openActivity(CallDetailsActivity.newIntent(requireContext(), entry.number, entry.name))
    }
}
