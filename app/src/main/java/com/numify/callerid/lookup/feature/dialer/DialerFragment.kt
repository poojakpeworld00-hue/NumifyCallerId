package com.numify.callerid.lookup.feature.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.lookup.common.ListDividerDecoration
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.repository.ContactRepository
import com.numify.callerid.lookup.databinding.ActivityDialerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialer. The on-screen keypad assembles the number displayed in
 * [ActivityDialerBinding.textDialNumber] - dialled by Call, saved by "Add to
 * contacts" - and filters the most-used list into the "Matches" section above the
 * keypad sheet.
 *
 * A tab rather than its own Activity: the dialer is one of the four places the
 * bottom bar goes now, and a nav chip that launched an Activity would leave the
 * bar behind and come back with no tab selected. Same move ToolboxFragment made,
 * down to reusing the `activity_` layout and hiding its back button — the shell
 * owns the chrome here.
 */
class DialerFragment : BaseFragment<ActivityDialerBinding>() {

    private val viewModel: DialerViewModel by viewModels()
    private val adapter = SpeedDialAdapter(onClick = ::setDial, onCall = ::fillAndDial)
    private val contactsRepo by lazy { ContactRepository(requireContext()) }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        ActivityDialerBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hosted as a tab: the shell owns the bottom nav, so only the top inset
        // applies here.
        ViewCompat.setOnApplyWindowInsetsListener(binding.dialerRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.buttonBack.visibility = View.GONE

        binding.listFrequent.layoutManager = LinearLayoutManager(requireContext())
        binding.listFrequent.adapter = adapter
        // Hairlines between rows inside the card, as on every other list.
        binding.listFrequent.addItemDecoration(ListDividerDecoration(binding.listFrequent))

        // Keypad builds the dialed number display.
        binding.buttonBackspace.setOnClickListener { backspaceDial() }
        binding.buttonBackspace.setOnLongClickListener { setDial(""); true }
        binding.buttonDialCall.setOnClickListener { placeCall(dialedNumber()) }
        binding.columnAddContact.setOnClickListener { addToContacts(dialedNumber()) }

        // Show a blinking cursor in the number field but keep our on-screen keypad
        // as the only input: suppress the soft keyboard, then focus it.
        binding.textDialNumber.showSoftInputOnFocus = false
        binding.textDialNumber.requestFocus()
        hideSystemKeyboard()

        setupKeypad()
        updateDialState()
        loadFrequentIfAllowed()
    }

    override fun initObservers() {
        viewModel.frequent.observe(viewLifecycleOwner) { list ->
            adapter.submit(list)
            val hasMatches = list.isNotEmpty()
            binding.textEmpty.visibility = if (hasMatches) View.GONE else View.VISIBLE
            binding.textMatchesLabel.visibility = if (hasMatches) View.VISIBLE else View.GONE
            // Label reads "Matches" while dialing, "Frequently called" at rest.
            binding.textMatchesLabel.setText(
                if (dialedNumber().isEmpty()) R.string.dialer_frequent else R.string.dialer_matches
            )
            // A named match means the dialed digits belong to a saved contact — no "Add".
            hasNamedMatch = dialedNumber().isNotEmpty() && list.any { !it.name.isNullOrBlank() }
            applyAddContactVisibility()
        }
    }

    /**
     * The keypad is the only input here, so the IME is kept shut whenever this tab
     * is in front — returning from Contacts or the call screen, or simply
     * switching tabs, could otherwise leave a keyboard sitting over the dialpad.
     */
    override fun onResume() {
        super.onResume()
        if (!isHidden) claimSoftInput()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) releaseSoftInput() else claimSoftInput()
    }

    override fun onPause() {
        super.onPause()
        releaseSoftInput()
    }

    /**
     * Holds the host window shut against the IME while this tab is up.
     *
     * As its own Activity this was a manifest attribute —
     * `windowSoftInputMode="stateAlwaysHidden|adjustNothing"`. A fragment has no
     * manifest entry, and `showSoftInputOnFocus = false` alone is not enough:
     * that blocks the tap/focus path, but the window still enters with
     * stateUnspecified and OEM IMEs open themselves for a focused number field.
     * The first build of this tab came up with the system keypad over the app's
     * own one.
     *
     * It is released again on the way out, or the setting would follow the user
     * to Tools and Lookup, whose search fields do need a keyboard and need the
     * window to resize for it.
     */
    private fun claimSoftInput() {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        hideSystemKeyboard()
    }

    private fun releaseSoftInput() {
        activity?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    private fun hideSystemKeyboard() {
        val window = activity?.window ?: return
        runCatching {
            WindowCompat.getInsetsController(window, binding.textDialNumber)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    /** Wires every key cell to append its tag; long-pressing "0" inserts "+". */
    private fun setupKeypad() {
        val grid = binding.gridKeypad
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i)
            val key = cell.tag?.toString() ?: continue
            cell.setOnClickListener { appendDial(key) }
            if (key == "0") cell.setOnLongClickListener { appendDial("+"); true }
        }
    }

    private fun dialedNumber(): String = binding.textDialNumber.text?.toString().orEmpty()

    private fun appendDial(text: String) {
        binding.textDialNumber.append(text)
        updateDialState()
    }

    private fun backspaceDial() {
        val text = binding.textDialNumber.text
        if (text.isNotEmpty()) binding.textDialNumber.setText(text.subSequence(0, text.length - 1))
        updateDialState()
    }

    private fun setDial(number: String) {
        binding.textDialNumber.setText(number)
        updateDialState()
    }

    private var addContactJob: Job? = null

    /** The exact dialed number is a saved contact. */
    private var savedExact = false

    /** The dialed digits match a saved (named) contact in the recents/matches list. */
    private var hasNamedMatch = false

    /**
     * Shows "Add to contacts" and backspace only while a number is present, and
     * refreshes the list so the keypad filters recents exactly as the search field
     * at the top does.
     *
     * "Add to contacts" toggles between INVISIBLE and VISIBLE, never GONE, so its
     * slot stays reserved; otherwise the keypad reflows on every keystroke and the
     * number and pill appear to blink. It is also not pre-hidden ahead of the
     * async contact lookup, which used to produce a GONE-to-VISIBLE flash, and any
     * stale lookup is cancelled.
     */
    private fun updateDialState() {
        val number = dialedNumber()
        // Keep the cursor at the end after every keypad edit (setText resets it).
        binding.textDialNumber.setSelection(number.length)
        val hasNumber = number.isNotEmpty()
        binding.buttonBackspace.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        if (hasNumber) {
            refreshAddContact(number)
        } else {
            addContactJob?.cancel()
            savedExact = false
            applyAddContactVisibility()
        }
        viewModel.filter(number)
    }

    /** Re-checks whether the exact dialed number is a saved contact, then updates the pill. */
    private fun refreshAddContact(number: String) {
        addContactJob?.cancel()
        addContactJob = viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                contactsRepo.lookupNameByNumber(number) != null
            }
            // Drop stale results if the dialed number changed while querying.
            if (view != null && dialedNumber() == number) {
                savedExact = saved
                applyAddContactVisibility()
            }
        }
    }

    /**
     * "Add to contacts" appears only for an unknown number: a number has been
     * dialled, it is not a saved contact, and it matches no named contact in the
     * list. It uses INVISIBLE rather than GONE so the keypad never reflows.
     */
    private fun applyAddContactVisibility() {
        val show = dialedNumber().isNotEmpty() && !savedExact && !hasNamedMatch
        binding.columnAddContact.visibility = if (show) View.VISIBLE else View.INVISIBLE
    }

    /** Shows a row's number in the dial display, then dials it. */
    private fun fillAndDial(number: String) {
        setDial(number)
        placeCall(number)
    }

    private fun addToContacts(number: String) {
        if (number.isBlank()) return
        runCatching {
            val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                putExtra(ContactsContract.Intents.Insert.PHONE, number)
            }
            startActivity(intent)
        }
    }

    private fun loadFrequentIfAllowed() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.load() else binding.textEmpty.visibility = View.VISIBLE
    }
}
