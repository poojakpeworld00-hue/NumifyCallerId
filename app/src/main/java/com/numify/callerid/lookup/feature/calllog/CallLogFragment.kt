package com.numify.callerid.lookup.feature.calllog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.lookup.R
import com.numify.callerid.monetize.delivery.NativeBannerPresenter
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.common.openActivity
import com.numify.callerid.lookup.databinding.FragmentRecentsBinding
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.calldetails.CallDetailsActivity
import com.numify.callerid.lookup.feature.dialer.DialerActivity
import com.numify.callerid.lookup.feature.settings.SettingsActivity
import com.numify.callerid.lookup.feature.finder.CountryCatalog
import com.numify.callerid.lookup.feature.finder.CountryPickerActivity
import com.numify.callerid.lookup.feature.widgets.CoachMarkOverlay
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.repository.CallLogTotals
import com.numify.callerid.lookup.repository.RegionDetector
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.monetize.strategy.recordPermissionOutcome
import com.numify.callerid.lookup.common.followAdContainer

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

    /** Dialing code selected in the search country chip (no leading '+'). */
    private var homeDial: String = ""

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

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(CountryPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(CountryPickerActivity.EXTRA_DIAL).orEmpty()
            prefs.homeCountryIso = iso
            applyHomeCountry(iso, dial)
        }
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
            requireActivity().openActivity<DialerActivity>()
        }
        binding.buttonRecentsFilter.setOnClickListener { showSortMenu(it) }
        binding.buttonSettings.setOnClickListener {
            requireActivity().openActivity<SettingsActivity>()
        }
        binding.cardProtection.setOnClickListener {
            (activity as? MainShellActivity)?.showBlocklist()
        }
        binding.buttonProtectionAction.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                requireActivity().openActivity<SettingsActivity>()
            } else {
                (activity as? MainShellActivity)?.startOverlayPermissionFlow()
            }
        }

        binding.buttonPermManage.setOnClickListener {
            (activity as? MainShellActivity)?.showPermissionSheet()
        }

        setupHomeCountry()
        binding.columnHomeCountry.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), CountryPickerActivity::class.java))
        }
        binding.buttonHomeSearch.setOnClickListener { submitSearch() }
        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(); true
            } else false
        }

        binding.listRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.listRecents.adapter = adapter

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

        binding.inputSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                viewModel.applyQuery(text)
                binding.buttonClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.buttonClearSearch.setOnClickListener { binding.inputSearch.setText("") }

        if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
        refreshProtectionState()
        refreshPermissionHint()
        maybeShowSearchHint()
    }

    /** Re-evaluates when this tab becomes visible again (show/hide keeps the fragment resumed). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
            refreshProtectionState()
            refreshPermissionHint()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings (or a system dialog) so a freshly
        // granted permission shows the list without needing to leave the screen.
        if (view != null) {
            if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
            refreshProtectionState()
            refreshPermissionHint()
        }
    }

    /**
     * Surfaces the "Manage permissions" hint once the MainShellActivity permission
     * sheet has been dismissed with permissions still pending, and hides it again
     * as soon as everything is granted. Safe to call any time the fragment is
     * attached — MainShellActivity owns the actual condition.
     */
    fun refreshPermissionHint() {
        if (view == null) return
        val show = (activity as? MainShellActivity)?.shouldShowPermissionHint() == true
        binding.columnPermHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshProtectionState() {
        val active = Settings.canDrawOverlays(requireContext())
        binding.textProtectionTitle.setText(
            if (active) R.string.home_protection_on_title else R.string.home_protection_off_title
        )
        binding.textProtectionSub.setText(
            if (active) R.string.home_protection_on_sub else R.string.home_protection_off_sub
        )
        binding.buttonProtectionAction.setText(
            if (active) R.string.home_protection_manage else R.string.home_protection_turn_on
        )
        binding.imageProtection.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.accent else R.color.on_surface_variant
            )
        )
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
     * Requests the [corePermissions] (skipping any already granted) and then runs
     * [action]. A permanently-denied permission is skipped rather than bouncing the
     * user to App Settings, so the action still runs.
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

    /**
     * First-run coach-mark: dims the screen, spotlights the lookup card and shows a
     * hint bubble beneath it. Shown once, persisted via [SettingsRepository.isSearchHintShown].
     */
    private fun maybeShowSearchHint() {
        if (prefs.isSearchHintShown) return
        val anchor = binding.cardLookup
        anchor.post {
            if (!isAdded || view == null) return@post
            val act = activity ?: return@post
            prefs.isSearchHintShown = true
            CoachMarkOverlay.show(act, anchor, R.layout.include_search_hint)
        }
    }

    /**
     * Picks the search country chip. Uses the saved choice if any; otherwise shows the
     * device region immediately and refines it to the IP-detected country in the background.
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
        val region = java.util.Locale.getDefault().country
        val fallbackIso = if (region.length == 2) region else "US"
        applyHomeCountry(fallbackIso, dialFor(fallbackIso))
        detectCountryByIp()
    }

    /** SIM (then network) registered country as an uppercase ISO-2, or null. */
    private fun simCountryIso(): String? {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
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
            val dial = geo.dial.ifBlank { dialFor(geo.iso) }
            applyHomeCountry(geo.iso, dial)
        }
    }

    private fun applyHomeCountry(iso: String, dial: String) {
        homeDial = dial
        binding.textHomeFlag.text = CountryCatalog.flag(iso)
        binding.textHomeDial.text = if (dial.isBlank()) iso else "+$dial"
    }

    /** Navigates to the Lookup tab and runs the lookup for the entered number. */
    private fun submitSearch() {
        val typed = binding.inputSearch.text?.toString()?.trim().orEmpty()
        val number = when {
            typed.isBlank() -> ""
            typed.startsWith("+") -> typed
            homeDial.isNotBlank() -> "+$homeDial" + typed.filter { it.isDigit() }
            else -> typed
        }
        (activity as? MainShellActivity)?.showLookup(number.ifBlank { null })
        binding.inputSearch.setText("")
    }

    override fun initObservers() {
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)
            binding.textEmpty.visibility =
                if (rows.isEmpty() && hasCallLogPermission()) View.VISIBLE else View.GONE
            showCounts(rows)
        }
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
        val filtering = (viewModel.filter.value ?: CallLogFilter.ALL) != CallLogFilter.ALL ||
            binding.inputSearch.text?.isNotBlank() == true

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
        viewModel.load()
    }

    private fun showPermissionState() {
        binding.permState.visibility = View.VISIBLE
        binding.listRecents.visibility = View.GONE
        binding.textEmpty.visibility = View.GONE
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(entry: com.numify.callerid.lookup.repository.CallRecord) {
        requireActivity().openActivity(CallDetailsActivity.newIntent(requireContext(), entry.number, entry.name))
    }
}
