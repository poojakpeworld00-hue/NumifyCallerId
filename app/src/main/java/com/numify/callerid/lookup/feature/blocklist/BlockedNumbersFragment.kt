package com.numify.callerid.lookup.feature.blocklist

import android.Manifest
import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.repository.BlockedNumber
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.repository.ContactRepository
import com.numify.callerid.lookup.databinding.ActivityBlocklistBinding
import com.numify.callerid.lookup.databinding.DialogBlockAddBinding
import com.numify.callerid.lookup.databinding.DialogBlockDetailsBinding
import com.numify.callerid.lookup.databinding.DialogBlockRecentsBinding
import com.numify.callerid.lookup.databinding.DialogEnableCallerIdBinding
import com.numify.callerid.lookup.databinding.IncludeBlockAddFormBinding
import com.numify.callerid.lookup.common.CallerIdCoordinator
import com.numify.callerid.lookup.common.ListDividerDecoration
import com.numify.callerid.lookup.feature.finder.CountryCatalog
import com.numify.callerid.lookup.feature.finder.CountryPickerActivity
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.RewardedAdPresenter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.numify.callerid.monetize.strategy.recordPermissionOutcome

class BlockedNumbersFragment : BaseFragment<ActivityBlocklistBinding>() {

    /** Blocklist: mid native at the top, under the summary strip. */
    override val screenAdFormat = ScreenAdFormat.MID_NATIVE

    private val viewModel: BlocklistViewModel by viewModels()

    // Row actions are block-management too, so they pass through the Caller-ID gate.
    private val adapter = BlockedNumberAdapter(
        onUnblock = { entry -> requireCallerId { unblock(entry) } },
        onRowClick = { entry -> requireCallerId { showDetails(entry) } }
    )

    /** The single live "Enable Caller ID" dialog, if any (prevents duplicates). */
    private var enableCallerIdDialog: Dialog? = null

    /**
     * The block action the user attempted while Caller ID was still off - "Add
     * number", for example. It is held so that the moment the role is granted we
     * resume *that* action by opening its dialog, rather than leaving the user
     * stranded on the list.
     */
    private var pendingCallerIdAction: (() -> Unit)? = null

    /** Looping/entrance animators for the enable dialog; cancelled on dismiss. */
    private val enableDialogAnimators = mutableListOf<Animator>()

    /**
     * Re-checks Caller ID after the system role dialog returns and closes the
     * enable-prompt once the role is held.
     */
    private val callerIdRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshCallerIdGate() }

    /**
     * Where a number picked from contacts or recents should end up. Null means
     * block it immediately, which is what the empty-state rows want. The add
     * dialog sets it so a pick populates its field instead, leaving the user to
     * confirm with Block.
     */
    private var onNumberPicked: ((String) -> Unit)? = null

    /**
     * The form whose country chip was tapped. Two forms are live at once - the
     * dialog's and the empty state's - so the picker result has to return to
     * whichever one asked, rather than to "the" form.
     */
    private var pickingCountryFor: AddForm? = null

    /** The empty state's inline form, built once the empty state is shown. */
    private var emptyForm: AddForm? = null

    /** Country chip → searchable country list → back onto that form's chip. */
    private val countryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val dial = data.getStringExtra(CountryPickerActivity.EXTRA_DIAL) ?: return@registerForActivityResult
        val iso = data.getStringExtra(CountryPickerActivity.EXTRA_ISO).orEmpty()
        pickingCountryFor?.setCountry(iso, dial)
    }

    /**
     * A single live "block a number" form, belonging either to the dialog or to
     * the empty state.
     *
     * The dial code is held here rather than on the fragment because each form
     * carries its own country chip and its own field; one shared value would let
     * the country chosen in one form silently apply to the other form's number.
     */
    private inner class AddForm(private val view: IncludeBlockAddFormBinding) {

        private var dial: String = ""

        init {
            val iso = defaultIso()
            setCountry(iso, CountryCatalog.dialOf(iso).orEmpty())

            view.buttonCountry.setOnClickListener {
                pickingCountryFor = this
                countryPickerLauncher.launch(
                    Intent(requireContext(), CountryPickerActivity::class.java)
                )
            }
            // The chips fill THIS form's field; confirming is still the Block
            // button, so a mis-tap in a picker never blocks anyone outright.
            view.buttonFromContacts.setOnClickListener {
                onNumberPicked = { number -> view.inputNumber.setText(number) }
                pickFromContacts()
            }
            view.buttonFromRecents.setOnClickListener {
                onNumberPicked = { number -> view.inputNumber.setText(number) }
                pickFromRecents()
            }
        }

        fun setCountry(iso: String, dialCode: String) {
            dial = dialCode
            view.textDialCode.text = dialCode
            view.textFlag.text = if (iso.length == 2) CountryCatalog.flag(iso) else ""
        }

        /**
         * Combines the country chip with whatever was typed. A number that already
         * carries its own "+", as anything picked from contacts or the call log
         * usually does, is left untouched, so a second country code is never
         * prefixed onto it.
         */
        fun compose(): String {
            val n = view.inputNumber.text?.toString()?.trim().orEmpty()
            if (n.isEmpty()) return ""
            return if (n.startsWith("+")) n else dial + n
        }

        fun clear() = view.inputNumber.setText("")
    }

    /** Asks for contacts access, then opens the in-app contacts picker once granted. */
    private val contactsPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        context?.recordPermissionOutcome(Manifest.permission.READ_CONTACTS, granted)
        if (granted) showContactsPicker()
        else Toast.makeText(requireContext(), R.string.blocklist_perm_contacts, Toast.LENGTH_SHORT).show()
    }

    /** Asks for call-log access, then opens the recent-calls picker once granted. */
    private val callLogPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        context?.recordPermissionOutcome(Manifest.permission.READ_CALL_LOG, granted)
        if (granted) showRecentsPicker()
        else Toast.makeText(requireContext(), R.string.blocklist_perm_calllog, Toast.LENGTH_SHORT).show()
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        ActivityBlocklistBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hosted as a tab: the shell owns the bottom nav, so only the top inset applies.
        ViewCompat.setOnApplyWindowInsetsListener(binding.blocklistRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }
        binding.buttonBack.visibility = View.GONE
        binding.listBlocklist.layoutManager = LinearLayoutManager(requireContext())
        binding.listBlocklist.adapter = adapter
        // Hairlines between rows inside the card — the same decoration the
        // recents and contacts lists use. No headings here to skip.
        binding.listBlocklist.addItemDecoration(
            ListDividerDecoration(binding.listBlocklist)
        )

        // With nothing blocked yet, the empty state IS the add form — no menu in
        // front of it. The FAB opens the same form in a dialog.
        emptyForm = AddForm(binding.emptyForm)
        binding.buttonEmptyBlock.setOnClickListener {
            requireCallerId {
                val form = emptyForm ?: return@requireCallerId
                blockNumber(form.compose())
                form.clear()
            }
        }
        binding.fabAdd.setOnClickListener { requireCallerId { showAddDialog() } }
    }

    private var emptyCascaded = false

    /** Rises the empty state's form card in the first time it shows. */
    private fun cascadeEmptyMethods() {
        if (emptyCascaded) return
        emptyCascaded = true
        val card = binding.emptyFormCard
        card.alpha = 0f
        card.translationY = resources.displayMetrics.density * 10f
        card.animate().alpha(1f).translationY(0f).setDuration(320L).start()
    }

    override fun initObservers() {
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)
            val empty = rows.isEmpty()
            binding.emptyScroll.visibility = if (empty) View.VISIBLE else View.GONE
            binding.populatedGroup.visibility = if (empty) View.GONE else View.VISIBLE
            if (empty) cascadeEmptyMethods()
        }
        viewModel.count.observe(viewLifecycleOwner) { count ->
            binding.textSummaryCount.text =
                resources.getQuantityString(R.plurals.blocklist_blocked_count, count, count)
        }
    }

    /** Custom (non-system) details dialog with an Unblock action. */
    private fun showDetails(entry: BlockedNumber) {
        val view = DialogBlockDetailsBinding.inflate(layoutInflater)
        val dialog = customDialog(view.root)

        view.textDetailNumber.text = entry.number
        if (entry.addedAt > 0L) {
            val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(entry.addedAt))
            view.textDetailAdded.text = getString(R.string.blocklist_details_added, date)
        } else {
            view.textDetailAdded.visibility = View.GONE
        }

        view.buttonClose.setOnClickListener { dialog.dismiss() }
        view.buttonUnblock.setOnClickListener {
            dialog.dismiss()
            unblock(entry)
        }
        dialog.show()
    }

    private fun unblock(entry: BlockedNumber) {
        viewModel.remove(entry.number)
        Toast.makeText(requireContext(), R.string.blocklist_removed, Toast.LENGTH_SHORT).show()
    }

    /**
     * The add-to-blocklist dialog: type a number, or fill it from contacts or the
     * call log without leaving the dialog. Confirmation is always the Block
     * button, so a mis-tap inside a picker never blocks somebody outright.
     */
    private fun showAddDialog() {
        val view = DialogBlockAddBinding.inflate(layoutInflater)
        val dialog = customDialog(view.root)
        val form = AddForm(view.form)

        view.buttonCancel.setOnClickListener { dialog.dismiss() }
        view.buttonAdd.setOnClickListener {
            blockNumber(form.compose())
            dialog.dismiss()
        }
        dialog.setOnDismissListener {
            // Leaving these set would point a later pick, or a country result, at
            // a form whose window is already gone.
            onNumberPicked = null
            if (pickingCountryFor === form) pickingCountryFor = null
        }
        dialog.show()
    }

    /** SIM (then network, then device locale) region for the country chip. */
    private fun defaultIso(): String {
        val tm = context?.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val iso = tm?.simCountryIso?.takeIf { it.length == 2 }
            ?: tm?.networkCountryIso?.takeIf { it.length == 2 }
            ?: Locale.getDefault().country.takeIf { it.length == 2 }
            ?: "US"
        return iso.uppercase()
    }

    // --- From contacts (in-app dialog — never leaves the app) ---

    private fun pickFromContacts() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showContactsPicker()
        else requestPermissionManaged(Manifest.permission.READ_CONTACTS, contactsPermLauncher)
    }

    private fun showContactsPicker() {
        val contacts = ContactRepository(requireContext()).getContacts()
            .filter { it.detail.isNotBlank() }
            .map { CallRecord(it.name, it.detail, CallType.OUTGOING, 0L, 0L) }
            .distinctBy { it.number }
            .take(200)

        showPickDialog(R.string.blocklist_pick_contacts_title, R.string.blocklist_no_contacts, contacts)
    }

    // --- Block from recent calls ---

    private fun pickFromRecents() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) showRecentsPicker()
        else requestPermissionManaged(Manifest.permission.READ_CALL_LOG, callLogPermLauncher)
    }

    private fun showRecentsPicker() {
        val recents = CallLogRepository(requireContext()).getCalls(limit = 200)
            .filter { it.number.isNotBlank() && !it.number.equals("Unknown", ignoreCase = true) }
            .distinctBy { it.number }
            .take(50)

        showPickDialog(R.string.blocklist_pick_recents_title, R.string.blocklist_no_recents, recents)
    }

    /**
     * Shared in-app picker dialog for both contacts and recents — same list UI,
     * just a different title, empty message and data set.
     */
    private fun showPickDialog(
        @StringRes titleRes: Int,
        @StringRes emptyRes: Int,
        items: List<CallRecord>
    ) {
        val view = DialogBlockRecentsBinding.inflate(layoutInflater)
        val dialog = customDialog(view.root)

        view.textTitle.setText(titleRes)
        view.textNoRecents.setText(emptyRes)

        val pickAdapter = BlockOptionAdapter { entry ->
            dialog.dismiss()
            // From the add dialog a pick fills its field; from the empty-state
            // rows there is no dialog to fill, so it blocks straight away.
            onNumberPicked?.invoke(entry.number) ?: blockNumber(entry.number)
        }
        view.listRecents.layoutManager = LinearLayoutManager(requireContext())
        view.listRecents.adapter = pickAdapter
        pickAdapter.submit(items)

        view.listRecents.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        view.textNoRecents.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        view.buttonClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Single funnel for all three methods: de-dupes, clears the reward gate and
     * reports the outcome.
     *
     * The duplicate check runs first, so re-adding a number that is already
     * blocked never costs an ad.
     */
    private fun blockNumber(raw: String) {
        val number = raw.trim()
        if (number.isEmpty()) return
        if (viewModel.isNumberBlocked(number)) {
            Toast.makeText(requireContext(), R.string.blocklist_already_blocked, Toast.LENGTH_SHORT).show()
            return
        }
        val host = activity ?: return
        BlockReward.allow(host, number) {
            viewModel.add(number)
            Toast.makeText(requireContext(), R.string.blocklist_added, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Caller-ID gate (all block management requires the CallScreening role) ---

    /**
     * Runs [action] only when Caller ID is enabled; otherwise prompts the user to
     * enable it. Single funnel for every block-management entry point.
     */
    private fun requireCallerId(action: () -> Unit) {
        if (CallerIdCoordinator.isCallerIdEnabled(requireContext())) {
            action()
        } else {
            pendingCallerIdAction = action
            showEnableCallerIdDialog()
        }
    }

    /**
     * Animated bottom-sheet prompt shown when a block action is attempted while
     * Caller ID is off. Guarded so rapid taps can't stack copies.
     */
    private fun showEnableCallerIdDialog() {
        if (enableCallerIdDialog?.isShowing == true) return

        val view = DialogEnableCallerIdBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext()).apply {
            setContentView(view.root)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                setWindowAnimations(R.style.SheetSlideAnimation)
                setDimAmount(0.55f)
            }
        }

        view.buttonNotNow.setOnClickListener {
            pendingCallerIdAction = null
            dialog.dismiss()
        }
        view.buttonEnable.setOnClickListener {
            dialog.dismiss()
            requestEnableCallerId()
        }
        dialog.setOnDismissListener {
            cancelEnableDialogAnimators()
            if (enableCallerIdDialog === dialog) enableCallerIdDialog = null
        }
        enableCallerIdDialog = dialog
        dialog.show()
        animateEnableCallerIdDialog(view)
    }

    /**
     * Drives the dialog's motion once it is on screen: the shield pops in with an
     * overshoot, a ring pulses outward, the checkmark draws itself as an AVD, the
     * hint strip reveals, the CTA breathes, and the demo toggle loops off to on.
     */
    private fun animateEnableCallerIdDialog(v: DialogEnableCallerIdBinding) {
        v.shieldTile.alpha = 0f
        v.shieldTile.scaleX = 0.4f
        v.shieldTile.scaleY = 0.4f
        v.shieldTile.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(80L).setDuration(440L)
            .setInterpolator(OvershootInterpolator(2.4f))
            .start()

        AnimatedVectorDrawableCompat.create(requireContext(), R.drawable.motion_shield)?.let { avd ->
            v.shieldIcon.setImageDrawable(avd)
            avd.start()
        }

        v.shieldRing.alpha = 0f
        loopAnimator(v.shieldRing, View.SCALE_X, 0.7f, 1.5f, 1500L, 220L, DecelerateInterpolator())
        loopAnimator(v.shieldRing, View.SCALE_Y, 0.7f, 1.5f, 1500L, 220L, DecelerateInterpolator())
        loopAnimator(v.shieldRing, View.ALPHA, 0.7f, 0f, 1500L, 220L, DecelerateInterpolator())

        v.hintStrip.alpha = 0f
        v.hintStrip.translationY = 10f * resources.displayMetrics.density
        v.hintStrip.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(620L).setDuration(340L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        loopAnimator(
            v.buttonEnable, View.SCALE_X, 1f, 1.03f, 1300L, 900L,
            AccelerateDecelerateInterpolator(), ValueAnimator.REVERSE
        )
        loopAnimator(
            v.buttonEnable, View.SCALE_Y, 1f, 1.03f, 1300L, 900L,
            AccelerateDecelerateInterpolator(), ValueAnimator.REVERSE
        )

        v.flipTrack.post { if (isAdded && view != null) startToggleDemo(v) }
    }

    /** Starts one infinite [ObjectAnimator], registering it for later cancellation. */
    private fun loopAnimator(
        target: View,
        property: android.util.Property<View, Float>,
        from: Float,
        to: Float,
        duration: Long,
        startDelay: Long,
        interpolator: android.view.animation.Interpolator,
        repeatMode: Int = ValueAnimator.RESTART
    ) {
        ObjectAnimator.ofFloat(target, property, from, to).apply {
            this.duration = duration
            this.startDelay = startDelay
            this.interpolator = interpolator
            this.repeatCount = ValueAnimator.INFINITE
            this.repeatMode = repeatMode
            enableDialogAnimators.add(this)
            start()
        }
    }

    /**
     * Loops the illustrative toggle in the hint strip: track fades grey→green, the
     * thumb slides across with a tap ripple, holds, then resets.
     */
    private fun startToggleDemo(v: DialogEnableCallerIdBinding) {
        val marginStart = (v.flipThumb.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart ?: 0
        val travel = (v.flipTrack.width - v.flipThumb.width - 2 * marginStart).toFloat()
        if (travel <= 0f) return

        val offColor = ContextCompat.getColor(requireContext(), R.color.cid_toggle_off)
        val onColor = ContextCompat.getColor(requireContext(), R.color.online_green)
        val argb = ArgbEvaluator()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2600L
            repeatCount = ValueAnimator.INFINITE
            interpolator = null // linear; phase timing is handled below
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float

                val on = when {
                    t < 0.30f -> 0f
                    t < 0.40f -> ease((t - 0.30f) / 0.10f)
                    t < 0.86f -> 1f
                    t < 0.96f -> 1f - ease((t - 0.86f) / 0.10f)
                    else -> 0f
                }
                v.flipTrack.backgroundTintList =
                    ColorStateList.valueOf(argb.evaluate(on, offColor, onColor) as Int)
                v.flipThumb.translationX = on * travel

                val r = when {
                    t < 0.30f -> -1f
                    t < 0.52f -> (t - 0.30f) / 0.22f
                    else -> -1f
                }
                if (r in 0f..1f) {
                    v.flipRipple.alpha = (1f - r) * 0.8f
                    val s = 0.4f + r * 1.7f
                    v.flipRipple.scaleX = s
                    v.flipRipple.scaleY = s
                } else {
                    v.flipRipple.alpha = 0f
                }
            }
            enableDialogAnimators.add(this)
            start()
        }
    }

    /** Decelerating ease for the toggle demo (1-(1-x)^2). */
    private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x)

    /** Cancels & clears every tracked enable-dialog animator (idempotent). */
    private fun cancelEnableDialogAnimators() {
        enableDialogAnimators.forEach { it.cancel() }
        enableDialogAnimators.clear()
    }

    /** Launches the system CallScreening role request (no-op if already held/unavailable). */
    private fun requestEnableCallerId() {
        val intent = CallerIdCoordinator.buildEnableIntent(requireContext()) ?: run {
            refreshCallerIdGate(); return
        }
        // We're leaving to a system page — don't let the return trip trigger an App Open ad.
        AppOpenAdManager.skipNextAppOpenAd = true
        runCatching { callerIdRoleLauncher.launch(intent) }
    }

    /**
     * Re-evaluates the gate. Once Caller ID is on, the enable prompt is dismissed
     * and block management becomes usable again immediately. Called after the role
     * round-trip and on every resume.
     */
    private fun refreshCallerIdGate() {
        if (view == null) return
        if (!CallerIdCoordinator.isCallerIdEnabled(requireContext())) return
        enableCallerIdDialog?.dismiss()
        enableCallerIdDialog = null

        val resume = pendingCallerIdAction ?: return
        pendingCallerIdAction = null
        binding.root.post { if (isAdded && view != null) resume() }
    }

    override fun onResume() {
        super.onResume()
        refreshCallerIdGate()
        // Warm the rewarded ad that BlockReward shows once the free slots are
        // gone. Without this every gated block falls through to the fallback and
        // the gate never actually shows an ad.
        RewardedAdPresenter.preload(requireContext())
    }

    /** Re-checks the gate when this tab becomes visible again (show/hide keeps it resumed). */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) refreshCallerIdGate()
    }

    override fun onDestroyView() {
        // Avoid leaking the dialog window / animators across view recreation.
        cancelEnableDialogAnimators()
        enableCallerIdDialog?.dismiss()
        enableCallerIdDialog = null
        pendingCallerIdAction = null
        super.onDestroyView()
    }

    /** Wraps a custom view in a transparent, centered, ~88%-width dialog. */
    private fun customDialog(content: View): Dialog = Dialog(requireContext()).apply {
        setContentView(content)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
