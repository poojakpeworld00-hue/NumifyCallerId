package com.contacts.callerid.number.lookup.feature.calldetails

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.foundation.BaseActivity
import com.contacts.callerid.number.lookup.monetize.delivery.RewardedAdPresenter
import com.contacts.callerid.number.lookup.feature.blocklist.BlockReward
import com.contacts.callerid.number.lookup.repository.BlocklistRepository
import com.contacts.callerid.number.lookup.repository.CallRecord
import com.contacts.callerid.number.lookup.repository.CallType
import com.contacts.callerid.number.lookup.databinding.ActivityCallDetailBinding
import com.contacts.callerid.number.lookup.databinding.ItemCallHistoryBinding
import com.contacts.callerid.number.lookup.feature.finder.LookupActivity
import com.contacts.callerid.number.lookup.feature.widgets.CallActionHandler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Per-number call detail screen opened from a recents row. */
class CallDetailsActivity : BaseActivity<ActivityCallDetailBinding>() {

    override val layoutId: Int = R.layout.activity_call_detail

    private val viewModel: CallDetailsViewModel by viewModels()

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val fallbackName by lazy { intent.getStringExtra(EXTRA_NAME) }

    private var history: List<CallRecord> = emptyList()
    private var expanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }

        binding.buttonCall.setOnClickListener { placeCall(number) }
        binding.buttonMessage.setOnClickListener { message() }
        binding.buttonWhatsapp.setOnClickListener { whatsapp() }
        binding.buttonBlock.setOnClickListener { toggleBlock() }
        binding.buttonIdentify.setOnClickListener { identifyNumber() }
        binding.buttonViewAll.setOnClickListener { expanded = true; renderHistory() }

        // Ready for the block gate, which shows a rewarded ad once the free
        // slots are used up.
        RewardedAdPresenter.preload(this)
    }

    /**
     * Re-read on every entry, not just on create: the number can be blocked or
     * unblocked from the blocklist screen while this one sits in the back stack,
     * and coming back to a button that still says "Block" on a blocked number is
     * how you end up blocking it twice.
     */
    override fun onResume() {
        super.onResume()
        updateBlockState()
    }

    override fun initObservers() {
        viewModel.ui.observe(this) { ui ->
            bindHero(ui)
            binding.textTotal.text = ui.totalDuration
            binding.textCallCount.text = ui.totalCalls
            history = ui.history
            renderHistory()
        }
        viewModel.load(number, fallbackName)
    }

    /**
     * Identified numbers get their initials and the number beneath the name;
     * unknown ones get a "?", the amber "Not identified yet" pill, and the
     * Identify CTA.
     *
     * The avatar's own surface no longer changes between the two states — inside
     * the hero it is a translucent tile either way, and swapping it for a solid
     * brand disc put a second filled shape on a card that already has one.
     */
    private fun bindHero(ui: CallInsightUi) {
        val identified = ui.verified

        binding.textAvatar.text =
            if (identified) CallActionHandler.initials(ui.name, ui.number) else "?"
        binding.textAvatar.setTextColor(
            ContextCompat.getColor(
                this,
                if (identified) R.color.ds_on_hero else R.color.ds_on_hero_muted
            )
        )

        binding.textName.text = ui.name
        binding.textNumber.text =
            getString(R.string.detail_dot_join, ui.number, getString(R.string.detail_line_mobile))
        binding.textNumber.visibility = if (identified) View.VISIBLE else View.GONE
        binding.textVerified.visibility = if (identified) View.GONE else View.VISIBLE
        binding.buttonIdentify.visibility = if (identified) View.GONE else View.VISIBLE
    }

    /**
     * Opens Lookup on this number so the user can identify it.
     *
     * Straight to the screen. This used to bounce off MainShellActivity —
     * REORDER_TO_FRONT with an extra the shell read and turned back into a
     * Lookup — which made sense while Lookup was a tab the shell owned. It is an
     * Activity now, and routing a screen through the shell to reach another
     * screen only leaves the shell holding an extra it has to remember to clear.
     */
    private fun identifyNumber() {
        if (number.isBlank()) return
        startActivity(LookupActivity.newIntent(this, number))
        finish()
    }

    private fun renderHistory() {
        binding.columnHistory.removeAllViews()
        val empty = history.isEmpty()
        binding.textEmptyHistory.visibility = if (empty) View.VISIBLE else View.GONE
        binding.summaryStrip.visibility = if (empty) View.GONE else View.VISIBLE
        binding.historyCard.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) return

        // Rows grouped under Today / Yesterday / date headers.
        val shown = if (expanded) history else history.take(COLLAPSED_COUNT)
        var lastHeader: String? = null
        shown.forEach { e ->
            val header = dateHeader(e.date)
            if (header != lastHeader) {
                addHistoryHeader(header)
                lastHeader = header
            }
            addHistoryRow(e)
        }

        val more = !expanded && history.size > COLLAPSED_COUNT
        binding.buttonViewAll.visibility = if (more) View.VISIBLE else View.GONE
        binding.historyDivider.visibility = if (more) View.VISIBLE else View.GONE
    }

    private fun addHistoryHeader(text: String) {
        val header = layoutInflater
            .inflate(R.layout.item_call_history_header, binding.columnHistory, false) as TextView
        header.text = text
        binding.columnHistory.addView(header)
    }

    private fun addHistoryRow(e: CallRecord) {
        val row = ItemCallHistoryBinding.inflate(layoutInflater, binding.columnHistory, false)
        val missed = e.type == CallType.MISSED || e.type == CallType.SPAM

        row.imageDir.setImageResource(CallActionHandler.typeIconRes(e.type))
        val iconColor =
            ContextCompat.getColor(this, if (missed) R.color.ds_danger else R.color.ds_accent)
        row.imageDir.imageTintList = ColorStateList.valueOf(iconColor)
        // The disc behind the arrow follows it, so a missed call is red end to
        // end rather than a red arrow on a blue ground.
        row.imageDir.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                this,
                if (missed) R.color.ds_danger_tint else R.color.ds_hero_blue_wash
            )
        )
        row.textDuration.setTextColor(
            ContextCompat.getColor(this, if (missed) R.color.ds_danger else R.color.ds_ink_muted)
        )

        row.textWhen.text = timeLabel(e.date)

        val dur = CallActionHandler.durationLabel(e.durationSec)
        row.textDuration.text =
            if (missed || dur.isEmpty()) getString(R.string.detail_not_answered) else dur

        binding.columnHistory.addView(row.root)
    }

    private fun timeLabel(date: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(date))

    /** Group label: Today / Yesterday / "MMM d" (upper-cased by the header style). */
    private fun dateHeader(date: Long): String = when {
        isSameDay(date, 0) -> getString(R.string.detail_today_short)
        isSameDay(date, 1) -> getString(R.string.detail_yesterday_short)
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(date))
    }

    /** True if [date] falls on the day [daysAgo] before today. */
    private fun isSameDay(date: Long, daysAgo: Int): Boolean {
        val ref = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -daysAgo) }
        val cal = Calendar.getInstance().apply { timeInMillis = date }
        return ref.get(Calendar.YEAR) == cal.get(Calendar.YEAR) &&
            ref.get(Calendar.DAY_OF_YEAR) == cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun message() {
        if (number.isBlank()) return
        runCatching { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number"))) }
    }

    /**
     * Opens a WhatsApp chat with this number (digits only, country code expected).
     * Tries WhatsApp, then WhatsApp Business, then the wa.me web redirect.
     */
    private fun whatsapp() {
        val digits = number.filter { it.isDigit() }
        if (digits.isEmpty()) return
        val uri = Uri.parse("https://wa.me/$digits")

        for (pkg in WHATSAPP_PACKAGES) {
            if (launch(Intent(Intent.ACTION_VIEW, uri).setPackage(pkg))) return
        }
        // Neither WhatsApp app available -> browser redirect, else inform the user.
        if (!launch(Intent(Intent.ACTION_VIEW, uri))) {
            Toast.makeText(this, R.string.whatsapp_not_installed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun launch(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }.getOrDefault(false)

    /**
     * Blocks or unblocks the number, then repaints the action.
     *
     * It used to block only: tapping it a second time silently blocked an
     * already-blocked number, and the one way back was to find it in the
     * blocklist. Same shape as the lookup detail screen, down to the rule that
     * only blocking goes through the reward gate — unblocking hands a slot back
     * and must never cost an ad.
     */
    private fun toggleBlock() {
        if (number.isBlank()) return
        val blocklist = BlocklistRepository(this)

        if (blocklist.isNumberBlocked(number)) {
            blocklist.remove(number)
            updateBlockState()
            Toast.makeText(this, R.string.blocklist_removed, Toast.LENGTH_SHORT).show()
            return
        }

        BlockReward.allow(this, number) {
            blocklist.add(number)
            updateBlockState()
            Toast.makeText(this, R.string.blocklist_added, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Reflects the current block state on the action: red slash for "Block",
     * green for "Unblock".
     *
     * The label keeps the same ink as its three neighbours and the tile carries
     * the state — colouring one label of four makes the strip read as three
     * buttons and a warning.
     */
    private fun updateBlockState() {
        val blocked = number.isNotBlank() && BlocklistRepository(this).isNumberBlocked(number)
        val labelRes = if (blocked) R.string.action_unblock else R.string.action_block
        val fg = if (blocked) R.color.ds_success else R.color.ds_danger
        val soft = if (blocked) R.color.ds_success_wash else R.color.ds_danger_tint

        binding.textBlockLabel.setText(labelRes)
        binding.imageBlockIcon.contentDescription = getString(labelRes)
        binding.imageBlockIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, fg))
        binding.imageBlockIcon.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, soft))
    }

    companion object {
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_NAME = "extra_name"
        private const val COLLAPSED_COUNT = 4
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        fun newIntent(context: Context, number: String, name: String?): Intent =
            Intent(context, CallDetailsActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_NAME, name)
    }
}
