package com.contacts.callerid.number.lookup.feature.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.contacts.callerid.number.lookup.common.AvatarPalette
import com.contacts.callerid.number.lookup.common.DialedNumberCheck
import com.contacts.callerid.number.lookup.common.ListDividerDecoration
import com.contacts.callerid.number.lookup.common.openActivity
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.foundation.BaseFragment
import com.contacts.callerid.number.lookup.feature.assistant.AiHubActivity
import com.contacts.callerid.number.lookup.feature.finder.LookupActivity
import com.contacts.callerid.number.lookup.feature.widgets.CallActionHandler
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.repository.assistant.AiFeatureConfig
import com.contacts.callerid.number.lookup.databinding.ActivityDialerBinding
import kotlinx.coroutines.launch

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

        binding.listFrequent.layoutManager = LinearLayoutManager(requireContext())
        binding.listFrequent.adapter = adapter
        // Hairlines between rows inside the card, as on every other list.
        binding.listFrequent.addItemDecoration(ListDividerDecoration(binding.listFrequent))

        // Keypad builds the dialed number display.
        binding.buttonBackspace.setOnClickListener { backspaceDial() }
        binding.buttonBackspace.setOnLongClickListener { setDial(""); true }
        binding.buttonDialCall.setOnClickListener { placeCall(dialedNumber()) }

        // The head row is the number itself; its one button is the one action
        // that should not sit below four others.
        binding.buttonDialHeadCall.setOnClickListener { placeCall(dialedNumber()) }

        // What to do with the number, beyond calling it.
        binding.rowAddContact.setOnClickListener { addToContacts(dialedNumber()) }
        binding.rowDialMessage.setOnClickListener { sendMessage(dialedNumber()) }
        binding.rowDialLookup.setOnClickListener {
            requireActivity().openActivity(LookupActivity.newIntent(requireContext(), dialedNumber()))
        }
        binding.rowDialWhatsApp.setOnClickListener { openWhatsApp(dialedNumber()) }
        binding.rowDialAskAi.setOnClickListener {
            requireActivity().openActivity(AiHubActivity.newIntent(requireContext()))
        }

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
            hasFrequent = hasMatches
            applyZeroState()
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
        if (hidden) {
            releaseSoftInput()
        } else {
            claimSoftInput()
        }
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


    /** The exact dialed number is a saved contact. */
    private var savedExact = false

    /** The dialed digits match a saved (named) contact in the recents/matches list. */
    private var hasNamedMatch = false

    /** The matches list has rows in it — at rest, the frequently-called ones. */
    private var hasFrequent = false

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
        // Straight off the pool the view model already holds. This used to fire a
        // PhoneLookup on a background thread for every keypress — a
        // content-provider round trip per digit, on the one screen whose whole job
        // is to keep up with digits.
        savedExact = hasNumber && viewModel.isSavedContact(number)
        applyAddContactVisibility()
        applyZeroState()
        viewModel.filter(number)
    }

    /**
     * The action card is what the screen shows about an **unknown** number: it
     * appears once digits are typed and only while those digits belong to nobody
     * in the address book. A number that is already saved has a match row in the
     * list below with the contact's name on it, and offering "Add to contacts"
     * next to it would be nonsense — this is the same split every stock dialer
     * makes, and why the card leads the band rather than trailing it.
     *
     * Within the card each row still decides for itself:
     *
     * - **Add to contacts** takes anything typed. Short codes and internal
     *   extensions are not valid phone numbers and are exactly the kind of thing
     *   people save, so it is deliberately not held to the check below.
     * - **Message**, **Lookup** and **WhatsApp** wait for a number that parses.
     *   Half a number has nothing to look up and no WhatsApp account behind it;
     *   offering them anyway only teaches people the features do not work.
     * - **Ask AI** follows the assistant's Remote Config switch and the user's
     *   own preference, the same pair that gates its tile in Tools.
     * - **WhatsApp** additionally needs WhatsApp installed. A row that can only
     *   ever raise "WhatsApp isn't installed" is worse than no row.
     *
     * If that leaves nothing, the card goes too rather than sitting there empty.
     */
    private fun applyAddContactVisibility() {
        val number = dialedNumber()
        val hasNumber = number.isNotEmpty()
        val ctx = context ?: return

        // Parsed once and shared: this runs on every keystroke.
        val dialable = hasNumber && DialedNumberCheck.isLookupable(ctx, number)
        val unknown = hasNumber && !savedExact && !hasNamedMatch

        binding.textDialActionsNumber.text = number
        // Same avatar rule as the Contacts and Recents rows, so one number does
        // not get three different treatments across three tabs.
        binding.textDialHeadAvatar.text = CallActionHandler.initials("", number)
        binding.textDialHeadAvatar.backgroundTintList = AvatarPalette.tintFor(ctx, number)
        binding.rowAddContact.isVisible = true
        binding.rowDialMessage.isVisible = dialable
        binding.rowDialLookup.isVisible = dialable
        binding.rowDialWhatsApp.isVisible = dialable && whatsAppPackage() != null
        binding.rowDialAskAi.isVisible = dialable &&
            AiFeatureConfig.isEnabled(ctx) && SettingsRepository(ctx).aiHomeButtonEnabled
        binding.columnDialActions.isVisible = unknown
    }

    /**
     * "Dial a number to get started" belongs to the untouched screen only.
     *
     * It is a sibling of the matches block, both filling the band over the
     * keypad, so it has to go as soon as anything else wants that band — and
     * once digits are typed the action card does. It was showing whenever the
     * frequent list was empty, which after this change meant it printed itself
     * straight through "Add to contacts / Lookup / Ask AI / WhatsApp".
     *
     * Its copy says the same thing: an invitation to dial is nonsense once you
     * have dialled.
     */
    private fun applyZeroState() {
        binding.textEmpty.isVisible = dialedNumber().isEmpty() && !hasFrequent
    }

    /**
     * The installed WhatsApp flavour, consumer first, or null if neither is here.
     *
     * Resolved once and remembered. This is read from the visibility pass, which
     * runs on every keystroke, and `getLaunchIntentForPackage` is a binder call
     * into the package manager — two of them per digit for an answer that cannot
     * change while the user is typing.
     */
    private var whatsAppPackage: String? = null
    private var whatsAppResolved = false

    private fun whatsAppPackage(): String? {
        if (whatsAppResolved) return whatsAppPackage
        val pm = context?.packageManager ?: return null
        whatsAppPackage = listOf("com.whatsapp", "com.whatsapp.w4b")
            .firstOrNull { runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() != null }
        whatsAppResolved = true
        return whatsAppPackage
    }

    /**
     * Opens a WhatsApp chat with the dialled number.
     *
     * Through `https://wa.me/<digits>` rather than the app's launch intent: the
     * point of the row is this number, and a launch intent would only drop the
     * user on their chat list with the number lost. WhatsApp wants digits alone,
     * so the "+", spaces and brackets a dialled number can carry are stripped.
     */
    private fun openWhatsApp(number: String) {
        val digits = number.filter(Char::isDigit)
        if (digits.isEmpty()) return
        val pkg = whatsAppPackage() ?: run {
            Toast.makeText(requireContext(), R.string.toast_no_whatsapp, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, "https://wa.me/$digits".toUri()).setPackage(pkg)
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.toast_no_whatsapp, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens the user's SMS app on the dialled number.
     *
     * `smsto:` rather than `sms:`, and ACTION_SENDTO rather than ACTION_VIEW, so
     * the chooser offers messaging apps only — the same pair Call Details and the
     * assistant's reply action use. No body is attached: the point of the row is
     * the recipient, and the user writes the message themselves.
     */
    private fun sendMessage(number: String) {
        if (number.isBlank()) return
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:$number".toUri())
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.toast_no_sms_app, Toast.LENGTH_SHORT).show()
        }
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

    /**
     * Reloads the search pool if either source is readable.
     *
     * It gated on READ_CALL_LOG alone, from when the pool was the call log. The
     * pool is call log *plus* contacts now, so a user who granted Contacts but
     * refused Call log would have been left with a dialer that could not find
     * anyone — the view model reads each side defensively and simply contributes
     * nothing for the one that is denied.
     */
    private fun loadFrequentIfAllowed() {
        val ctx = context ?: return
        val canRead = listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS)
            .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (canRead) viewModel.load() else applyZeroState()
    }
}
