package com.callerid.numberlookup.home.feature.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.text.InputFilter
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
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.common.AvatarPalette
import com.callerid.numberlookup.home.common.DialedNumberCheck
import com.callerid.numberlookup.home.common.ListDividerDecoration
import com.callerid.numberlookup.home.common.openActivity
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseFragment
import com.callerid.numberlookup.home.feature.assistant.AiHubActivity
import com.callerid.numberlookup.home.feature.finder.LookupActivity
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.repository.assistant.AiFeatureConfig
import com.callerid.numberlookup.home.databinding.ActivityDialerBinding
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

    /** @see BaseFragment.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "DialerFragment"

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
        // A saved contact's own number carries the country code WhatsApp needs;
        // the typed digits often do not.
        binding.rowDialWhatsApp.setOnClickListener {
            openWhatsApp(viewModel.savedContact(dialedNumber())?.number ?: dialedNumber())
        }
        binding.rowDialAskAi.setOnClickListener {
            requireActivity().openActivity(AiHubActivity.newIntent(requireContext()))
        }

        // Show a blinking cursor in the number field but keep our on-screen keypad
        // as the only input: suppress the soft keyboard, then focus it.
        binding.textDialNumber.showSoftInputOnFocus = false
        binding.textDialNumber.requestFocus()
        hideSystemKeyboard()
        // Every edit goes through here - keypad, backspace, and a paste from the
        // field's own long-press menu, which never passes the keypad and used to
        // leave the backspace hidden over a pasted number.
        binding.textDialNumber.doAfterTextChanged { updateDialState() }
        // inputType="phone" only limits what a keyboard can type; a paste goes
        // straight in, letters and all. Keep what a dialer can dial.
        binding.textDialNumber.filters = arrayOf(InputFilter { source, start, end, _, _, _ ->
            val kept = source.subSequence(start, end).filter { it in DIALABLE }
            if (kept.length == end - start) null else kept
        })

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

    /**
     * Types [text] at the cursor, replacing any selection.
     *
     * The field shows a cursor the user can move, so a key goes where the cursor
     * is - appending at the end regardless put a correction made mid-number on
     * the wrong digit. The editable moves the cursor past what was inserted.
     */
    private fun appendDial(text: String) {
        val field = binding.textDialNumber
        val editable = field.text ?: return
        val (from, to) = selectionRange(field)
        editable.replace(from, to, text)
    }

    /** Deletes the selection, or else the one character before the cursor. */
    private fun backspaceDial() {
        val field = binding.textDialNumber
        val editable = field.text ?: return
        val (from, to) = selectionRange(field)
        when {
            from != to -> editable.delete(from, to)
            from > 0 -> editable.delete(from - 1, from)
        }
    }

    /** Selection as an ordered pair; a field that never had focus counts as "at the end". */
    private fun selectionRange(field: android.widget.EditText): Pair<Int, Int> {
        val length = field.length()
        val start = field.selectionStart.takeIf { it >= 0 } ?: length
        val end = field.selectionEnd.takeIf { it >= 0 } ?: length
        return minOf(start, end) to maxOf(start, end)
    }

    private fun setDial(number: String) {
        binding.textDialNumber.setText(number)
        // setText drops the cursor at 0; a whole new number is read from its end.
        binding.textDialNumber.setSelection(binding.textDialNumber.length())
    }


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
        val hasNumber = number.isNotEmpty()
        binding.buttonBackspace.visibility = if (hasNumber) View.VISIBLE else View.INVISIBLE
        // Straight off the pool the view model already holds. This used to fire a
        // PhoneLookup on a background thread for every keypress — a
        // content-provider round trip per digit, on the one screen whose whole job
        // is to keep up with digits.
        applyAddContactVisibility()
        applyZeroState()
        viewModel.filter(number)
    }

    /**
     * The action card is what the screen shows about the typed number. For an
     * **unknown** number it heads with the digits and "Not in your contacts" and
     * offers "Add to contacts". Once the whole number is a saved contact's it
     * heads with that contact's name over its number instead, keeps only
     * Message and WhatsApp, and
     * replaces the match list - which would only have been that same contact as
     * a single row. While the digits are still partial and only some matches are
     * named, the card stays away and the list does the talking.
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
        // The whole number belongs to a saved contact: the card heads with that
        // contact instead of the digits, and takes the place of the one-row
        // match list that would otherwise repeat it underneath.
        val saved = if (hasNumber) viewModel.savedContact(number) else null
        val savedName = saved?.name?.takeIf { it.isNotBlank() }
        val unknown = hasNumber && saved == null && !hasNamedMatch

        binding.textDialActionsNumber.text = savedName ?: number
        if (saved != null) {
            binding.textDialActionsStatus.text = saved.number
        } else {
            binding.textDialActionsStatus.setText(R.string.dialer_number_not_saved)
        }
        // Same avatar rule as the Contacts and Recents rows, so one number does
        // not get three different treatments across three tabs.
        binding.textDialHeadAvatar.text = CallActionHandler.initials(savedName.orEmpty(), number)
        binding.textDialHeadAvatar.backgroundTintList =
            AvatarPalette.tintFor(ctx, savedName ?: number)
        binding.rowAddContact.isVisible = saved == null
        binding.rowDialMessage.isVisible = dialable
        // A saved contact is someone the user already knows: the card offers only
        // the two ways to reach them, not identifying them or asking about them.
        binding.rowDialLookup.isVisible = dialable && saved == null
        binding.rowDialWhatsApp.isVisible = dialable && whatsAppPackage() != null
        binding.rowDialAskAi.isVisible = dialable && saved == null &&
            AiFeatureConfig.isEnabled(ctx) && SettingsRepository(ctx).aiHomeButtonEnabled

        arrangeGrid(savedContact = saved != null)

        // The grid's rows are fixed pairs, so a row whose cells have both gone
        // would otherwise hold open 52dp of nothing — which is exactly what
        // happens on a phone with no WhatsApp and the assistant switched off.
        binding.gridRowOne.isVisible = hasVisibleCell(binding.gridRowOne)
        binding.gridRowTwo.isVisible = hasVisibleCell(binding.gridRowTwo)
        binding.gridRowThree.isVisible = hasVisibleCell(binding.gridRowThree)

        binding.columnDialActions.isVisible = unknown || saved != null
        val showMatches = hasFrequent && saved == null
        binding.textMatchesLabel.isVisible = showMatches
        binding.listFrequent.isVisible = showMatches
    }

    /**
     * Lays the action cells out for the card's two headings.
     *
     * A saved contact has no "Add", and leaving its slot empty put "Send
     * message" alone on the first row with a hole beside it. So for a contact
     * WhatsApp moves up beside Message - the only two actions a contact gets -
     * and for an unknown number the cells go back to the original order.
     * Every cell shares one style, so moving one between rows keeps its size.
     * Only moves a cell that is out of place, since this runs on every keystroke.
     */
    private fun arrangeGrid(savedContact: Boolean) {
        val one = binding.gridRowOne
        val two = binding.gridRowTwo
        if (savedContact) {
            place(binding.rowDialMessage, one, 0)
            place(binding.rowDialWhatsApp, one, 1)
        } else {
            place(binding.rowAddContact, one, 0)
            place(binding.rowDialMessage, one, 1)
            place(binding.rowDialLookup, two, 0)
            place(binding.rowDialWhatsApp, two, 1)
            place(binding.rowDialAskAi, binding.gridRowThree, 0)
        }
    }

    private fun place(cell: View, row: ViewGroup, index: Int) {
        if (cell.parent === row && row.indexOfChild(cell) == index) return
        (cell.parent as? ViewGroup)?.removeView(cell)
        row.addView(cell, index)
    }

    /** A grid row with a visible action in it. Its spacer has no id and never counts. */
    private fun hasVisibleCell(row: ViewGroup): Boolean =
        row.children.any { it.id != View.NO_ID && it.isVisible }

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

    private companion object {
        /** Digits, the "+" of a country code, and the pause/wait/service keys. */
        const val DIALABLE = "0123456789+*#,;"
    }
}
