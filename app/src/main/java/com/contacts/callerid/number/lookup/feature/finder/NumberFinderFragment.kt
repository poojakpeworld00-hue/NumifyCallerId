package com.contacts.callerid.number.lookup.feature.finder

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.contacts.callerid.number.lookup.common.ListDividerDecoration
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.repository.RegionDetector
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore
import com.contacts.callerid.number.lookup.monetize.delivery.RewardedAdPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.contacts.callerid.number.lookup.foundation.BaseFragment
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.common.openActivity
import com.contacts.callerid.number.lookup.databinding.FragmentLookupBinding
import java.util.Locale

class NumberFinderFragment : BaseFragment<FragmentLookupBinding>() {

    /** Lookup: native banner pinned under the flag/paste row. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: NumberFinderViewModel by viewModels()
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var currentState: SearchState = SearchState.Idle

    /** A number handed in from elsewhere (e.g. Home search) to look up on arrival. */
    private var pendingNumber: String? = null

    /** Clipboard value already used via the Paste chip — don't offer it again. */
    private var consumedClip: String? = null

    private val countryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val data = res.data ?: return@registerForActivityResult
            val iso = data.getStringExtra(CountryPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(CountryPickerActivity.EXTRA_DIAL).orEmpty()
            SettingsRepository(requireContext()).homeCountryIso = iso // keep Home + Lookup in sync
            confirmCountry(iso, dial)
        }
    }

    /** Opens the standalone history screen; a picked number is searched on return. */
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val number = res.data?.getStringExtra(SearchHistoryActivity.EXTRA_NUMBER)
                ?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            binding.inputNumberInput.setText(number)
            binding.inputNumberInput.setSelection(number.length)
            viewModel.search(number)
            hideKeyboard()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentLookupBinding.inflate(inflater, container, false)

    override fun initView() {
        // Let the blue hero extend under the status bar; pad its top by the inset.
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        binding.buttonLookupBack.setOnClickListener {
            // Through the dispatcher, not finish(), so the host's back ad and any
            // back handling it has registered still run.
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        // A number handed in at construction — "Identify" on a recents row opens
        // this screen with one already chosen.
        arguments?.getString(ARG_NUMBER)?.takeIf { it.isNotBlank() }?.let { pendingNumber = it }
        setupCountryChip()
        // Preload the rewarded ad so it's ready when the user reveals a result.
        RewardedAdPresenter.preload(requireContext())
        binding.columnCountryPickerSearch.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), CountryPickerActivity::class.java))
        }

        binding.buttonLookupHistory.setOnClickListener {
            historyLauncher.launch(SearchHistoryActivity.newIntent(requireContext()))
        }

        historyAdapter = SearchHistoryAdapter(
            onClick = { entry -> binding.inputNumberInput.setText(entry.rawNumber); binding.inputNumberInput.setSelection(entry.rawNumber.length) },
            onCall = { entry -> dial(entry.rawNumber) },
            onRevealName = { entry -> revealHistoryName(entry) }
        )
        binding.listHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.listHistory.adapter = historyAdapter
        binding.listHistory.addItemDecoration(ListDividerDecoration(binding.listHistory))

        binding.inputNumberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.imageClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                // Note: no live search — results are shown only after tapping Lookup.
                if (text.isEmpty()) maybeShowPasteChip() else binding.chipPaste.visibility = View.GONE
            }
        })

        binding.inputNumberInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(binding.inputNumberInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else false
        }

        binding.buttonSearch.setOnClickListener {
            viewModel.search(binding.inputNumberInput.text?.toString().orEmpty())
            hideKeyboard()
        }

        binding.imageClear.setOnClickListener {
            binding.inputNumberInput.setText("")
            viewModel.clear()
        }

        // Empty-state CTA: focus the field and pop the keyboard.
        binding.buttonEmptySearch.setOnClickListener {
            binding.inputNumberInput.requestFocus()
            showKeyboard()
        }

        // Paste chip: one tap fills the field from the clipboard and searches
        // (uses the raw clipboard value stored on the chip, not the pretty display).
        binding.chipPaste.setOnClickListener {
            val n = (binding.chipPaste.tag as? String)?.takeIf { it.isNotBlank() }
                ?: binding.textPasteNumber.text?.toString().orEmpty()
            if (n.isNotBlank()) {
                consumedClip = n // used once → don't re-offer this same clipboard number
                binding.inputNumberInput.setText(n)
                binding.inputNumberInput.setSelection(n.length)
                viewModel.search(n)
                hideKeyboard()
                binding.chipPaste.visibility = View.GONE
            }
        }

        // Focusing the field re-checks the clipboard (covers copying from inside the app).
        binding.inputNumberInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) maybeShowPasteChip()
        }

        binding.textHistoryClearAll.setOnClickListener { viewModel.clearSearchHistory() }

        consumePendingSearch()
    }

    override fun onResume() {
        super.onResume()
        // The standalone history screen may have cleared/changed entries while away.
        if (view != null) {
            viewModel.refreshSearchHistory()
            maybeShowPasteChip(autoFocusIfUsed = true)
        }
    }

    /** Tab became visible again (MainShellActivity uses show/hide, so onResume won't fire). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) {
            maybeShowPasteChip(autoFocusIfUsed = true)
            // Re-entering the tab re-hides names: each visit must re-earn via a rewarded ad.
            historyAdapter.resetReveals()
        }
    }

    /** Called by the host (e.g. from Home search) to look up a number on this tab. */
    fun requestSearch(number: String) {
        pendingNumber = number
        if (_isViewReady()) consumePendingSearch()
    }

    private fun _isViewReady(): Boolean = view != null

    private fun consumePendingSearch() {
        val number = pendingNumber?.takeIf { it.isNotBlank() } ?: return
        pendingNumber = null
        binding.inputNumberInput.setText(number)
        binding.inputNumberInput.setSelection(number.length)
        viewModel.search(number)
        hideKeyboard()
    }

    override fun initObservers() {
        viewModel.state.observe(viewLifecycleOwner) { state ->
            currentState = state
            when (state) {
                is SearchState.Idle -> render(loading = false, result = null)
                is SearchState.Loading -> render(loading = true, result = null)
                is SearchState.Result -> render(loading = false, result = state.result)
            }
            maybeShowPasteChip()
        }
        viewModel.history.observe(viewLifecycleOwner) { items ->
            historyAdapter.submit(items)
            val hasHistory = items.isNotEmpty()
            binding.columnHistoryHeader.visibility = if (hasHistory) View.VISIBLE else View.GONE
            binding.listHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
            updateEmptyState(hasHistory)
        }
    }

    private fun render(loading: Boolean, result: LookupVerdict?) {
        binding.shimmerResult.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.shimmerResult.startShimmer() else binding.shimmerResult.stopShimmer()

        // Shimmer overlays the scroll region, so hide the list/result content while loading.
        binding.scrollContent.visibility = if (loading) View.GONE else View.VISIBLE

        if (result != null) {
            binding.cardResult.visibility = View.VISIBLE
            bindResult(result)
        } else {
            binding.cardResult.visibility = View.GONE
        }
        updateEmptyState(binding.listHistory.visibility == View.VISIBLE)
    }

    /** Empty state only when idle with no result and no history. */
    private fun updateEmptyState(hasHistory: Boolean) {
        val idle = currentState is SearchState.Idle
        binding.columnEmptyState.visibility =
            if (idle && !hasHistory) View.VISIBLE else View.GONE
    }

    private fun bindResult(result: LookupVerdict) {
        // Name is blurred on the card; revealed (full name + detail screen) after a rewarded ad.
        val hasName = !result.name.isNullOrBlank()
        val fullName = result.name?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_caller)
        binding.textResName.text = if (hasName) blurName(fullName) else fullName
        binding.imageRevealName.visibility = if (hasName) View.VISIBLE else View.GONE
        binding.textResNumber.text = result.number

        when {
            result.isSpam -> bindStatusPill(
                R.string.lookup_spam_risk, R.color.danger, R.color.danger_soft, R.drawable.ic_warning
            )
            result.valid == true -> bindStatusPill(
                R.string.lookup_valid_number, R.color.success, R.color.success_soft, R.drawable.ic_verified
            )
            result.valid == false -> bindStatusPill(
                R.string.lookup_invalid_number, R.color.danger, R.color.danger_soft, R.drawable.ic_warning
            )
            else -> bindStatusPill(
                if (result.inContacts) R.string.lookup_in_contacts else R.string.lookup_not_in_contacts,
                R.color.on_surface_variant, R.color.neutral_soft, R.drawable.ic_info
            )
        }

        binding.textResCountry.text = result.country
            ?: viewModel.regionName(result.rawNumber)
            ?: getString(
                if (result.regionCode.isEmpty()) R.string.lookup_local_number
                else R.string.lookup_international
            )
        binding.textResCarrier.text = result.carrier?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)
        binding.textResType.text = result.lineType?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)

        binding.buttonResCall.setOnClickListener { dial(result.rawNumber) }
        binding.buttonResShare.setOnClickListener { share(result) }
        // Eye icon + "Show full detail" → watch a rewarded ad, then reveal.
        val reveal = { revealFullDetail(result, fullName) }
        binding.imageRevealName.setOnClickListener { reveal() }
        binding.buttonShowFullDetail.setOnClickListener { reveal() }
    }

    /**
     * Reveals the caller: a "watch ad" confirmation dialog, then the rewarded ad,
     * then the card name un-blurs and [ReportNumberActivity] opens, nicknames
     * included. With ads off it goes straight through.
     */
    private fun revealFullDetail(result: LookupVerdict, fullName: String) {
        val act = activity ?: return
        val open = {
            if (view != null) {
                binding.textResName.text = fullName
                binding.imageRevealName.visibility = View.GONE
                requireActivity().openActivity(ReportNumberActivity.newIntent(requireContext(), result), false)
            }
        }

        // The dialog, the ads-off short circuit and the ad are all RewardPrompt's
        // now — this used to inflate its own copy of the same dialog.
        NameRevealReward.reveal(act, fullName, result.number) { open() }
    }

    /** First letter + dots (e.g. "John" → "J•••"). */
    private fun blurName(name: String): String = NameRevealReward.blur(name)

    /** Recent-list name tap: gate the reveal behind a rewarded ad, then un-mask that row. */
    private fun revealHistoryName(entry: SearchHistoryEntry) {
        val act = activity ?: return
        val name = entry.name ?: return
        NameRevealReward.reveal(act, name, entry.number) {
            if (view != null) historyAdapter.revealName(entry.rawNumber)
        }
    }

    private fun setupCountryChip() {
        // 1) Honour an explicit choice from the country picker.
        val saved = SettingsRepository(requireContext()).homeCountryIso
        if (saved.length == 2) {
            confirmCountry(saved, dialFor(saved))
            return
        }
        // 2) SIM/network country — the most accurate source for a phone.
        val sim = simCountryIso()
        if (sim != null) {
            confirmCountry(sim, dialFor(sim))
            return
        }
        // 3) No SIM → device region immediately (never the globe), refined via IP.
        val region = Locale.getDefault().country
        val fallbackIso = if (region.length == 2) region else "US"
        confirmCountry(fallbackIso, dialFor(fallbackIso))
        detectCountryByIp()
    }

    /** SIM (then network) registered country as an uppercase ISO-2, or null. No permission needed. */
    private fun simCountryIso(): String? {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val iso = tm.simCountryIso?.takeIf { it.length == 2 }
            ?: tm.networkCountryIso?.takeIf { it.length == 2 }
        return iso?.uppercase()
    }

    private fun dialFor(iso: String): String =
        (CountryCatalog.byIso(iso)?.dial ?: CountryCatalog.dialOf(iso)).orEmpty()

    /**
     * Resolves the country from the user's IP through the shared, cache-first
     * [RegionDetector] and updates the chip on a best-effort basis. The country is
     * detected once for the whole app, in AdAwareActivity, and reused here, so no
     * repeat network call is made. The resolved ISO maps onto its dialling code,
     * which is how the chip shows both the flag and "+91". A failure simply leaves
     * the fallback in place.
     */
    private fun detectCountryByIp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val iso = RegionDetector.detectCountry(requireContext())?.iso ?: return@launch
            // Bail if the view is gone or the user picked a country meanwhile.
            if (view == null || SettingsRepository(requireContext()).homeCountryIso.isNotBlank()) return@launch
            val dial = dialFor(iso)
            if (dial.isBlank()) return@launch
            // Don't persist an auto-detected country — only the picker records a choice.
            confirmCountry(iso, dial)
        }
    }

    private fun confirmCountry(iso: String, dial: String) {
        binding.textFlagSearch.text = CountryCatalog.flag(iso)
        binding.textCountrySearch.text = if (dial.isBlank()) iso else "+$dial"
        viewModel.changeRegion(iso, dial)
    }

    /** Styles the status pill (text, text/icon color, soft background) for one lookup state. */
    private fun bindStatusPill(textRes: Int, fgColor: Int, bgColor: Int, iconRes: Int) {
        val fg = color(fgColor)
        binding.textResValid.apply {
            setText(textRes)
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(color(bgColor))
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(fg))
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)

    private fun dial(number: String) = placeCall(number)

    private fun share(result: LookupVerdict) {
        val details = buildString {
            append(result.name ?: getString(R.string.lookup_unknown_caller))
            append("\n").append(result.number)
            result.country?.let { append("\n").append(getString(R.string.lookup_country)).append(": ").append(it) }
            result.carrier?.let { append("\n").append(getString(R.string.lookup_carrier)).append(": ").append(it) }
            result.lineType?.let { append("\n").append(getString(R.string.lookup_line_type)).append(": ").append(it) }
        }
        runCatching {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, details)
            }
            startActivity(Intent.createChooser(intent, null))
        }
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.inputNumberInput.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.inputNumberInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Offers a one-tap "Paste <number>" chip when the clipboard holds something that
     * looks like a phone number and the field is empty (idle, no result yet).
     */
    private fun maybeShowPasteChip(autoFocusIfUsed: Boolean = false) {
        if (view == null) return
        val idleEmpty = currentState is SearchState.Idle && binding.inputNumberInput.text.isNullOrBlank()
        if (!idleEmpty) {
            binding.chipPaste.visibility = View.GONE
            return
        }
        val clip = clipboardPhone()
        when {
            // A fresh phone number in the clipboard → offer the Paste chip.
            clip != null && clip != consumedClip -> {
                binding.chipPaste.tag = clip // raw value used for the search
                binding.textPasteNumber.text =
                    runCatching { NumberDetails.format(clip) }.getOrNull()?.takeIf { it.isNotBlank() } ?: clip
                binding.chipPaste.visibility = View.VISIBLE
            }
            // Already pasted this same number once → don't re-offer it; open the
            // keyboard so the user can just type instead.
            clip != null && clip == consumedClip && autoFocusIfUsed -> {
                binding.chipPaste.visibility = View.GONE
                binding.inputNumberInput.requestFocus()
                showKeyboard()
            }
            else -> binding.chipPaste.visibility = View.GONE
        }
    }

    /** The clipboard text if it looks like a phone number, else null. */
    private fun clipboardPhone(): String? {
        val cm = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()?.trim().orEmpty()
        return clip.takeIf { looksLikePhone(it) }
    }

    /** Loose phone-number check: 6–20 chars, mostly digits, only dialling characters. */
    private fun looksLikePhone(s: String): Boolean {
        if (s.length < 6 || s.length > 20) return false
        if (s.count { it.isDigit() } < 6) return false
        return s.all { it.isDigit() || it in "+-().,  " }
    }

    companion object {
        private const val ARG_NUMBER = "arg_number"

        /**
         * [number] is searched as soon as the view is ready. It rides in the
         * arguments rather than in a field so it survives the fragment being
         * recreated on a configuration change — a rotation would otherwise drop
         * it and leave the user on an empty search field.
         */
        fun newInstance(number: String? = null): NumberFinderFragment =
            NumberFinderFragment().apply {
                if (!number.isNullOrBlank()) {
                    arguments = Bundle().apply { putString(ARG_NUMBER, number) }
                }
            }
    }
}
