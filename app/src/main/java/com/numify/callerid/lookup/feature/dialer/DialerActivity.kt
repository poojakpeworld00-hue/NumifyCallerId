package com.numify.callerid.lookup.feature.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.ContactRepository
import com.numify.callerid.lookup.databinding.ScreenDialerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialer screen: the on-screen keypad builds the number shown in
 * [ScreenDialerBinding.tvDialNumber] (dialed by Call, saved by "Add to contacts")
 * and filters the most-used list into the "Matches" section above the keypad sheet.
 */
class DialerActivity : BaseActivity<ScreenDialerBinding>() {

    override val layoutId: Int = R.layout.screen_dialer

    private val viewModel: DialerViewModel by viewModels()
    private val adapter = SpeedDialAdapter(onClick = ::setDial, onCall = ::fillAndDial)
    private val contactsRepo by lazy { ContactRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Mirrors the manifest's windowSoftInputMode. `showSoftInputOnFocus =
        // false` alone only blocks the tap/focus path — the window still enters
        // with stateUnspecified, and OEM IMEs auto-open for the focused number
        // field on entry. Setting it here too survives any theme override.
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.dialerRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.padBack.setOnClickListener { goBack() }

        binding.rollFrequent.layoutManager = LinearLayoutManager(this)
        binding.rollFrequent.adapter = adapter

        // Keypad builds the dialed number display.
        binding.padBackspace.setOnClickListener { backspaceDial() }
        binding.padBackspace.setOnLongClickListener { setDial(""); true }
        binding.padDialCall.setOnClickListener { placeCall(dialedNumber()) }
        binding.rowAddContact.setOnClickListener { addToContacts(dialedNumber()) }

        // Show a blinking cursor in the number field but keep our on-screen keypad
        // as the only input: suppress the soft keyboard, then focus it.
        binding.lblDialNumber.showSoftInputOnFocus = false
        binding.lblDialNumber.requestFocus()
        hideSystemKeyboard()

        setupKeypad()
        updateDialState()
        loadFrequentIfAllowed()
    }

    override fun initObservers() {
        viewModel.frequent.observe(this) { list ->
            adapter.submit(list)
            val hasMatches = list.isNotEmpty()
            binding.lblEmpty.visibility = if (hasMatches) View.GONE else View.VISIBLE
            binding.lblMatchesLabel.visibility = if (hasMatches) View.VISIBLE else View.GONE
            // Label reads "Matches" while dialing, "Frequently called" at rest.
            binding.lblMatchesLabel.setText(
                if (dialedNumber().isEmpty()) R.string.dialer_frequent else R.string.dialer_matches
            )
            // A named match means the dialed digits belong to a saved contact — no "Add".
            hasNamedMatch = dialedNumber().isNotEmpty() && list.any { !it.name.isNullOrBlank() }
            applyAddContactVisibility()
        }
    }

    /**
     * The keypad is the only input here, so the IME is dismissed whenever this
     * window takes focus — including on return from Contacts/the call screen,
     * where a keyboard left open by the previous screen would otherwise cover
     * the dialpad.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemKeyboard()
    }

    private fun hideSystemKeyboard() {
        runCatching {
            WindowCompat.getInsetsController(window, binding.lblDialNumber)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    /** Wires every key cell to append its tag; long-pressing "0" inserts "+". */
    private fun setupKeypad() {
        val grid = binding.gridKeypadVw
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i)
            val key = cell.tag?.toString() ?: continue
            cell.setOnClickListener { appendDial(key) }
            if (key == "0") cell.setOnLongClickListener { appendDial("+"); true }
        }
    }

    private fun dialedNumber(): String = binding.lblDialNumber.text?.toString().orEmpty()

    private fun appendDial(text: String) {
        binding.lblDialNumber.append(text)
        updateDialState()
    }

    private fun backspaceDial() {
        val text = binding.lblDialNumber.text
        if (text.isNotEmpty()) binding.lblDialNumber.setText(text.subSequence(0, text.length - 1))
        updateDialState()
    }

    private fun setDial(number: String) {
        binding.lblDialNumber.setText(number)
        updateDialState()
    }

    private var addContactJob: Job? = null

    /** The exact dialed number is a saved contact. */
    private var savedExact = false

    /** The dialed digits match a saved (named) contact in the recents/matches list. */
    private var hasNamedMatch = false

    /**
     * Keeps "Add to contacts"/backspace visible only with a number, and refreshes
     * the list so the keypad filters the recents just like the top search field.
     *
     * "Add to contacts" toggles INVISIBLE↔VISIBLE (never GONE) so its slot is always
     * reserved — otherwise the keypad reflows on every keystroke and the number/pill
     * appear to blink. We also don't pre-hide it before the async contact lookup
     * (which caused a GONE→VISIBLE flash), and we cancel any stale lookup.
     */
    private fun updateDialState() {
        val number = dialedNumber()
        // Keep the cursor at the end after every keypad edit (setText resets it).
        binding.lblDialNumber.setSelection(number.length)
        val hasNumber = number.isNotEmpty()
        binding.padBackspace.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
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
        addContactJob = lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                contactsRepo.lookupNameByNumber(number) != null
            }
            // Drop stale results if the dialed number changed while querying.
            if (dialedNumber() == number) {
                savedExact = saved
                applyAddContactVisibility()
            }
        }
    }

    /**
     * "Add to contacts" shows only for an unknown number: there is a dialed number,
     * it isn't a saved contact, and it doesn't match any named contact in the list.
     * Uses INVISIBLE (not GONE) so the keypad never reflows.
     */
    private fun applyAddContactVisibility() {
        val show = dialedNumber().isNotEmpty() && !savedExact && !hasNamedMatch
        binding.rowAddContact.visibility = if (show) View.VISIBLE else View.INVISIBLE
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
            this, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.load() else binding.lblEmpty.visibility = View.VISIBLE
    }
}
