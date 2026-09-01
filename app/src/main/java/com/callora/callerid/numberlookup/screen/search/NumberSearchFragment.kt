package com.callora.callerid.numberlookup.screen.search

import android.app.Activity
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.store.RegionProbe
import com.callora.callerid.adkit.policy.AdsPrefStore
import com.callora.callerid.adkit.runtime.RewardedAdLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.callora.callerid.numberlookup.core.CoreFragment
import com.callora.callerid.numberlookup.store.SettingsVault
import com.callora.callerid.numberlookup.kit.openActivity
import com.callora.callerid.numberlookup.databinding.SheetWatchAdBinding
import com.callora.callerid.numberlookup.databinding.PaneLookupBinding
import java.util.Locale

class NumberSearchFragment : CoreFragment<PaneLookupBinding>() {

    /** Lookup: native banner pinned under the flag/paste row. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: NumberSearchViewModel by viewModels()
    private lateinit var historyAdapter: SearchTrailAdapter
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
            val iso = data.getStringExtra(RegionPickerActivity.EXTRA_ISO) ?: return@registerForActivityResult
            val dial = data.getStringExtra(RegionPickerActivity.EXTRA_DIAL).orEmpty()
            SettingsVault(requireContext()).homeCountryIso = iso // keep Home + Lookup in sync
            commitCountry(iso, dial)
        }
    }

    /** Opens the standalone history screen; a picked number is searched on return. */
    private val historyLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val number = res.data?.getStringExtra(SearchTrailActivity.EXTRA_NUMBER)
                ?.takeIf { it.isNotBlank() } ?: return@registerForActivityResult
            binding.inpNumberInput.setText(number)
            binding.inpNumberInput.setSelection(number.length)
            viewModel.search(number)
            hideKeyboard()
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        PaneLookupBinding.inflate(inflater, container, false)

    override fun initView() {
        // Let the blue hero extend under the status bar; pad its top by the inset.
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeaderVw.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeaderVw) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }
        setupCountryChip()
        // Preload the rewarded ad so it's ready when the user reveals a result.
        RewardedAdLoader.preload(requireContext())
        binding.rowCountryPickerSearch.setOnClickListener {
            countryLauncher.launch(Intent(requireContext(), RegionPickerActivity::class.java))
        }

        binding.padLookupHistory.setOnClickListener {
            historyLauncher.launch(SearchTrailActivity.newIntent(requireContext()))
        }

        historyAdapter = SearchTrailAdapter(
            onClick = { entry -> binding.inpNumberInput.setText(entry.rawNumber); binding.inpNumberInput.setSelection(entry.rawNumber.length) },
            onCall = { entry -> dial(entry.rawNumber) },
            onRevealName = { entry -> revealHistoryName(entry) }
        )
        binding.rollHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rollHistory.adapter = historyAdapter

        binding.inpNumberInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                binding.picClear.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
                // Note: no live search — results are shown only after tapping Lookup.
                if (text.isEmpty()) maybeShowPasteChip() else binding.flagPaste.visibility = View.GONE
            }
        })

        binding.inpNumberInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                viewModel.search(binding.inpNumberInput.text?.toString().orEmpty())
                hideKeyboard()
                true
            } else false
        }

        binding.padSearch.setOnClickListener {
            viewModel.search(binding.inpNumberInput.text?.toString().orEmpty())
            hideKeyboard()
        }

        binding.picClear.setOnClickListener {
            binding.inpNumberInput.setText("")
            viewModel.clear()
        }

        // Empty-state CTA: focus the field and pop the keyboard.
        binding.padEmptySearch.setOnClickListener {
            binding.inpNumberInput.requestFocus()
            showKeyboard()
        }

        // Paste chip: one tap fills the field from the clipboard and searches
        // (uses the raw clipboard value stored on the chip, not the pretty display).
        binding.flagPaste.setOnClickListener {
            val n = (binding.flagPaste.tag as? String)?.takeIf { it.isNotBlank() }
                ?: binding.lblPasteNumber.text?.toString().orEmpty()
            if (n.isNotBlank()) {
                consumedClip = n // used once → don't re-offer this same clipboard number
                binding.inpNumberInput.setText(n)
                binding.inpNumberInput.setSelection(n.length)
                viewModel.search(n)
                hideKeyboard()
                binding.flagPaste.visibility = View.GONE
            }
        }

        // Focusing the field re-checks the clipboard (covers copying from inside the app).
        binding.inpNumberInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) maybeShowPasteChip()
        }

        binding.lblHistoryClearAll.setOnClickListener { viewModel.wipeSearchTrail() }

        consumePendingSearch()
    }

    override fun onResume() {
        super.onResume()
        // The standalone history screen may have cleared/changed entries while away.
        if (view != null) {
            viewModel.reloadSearchTrail()
            maybeShowPasteChip(autoFocusIfUsed = true)
        }
    }

    /** Tab became visible again (HomeShellActivity uses show/hide, so onResume won't fire). */
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
        binding.inpNumberInput.setText(number)
        binding.inpNumberInput.setSelection(number.length)
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
            binding.rowHistoryHeader.visibility = if (hasHistory) View.VISIBLE else View.GONE
            binding.rollHistory.visibility = if (hasHistory) View.VISIBLE else View.GONE
            updateEmptyState(hasHistory)
        }
    }

    private fun render(loading: Boolean, result: NumberVerdict?) {
        binding.shmResult.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.shmResult.startShimmer() else binding.shmResult.stopShimmer()

        // Shimmer overlays the scroll region, so hide the list/result content while loading.
        binding.scrlContent.visibility = if (loading) View.GONE else View.VISIBLE

        if (result != null) {
            binding.cvResultVw.visibility = View.VISIBLE
            bindResult(result)
        } else {
            binding.cvResultVw.visibility = View.GONE
        }
        updateEmptyState(binding.rollHistory.visibility == View.VISIBLE)
    }

    /** Empty state only when idle with no result and no history. */
    private fun updateEmptyState(hasHistory: Boolean) {
        val idle = currentState is SearchState.Idle
        binding.rowEmptyState.visibility =
            if (idle && !hasHistory) View.VISIBLE else View.GONE
    }

    private fun bindResult(result: NumberVerdict) {
        // Name is blurred on the card; revealed (full name + detail screen) after a rewarded ad.
        val hasName = !result.name.isNullOrBlank()
        val fullName = result.name?.takeIf { it.isNotBlank() } ?: getString(R.string.lookup_unknown_caller)
        binding.lblResName.text = if (hasName) blurName(fullName) else fullName
        binding.picRevealName.visibility = if (hasName) View.VISIBLE else View.GONE
        binding.lblResNumber.text = result.number

        when {
            result.isSpam -> bindStatusPill(
                R.string.lookup_spam_risk, R.color.danger, R.color.danger_soft, R.drawable.glyph_warning
            )
            result.valid == true -> bindStatusPill(
                R.string.lookup_valid_number, R.color.success, R.color.success_soft, R.drawable.glyph_verified
            )
            result.valid == false -> bindStatusPill(
                R.string.lookup_invalid_number, R.color.danger, R.color.danger_soft, R.drawable.glyph_warning
            )
            else -> bindStatusPill(
                if (result.inContacts) R.string.lookup_in_contacts else R.string.lookup_not_in_contacts,
                R.color.on_surface_variant, R.color.neutral_soft, R.drawable.glyph_info
            )
        }

        binding.lblResCountry.text = result.country
            ?: viewModel.regionName(result.rawNumber)
            ?: getString(
                if (result.regionCode.isEmpty()) R.string.lookup_local_number
                else R.string.lookup_international
            )
        binding.lblResCarrier.text = result.carrier?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)
        binding.lblResType.text = result.lineType?.takeIf { it.isNotBlank() }
            ?: getString(R.string.lookup_unknown_value)

        binding.padResCall.setOnClickListener { dial(result.rawNumber) }
        binding.padResShare.setOnClickListener { share(result) }
        // Eye icon + "Show full detail" → watch a rewarded ad, then reveal.
        val reveal = { revealFullDetail(result, fullName) }
        binding.picRevealName.setOnClickListener { reveal() }
        binding.padShowFullDetail.setOnClickListener { reveal() }
    }

    /**
     * Reveals the caller: shows a "watch ad" confirmation dialog → rewarded ad →
     * un-blurs the card name and opens [NumberReportActivity] (incl. nicknames).
     * Goes straight through when ads are off.
     */
    private fun revealFullDetail(result: NumberVerdict, fullName: String) {
        val act = activity ?: return
        val open = {
            if (view != null) {
                binding.lblResName.text = fullName
                binding.picRevealName.visibility = View.GONE
                requireActivity().openActivity(NumberReportActivity.newIntent(requireContext(), result), false)
            }
        }

        // Ads off → straight to detail, no ad, no dialog.
        if (!AdsPrefStore.getInstance(act).getBoolean("IsAdsON")) {
            open()
            return
        }

        // Ads on → confirm with a dialog, then play the rewarded ad, then open.
        val db = SheetWatchAdBinding.inflate(layoutInflater)
        val dialog = Dialog(act).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(db.root)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        db.lblPreviewName.text = blurName(fullName)
        db.lblPreviewNumber.text = result.number
        db.padWatchAd.setOnClickListener {
            dialog.dismiss()
            RewardedAdLoader().show(act) { open() }
        }
        db.padCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** First letter + dots (e.g. "John" → "J•••"). */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name

    /** Recent-list name tap: gate the reveal behind a rewarded ad, then un-mask that row. */
    private fun revealHistoryName(entry: TrailEntry) {
        val act = activity ?: return
        val name = entry.name ?: return
        RewardNameUnlock.reveal(act, name, entry.number) {
            if (view != null) historyAdapter.revealName(entry.rawNumber)
        }
    }

    private fun setupCountryChip() {
        // 1) Honour an explicit choice from the country picker.
        val saved = SettingsVault(requireContext()).homeCountryIso
        if (saved.length == 2) {
            commitCountry(saved, dialFor(saved))
            return
        }
        // 2) SIM/network country — the most accurate source for a phone.
        val sim = simCountryIso()
        if (sim != null) {
            commitCountry(sim, dialFor(sim))
            return
        }
        // 3) No SIM → device region immediately (never the globe), refined via IP.
        val region = Locale.getDefault().country
        val fallbackIso = if (region.length == 2) region else "US"
        commitCountry(fallbackIso, dialFor(fallbackIso))
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
     * Resolves the country from the user's IP via the shared, cache-first
     * [RegionProbe] and updates the chip (best-effort). The country is detected
     * once app-wide (AdHostActivity) and reused here — no repeat network call.
     * The resolved ISO maps to its dial code so the chip shows the flag 🇮🇳 and
     * "+91". Failures leave the fallback.
     */
    private fun detectCountryByIp() {
        viewLifecycleOwner.lifecycleScope.launch {
            val iso = RegionProbe.detectCountry(requireContext())?.iso ?: return@launch
            // Bail if the view is gone or the user picked a country meanwhile.
            if (view == null || SettingsVault(requireContext()).homeCountryIso.isNotBlank()) return@launch
            val dial = dialFor(iso)
            if (dial.isBlank()) return@launch
            // Don't persist an auto-detected country — only the picker records a choice.
            commitCountry(iso, dial)
        }
    }

    private fun commitCountry(iso: String, dial: String) {
        binding.lblFlagSearch.text = CountryCatalog.flag(iso)
        binding.lblCountrySearch.text = if (dial.isBlank()) iso else "+$dial"
        viewModel.updateRegion(iso, dial)
    }

    /** Styles the status pill (text, text/icon color, soft background) for one lookup state. */
    private fun bindStatusPill(textRes: Int, fgColor: Int, bgColor: Int, iconRes: Int) {
        val fg = color(fgColor)
        binding.lblResValid.apply {
            setText(textRes)
            setTextColor(fg)
            backgroundTintList = ColorStateList.valueOf(color(bgColor))
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(fg))
        }
    }

    private fun color(resId: Int) = ContextCompat.getColor(requireContext(), resId)

    private fun dial(number: String) = placeCall(number)

    private fun share(result: NumberVerdict) {
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
        imm?.hideSoftInputFromWindow(binding.inpNumberInput.windowToken, 0)
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.inpNumberInput, InputMethodManager.SHOW_IMPLICIT)
    }

    /**
     * Offers a one-tap "Paste <number>" chip when the clipboard holds something that
     * looks like a phone number and the field is empty (idle, no result yet).
     */
    private fun maybeShowPasteChip(autoFocusIfUsed: Boolean = false) {
        if (view == null) return
        val idleEmpty = currentState is SearchState.Idle && binding.inpNumberInput.text.isNullOrBlank()
        if (!idleEmpty) {
            binding.flagPaste.visibility = View.GONE
            return
        }
        val clip = clipboardPhone()
        when {
            // A fresh phone number in the clipboard → offer the Paste chip.
            clip != null && clip != consumedClip -> {
                binding.flagPaste.tag = clip // raw value used for the search
                binding.lblPasteNumber.text =
                    runCatching { NumberProfile.format(clip) }.getOrNull()?.takeIf { it.isNotBlank() } ?: clip
                binding.flagPaste.visibility = View.VISIBLE
            }
            // Already pasted this same number once → don't re-offer it; open the
            // keyboard so the user can just type instead.
            clip != null && clip == consumedClip && autoFocusIfUsed -> {
                binding.flagPaste.visibility = View.GONE
                binding.inpNumberInput.requestFocus()
                showKeyboard()
            }
            else -> binding.flagPaste.visibility = View.GONE
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
}
