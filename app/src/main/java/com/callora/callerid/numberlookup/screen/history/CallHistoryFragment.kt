package com.callora.callerid.numberlookup.screen.history

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
import com.callora.callerid.numberlookup.R
import com.callora.callerid.adkit.runtime.NativeBannerAd
import com.callora.callerid.numberlookup.core.CoreFragment
import com.callora.callerid.numberlookup.kit.openActivity
import com.callora.callerid.numberlookup.databinding.PaneRecentsBinding
import com.callora.callerid.numberlookup.screen.HomeShellActivity
import com.callora.callerid.numberlookup.screen.insight.CallInsightActivity
import com.callora.callerid.numberlookup.screen.keypad.KeypadActivity
import com.callora.callerid.numberlookup.screen.prefs.PreferencesActivity
import com.callora.callerid.numberlookup.screen.search.CountryCatalog
import com.callora.callerid.numberlookup.screen.search.RegionPickerActivity
import com.callora.callerid.numberlookup.screen.shared.SpotlightOverlay
import com.callora.callerid.numberlookup.store.CallDirection
import com.callora.callerid.numberlookup.store.CallLogTotals
import com.callora.callerid.numberlookup.store.RegionProbe
import com.callora.callerid.numberlookup.store.SettingsVault
import com.callora.callerid.adkit.policy.logPermissionResult
import com.callora.callerid.numberlookup.kit.followAdContainer

class CallHistoryFragment : CoreFragment<PaneRecentsBinding>() {

    /** Recents: native banner in the existing in-list slot. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: CallHistoryViewModel by viewModels()
    private val adapter = CallHistoryAdapter(
        ::dialNumber,
        ::openDetail,
        onIdentify = { number -> (activity as? HomeShellActivity)?.showLookup(number) }
    )
    private val prefs by lazy { SettingsVault(requireContext()) }

    /** Dialing code selected in the search country chip (no leading '+'). */
    private var homeDial: String = ""

    /** Core-permission queue + its result launcher (see [withCorePermissions]). */
    private val corePermQueue = ArrayDeque<String>()
    private var corePermAction: (() -> Unit)? = null
    private var lastCorePermission: String? = null
    private val corePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastCorePermission?.let { context?.logPermissionResult(it, granted) }
        advanceCorePermissions()
    }

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(RegionPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(RegionPickerActivity.EXTRA_DIAL).orEmpty()
            prefs.homeCountryIso = iso
            applyHomeCountry(iso, dial)
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        PaneRecentsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeaderVw.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeaderVw) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        binding.padRecentsDial.setOnClickListener {
            requireActivity().openActivity<KeypadActivity>()
        }
        binding.padRecentsFilter.setOnClickListener { showSortMenu(it) }
        binding.padSettings.setOnClickListener {
            requireActivity().openActivity<PreferencesActivity>()
        }
        binding.panelProtection.setOnClickListener {
            (activity as? HomeShellActivity)?.showBlocklist()
        }
        binding.padProtectionAction.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                requireActivity().openActivity<PreferencesActivity>()
            } else {
                (activity as? HomeShellActivity)?.startOverlayPermissionFlow()
            }
        }

        binding.padPermManage.setOnClickListener {
            (activity as? HomeShellActivity)?.showPermissionSheet()
        }

        setupHomeCountry()
        binding.rowHomeCountry.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), RegionPickerActivity::class.java))
        }
        binding.padHomeSearch.setOnClickListener { submitSearch() }
        binding.inpSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                submitSearch(); true
            } else false
        }

        binding.rollRecents.layoutManager = LinearLayoutManager(requireContext())
        binding.rollRecents.adapter = adapter

        // Native banner at the bottom of the recents screen.
        // Ad slot + dividers are handled by CoreFragment.showScreenAd(), which
        // routes through ScreenAdPlan so this tab honours its own `ScreenAds`
        // entry (banner-first, native-banner fallback) instead of hard-coding a
        // native banner that Remote Config could not switch off.

        binding.segAll.setOnClickListener { viewModel.setFilter(CallScope.ALL) }
        binding.segIncoming.setOnClickListener { viewModel.setFilter(CallScope.INCOMING) }
        binding.segOutgoing.setOnClickListener { viewModel.setFilter(CallScope.OUTGOING) }
        binding.segMissed.setOnClickListener { viewModel.setFilter(CallScope.MISSED) }
        binding.padGrant.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CALL_LOG)
            ) {
                if (hasCallLogPermission()) onPermissionGranted() else showPermissionState()
                (activity as? HomeShellActivity)?.startOverlayPermissionFlow()
            }
        }

        binding.inpSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val text = s?.toString().orEmpty()
                viewModel.setQuery(text)
                binding.padClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.padClearSearch.setOnClickListener { binding.inpSearch.setText("") }

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
     * Surfaces the "Manage permissions" hint once the HomeShellActivity permission
     * sheet has been dismissed with permissions still pending, and hides it again
     * as soon as everything is granted. Safe to call any time the fragment is
     * attached — HomeShellActivity owns the actual condition.
     */
    fun refreshPermissionHint() {
        if (view == null) return
        val show = (activity as? HomeShellActivity)?.shouldShowPermissionHint() == true
        binding.rowPermHint.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshProtectionState() {
        val active = Settings.canDrawOverlays(requireContext())
        binding.lblProtectionTitle.setText(
            if (active) R.string.home_protection_on_title else R.string.home_protection_off_title
        )
        binding.lblProtectionSub.setText(
            if (active) R.string.home_protection_on_sub else R.string.home_protection_off_sub
        )
        binding.padProtectionAction.setText(
            if (active) R.string.home_protection_manage else R.string.home_protection_turn_on
        )
        binding.picProtection.imageTintList = ColorStateList.valueOf(
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
        val vault = SettingsVault(ctx)
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
     * hint bubble beneath it. Shown once, persisted via [SettingsVault.isSearchHintShown].
     */
    private fun maybeShowSearchHint() {
        if (prefs.isSearchHintShown) return
        val anchor = binding.panelLookup
        anchor.post {
            if (!isAdded || view == null) return@post
            val act = activity ?: return@post
            prefs.isSearchHintShown = true
            SpotlightOverlay.show(act, anchor, R.layout.part_search_hint)
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
            val geo = RegionProbe.detectCountry(requireContext()) ?: return@launch
            if (view == null || prefs.homeCountryIso.isNotBlank()) return@launch
            val dial = geo.dial.ifBlank { dialFor(geo.iso) }
            applyHomeCountry(geo.iso, dial)
        }
    }

    private fun applyHomeCountry(iso: String, dial: String) {
        homeDial = dial
        binding.lblHomeFlag.text = CountryCatalog.flag(iso)
        binding.lblHomeDial.text = if (dial.isBlank()) iso else "+$dial"
    }

    /** Navigates to the Lookup tab and runs the lookup for the entered number. */
    private fun submitSearch() {
        val typed = binding.inpSearch.text?.toString()?.trim().orEmpty()
        val number = when {
            typed.isBlank() -> ""
            typed.startsWith("+") -> typed
            homeDial.isNotBlank() -> "+$homeDial" + typed.filter { it.isDigit() }
            else -> typed
        }
        (activity as? HomeShellActivity)?.showLookup(number.ifBlank { null })
        binding.inpSearch.setText("")
    }

    override fun initObservers() {
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)
            binding.lblEmpty.visibility =
                if (rows.isEmpty() && hasCallLogPermission()) View.VISIBLE else View.GONE
            showCounts(rows)
        }
        viewModel.filter.observe(viewLifecycleOwner) { active ->
            highlightTab(binding.segAll, active == CallScope.ALL)
            highlightTab(binding.segIncoming, active == CallScope.INCOMING)
            highlightTab(binding.segOutgoing, active == CallScope.OUTGOING)
            highlightTab(binding.segMissed, active == CallScope.MISSED)
        }
    }

    /**
     * Header subtitle: how many calls and how many of those were missed.
     *
     * Unfiltered, this reports the WHOLE call log via [CallHistoryViewModel.totals].
     * Counting the rows on screen was wrong here: the list is capped at a row
     * window (`CallLogSource.getCalls`), so any device with more calls than the cap
     * showed exactly the cap — a fixed number that never moved — while the missed
     * count beside it moved, because it was counted within that sliding window.
     *
     * With a filter or a search active the rows ARE the subject, so it counts them
     * — the header then describes what's in view, which is what the user asked for.
     */
    private fun showCounts(rows: List<HistoryRowUi>) {
        val filtering = (viewModel.filter.value ?: CallScope.ALL) != CallScope.ALL ||
            binding.inpSearch.text?.isNotBlank() == true

        val (total, missed) = if (filtering) {
            val calls = rows.filterIsInstance<HistoryRowUi.Call>()
            calls.size to calls.count { it.entry.type == CallDirection.MISSED }
        } else {
            val t = viewModel.totals.value ?: CallLogTotals(0, 0)
            t.total to t.missed
        }

        binding.lblRecentsCount.text = getString(
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
            R.string.sort_newest to CallOrder.NEWEST,
            R.string.sort_oldest to CallOrder.OLDEST,
            R.string.sort_name_asc to CallOrder.NAME_ASC,
            R.string.sort_name_desc to CallOrder.NAME_DESC
        )
        val current = viewModel.sort.value ?: CallOrder.NEWEST

        val inflater = LayoutInflater.from(requireContext())
        val content = inflater.inflate(R.layout.flyout_sort, null) as LinearLayout
        val container = content.findViewById<LinearLayout>(R.id.sortContainerVw)

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
            val row = inflater.inflate(R.layout.cell_sort_option, container, false)
            row.findViewById<TextView>(R.id.lblSortLabel).setText(titleRes)
            row.findViewById<ImageView>(R.id.picSortCheck).visibility =
                if (sort == current) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener {
                viewModel.setSort(sort)
                popup.dismiss()
            }
            container.addView(row)
        }

        val yOffset = (4f * resources.displayMetrics.density).toInt()
        popup.showAsDropDown(anchor, 0, yOffset, Gravity.END)
    }

    private fun onPermissionGranted() {
        binding.permStateVw.visibility = View.GONE
        binding.rollRecents.visibility = View.VISIBLE
        viewModel.load()
    }

    private fun showPermissionState() {
        binding.permStateVw.visibility = View.VISIBLE
        binding.rollRecents.visibility = View.GONE
        binding.lblEmpty.visibility = View.GONE
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(entry: com.callora.callerid.numberlookup.store.CallRecord) {
        requireActivity().openActivity(CallInsightActivity.newIntent(requireContext(), entry.number, entry.name))
    }
}
