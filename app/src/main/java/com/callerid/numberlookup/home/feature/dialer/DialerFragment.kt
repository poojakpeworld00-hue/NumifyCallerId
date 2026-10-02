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
import com.callerid.numberlookup.home.feature.calldetails.CallDetailsActivity
import com.callerid.numberlookup.home.feature.finder.LookupActivity
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.repository.BlocklistRepository
import com.callerid.numberlookup.home.repository.ContactRepository
import com.callerid.numberlookup.home.feature.blocklist.BlockReward
import com.callerid.numberlookup.home.feature.blocklist.CallerIdGate
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
    // A row's call button calls at once. It used to type the number into the
    // keypad first, which left whatever had been typed replaced by it and made
    // a one-tap call look like two steps. Tapping the row still fills the keypad.
    private val adapter = SpeedDialAdapter(onClick = ::setDial, onCall = { placeCall(it) })

    /**
     * Block and Unblock need Caller ID, as on the Blocklist tab: with it off,
     * the same "Enable Caller ID" sheet asks first, and the block goes through
     * by itself once it is granted.
     */
    private val callerIdGate = CallerIdGate(this)

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
        // Headed by a saved contact, the row opens that contact, as a row on the
        // Contacts tab does. Only then: an unknown number has Lookup below it,
        // and a code saved as a contact has no call history to show.
        binding.rowDialNumberHead.setOnClickListener {
            val number = dialedNumber()
            if (viewModel.isServiceCode(number)) return@setOnClickListener
            val saved = viewModel.savedContact(number) ?: return@setOnClickListener
            requireActivity().openActivity(
                CallDetailsActivity.newIntent(
                    requireContext(), saved.number, saved.name, R.string.contact_detail_title
                )
            )
        }

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
        binding.buttonContactsPermission.setOnClickListener { askForContacts() }
        binding.rowDialFavourite.setOnClickListener { toggleFavourite() }
        binding.rowDialBlock.setOnClickListener { callerIdGate.require { toggleBlock() } }
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
        syncContactsPermission()
        callerIdGate.refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            releaseSoftInput()
        } else {
            claimSoftInput()
            syncContactsPermission()
            callerIdGate.refresh()
        }
    }

    override fun onDestroyView() {
        callerIdGate.release()
        super.onDestroyView()
    }

    /** Last known READ_CONTACTS state, so a grant made elsewhere triggers one reload. */
    private var hadContacts: Boolean? = null

    private fun hasContactsPermission(): Boolean = context?.let {
        ContextCompat.checkSelfPermission(it, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    } == true

    /**
     * Picks up contacts access granted outside this tab — on Contacts, from the
     * permission sheet, or in system Settings. Granting a permission changes no
     * data, so the content observer never fires, and the pool would go on
     * treating every saved number as unknown until something else reloaded it.
     */
    private fun syncContactsPermission() {
        if (view == null) return
        val has = hasContactsPermission()
        if (hadContacts == false && has) viewModel.load()
        hadContacts = has
        applyZeroState()
    }

    /**
     * The card's "Turn on". Android stops showing the dialog after the second
     * refusal and the request then fails silently, so in that case this goes
     * straight to the app's Settings page instead of a button that does nothing.
     */
    private fun askForContacts() {
        val ctx = context ?: return
        val permission = Manifest.permission.READ_CONTACTS
        val blocked = SettingsRepository(ctx).hasRequestedPermission(permission) &&
            !shouldShowRequestPermissionRationale(permission)
        if (blocked) {
            openAppSettings()
            return
        }
        requestPermissionChain(listOf(permission)) { syncContactsPermission() }
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
        val serviceCode = viewModel.isServiceCode(number)
        val dialable = hasNumber && !serviceCode && DialedNumberCheck.isLookupable(ctx, number)
        // The whole number belongs to a saved contact: the card heads with that
        // contact instead of the digits, and takes the place of the one-row
        // match list that would otherwise repeat it underneath.
        val saved = if (hasNumber) viewModel.savedContact(number) else null
        val savedName = saved?.name?.takeIf { it.isNotBlank() }
        val unknown = hasNumber && saved == null && !hasNamedMatch

        binding.textDialActionsNumber.text = savedName ?: number
        // Tappable (and so rippling) only when it leads somewhere; see the
        // listener in initView.
        binding.rowDialNumberHead.isClickable = saved != null && !serviceCode
        if (saved != null) {
            binding.textDialActionsStatus.text = saved.number
        } else if (serviceCode) {
            // A code is run, not saved or looked up: say so instead of
            // "Not in your contacts", which reads as if a search came up empty.
            binding.textDialActionsStatus.setText(R.string.dialer_service_code)
        } else {
            binding.textDialActionsStatus.setText(R.string.dialer_number_not_saved)
        }
        // Same avatar rule as the Contacts and Recents rows, so one number does
        // not get three different treatments across three tabs.
        binding.textDialHeadAvatar.text = CallActionHandler.initials(savedName.orEmpty(), number)
        binding.textDialHeadAvatar.backgroundTintList =
            AvatarPalette.tintFor(ctx, savedName ?: number)
        binding.rowAddContact.isVisible = saved == null && !serviceCode
        binding.rowDialMessage.isVisible = dialable
        // A saved contact is someone the user already knows: the card offers only
        // the two ways to reach them, not identifying them or asking about them.
        binding.rowDialLookup.isVisible = dialable && saved == null
        binding.rowDialWhatsApp.isVisible = dialable && whatsAppPackage() != null
        binding.rowDialAskAi.isVisible = dialable && saved == null &&
            AiFeatureConfig.isEnabled(ctx) && SettingsRepository(ctx).aiHomeButtonEnabled
        // A contact can be starred; a number with no contact behind it cannot.
        binding.rowDialFavourite.isVisible = saved != null && !serviceCode
        // Block for any whole number, saved or not. Half a number is not one
        // anyone means to block.
        binding.rowDialBlock.isVisible = !serviceCode && (dialable || saved != null)
        if (binding.rowDialBlock.isVisible) paintBlock(blockTarget(number, saved))
        if (saved != null && !serviceCode) syncFavourite(saved.number) else favouriteFor = null

        arrangeGrid(savedContact = saved != null)

        // The grid's rows are fixed pairs, so a row whose cells have both gone
        // would otherwise hold open 52dp of nothing — which is exactly what
        // happens on a phone with no WhatsApp and the assistant switched off.
        val rows = gridRows()
        rows.forEach { it.isVisible = hasVisibleCell(it) }
        // No actions at all, as for a service code: drop the grid's padding and
        // the rule over it too, or a divider sits under the heading with
        // nothing beneath it.
        val anyAction = rows.any { it.isVisible }
        binding.gridDialActions.isVisible = anyAction
        binding.dividerDialActions.isVisible = anyAction

        binding.columnDialActions.isVisible = unknown || saved != null
        val showMatches = hasFrequent && saved == null
        binding.textMatchesLabel.isVisible = showMatches
        binding.listFrequent.isVisible = showMatches
    }

    private fun gridRows(): List<ViewGroup> = listOf(
        binding.gridRowOne, binding.gridRowTwo, binding.gridRowThree, binding.gridRowFour,
    )

    /**
     * Lays the action cells out for the card's two headings.
     *
     * Visible cells flow into the rows two at a time, in the heading's order,
     * and hidden ones go to the back. Fixed slots left a hole wherever a cell
     * was hidden: with no WhatsApp installed, Lookup and Ask AI each sat alone
     * on a row of their own. A saved contact leads with the ways to reach them
     * (Message, WhatsApp), then Favourite and Block; an unknown number keeps
     * Add first and Block last. Every cell shares one style, so moving one
     * between rows keeps its size, and only a cell out of place is moved, since
     * this runs on every keystroke.
     */
    private fun arrangeGrid(savedContact: Boolean) {
        val order = if (savedContact) {
            listOf(
                binding.rowDialMessage, binding.rowDialWhatsApp,
                binding.rowDialFavourite, binding.rowDialBlock,
                binding.rowAddContact, binding.rowDialLookup, binding.rowDialAskAi,
            )
        } else {
            listOf(
                binding.rowAddContact, binding.rowDialMessage,
                binding.rowDialLookup, binding.rowDialWhatsApp,
                binding.rowDialAskAi, binding.rowDialBlock,
                binding.rowDialFavourite,
            )
        }
        val rows = gridRows()
        order.sortedBy { !it.isVisible }
            .forEachIndexed { i, cell -> place(cell, rows[i / 2], i % 2) }
    }

    // ── Favourite / Block ───────────────────────────────────────────────────

    /** Contact number whose star [favouriteStarred] describes; null = unknown. */
    private var favouriteFor: String? = null
    private var favouriteStarred = false

    /**
     * Reads the star for [number] once per contact, off the main thread: this
     * is called on every keystroke, and the star is a provider query.
     */
    private fun syncFavourite(number: String) {
        if (favouriteFor == number) return
        favouriteFor = number
        paintFavourite(false)
        viewLifecycleOwner.lifecycleScope.launch {
            val starred = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ContactRepository(requireContext()).isStarred(number)
            }
            if (favouriteFor == number) paintFavourite(starred)
        }
    }

    private fun paintFavourite(starred: Boolean) {
        favouriteStarred = starred
        binding.textDialFavourite.setText(
            if (starred) R.string.dialer_grid_unfavourite else R.string.dialer_grid_favourite
        )
        binding.rowDialFavourite.contentDescription = getString(
            if (starred) R.string.action_unfavourite else R.string.action_favourite
        )
    }

    /**
     * Stars or unstars the contact, the same write Call Details makes
     * (Contacts.STARRED, so the system app and our Favourites agree). Needs
     * contacts read and write; asked for on the first tap, not before.
     */
    private fun toggleFavourite() {
        val saved = viewModel.savedContact(dialedNumber()) ?: return
        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val ctx = context ?: return
        if (needed.any { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissionChain(needed) {
                if (needed.all {
                        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                    }
                ) toggleFavourite()
            }
            return
        }
        val wanted = !favouriteStarred
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ContactRepository(ctx).setStarred(saved.number, wanted)
            }
            if (!ok) {
                Toast.makeText(ctx, R.string.favourite_needs_contact, Toast.LENGTH_SHORT).show()
                return@launch
            }
            favouriteFor = saved.number
            paintFavourite(wanted)
            Toast.makeText(
                ctx, if (wanted) R.string.favourite_added else R.string.favourite_removed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** The saved contact's own number when there is one, else what was typed. */
    private fun blockTarget(
        typed: String,
        saved: com.callerid.numberlookup.home.repository.ContactPhone?,
    ): String = saved?.number ?: typed

    /** Red "Block", or green "Unblock" once the number is on the blocklist. */
    private fun paintBlock(number: String) {
        val ctx = context ?: return
        val blocked = BlocklistRepository(ctx).isNumberBlocked(number)
        val label = if (blocked) R.string.action_unblock else R.string.action_block
        binding.textDialBlock.setText(label)
        binding.imageDialBlock.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (blocked) R.color.ds_success else R.color.ds_danger)
        )
    }

    /**
     * Blocks or unblocks, as Call Details does: only blocking goes through the
     * reward gate, since unblocking hands a slot back and must never cost an ad.
     */
    private fun toggleBlock() {
        val number = blockTarget(dialedNumber(), viewModel.savedContact(dialedNumber()))
        if (number.isBlank()) return
        val blocklist = BlocklistRepository(requireContext())
        if (blocklist.isNumberBlocked(number)) {
            blocklist.remove(number)
            paintBlock(number)
            Toast.makeText(requireContext(), R.string.blocklist_removed, Toast.LENGTH_SHORT).show()
            return
        }
        BlockReward.allow(requireActivity(), number) {
            blocklist.add(number)
            if (view != null) paintBlock(number)
            context?.let { Toast.makeText(it, R.string.blocklist_added, Toast.LENGTH_SHORT).show() }
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
        val untouched = dialedNumber().isEmpty()
        // The contacts prompt shares the untouched band, and over it the
        // invitation to dial would sit under a card asking for something else.
        val askContacts = untouched && !hasContactsPermission()
        binding.cardContactsPermission.isVisible = askContacts
        binding.textEmpty.isVisible = untouched && !hasFrequent && !askContacts
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
