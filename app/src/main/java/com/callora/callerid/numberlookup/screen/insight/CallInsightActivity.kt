package com.callora.callerid.numberlookup.screen.insight

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
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
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.core.CoreActivity
import com.callora.callerid.numberlookup.store.BlockRegistry
import com.callora.callerid.numberlookup.store.CallRecord
import com.callora.callerid.numberlookup.store.CallDirection
import com.callora.callerid.numberlookup.databinding.ScreenCallDetailBinding
import com.callora.callerid.numberlookup.databinding.CellCallHistoryBinding
import com.callora.callerid.numberlookup.screen.HomeShellActivity
import com.callora.callerid.numberlookup.screen.shared.CallPresenter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Per-number call detail screen opened from a recents row. */
class CallInsightActivity : CoreActivity<ScreenCallDetailBinding>() {

    override val layoutId: Int = R.layout.screen_call_detail

    private val viewModel: CallInsightViewModel by viewModels()

    private val number by lazy { intent.getStringExtra(EXTRA_NUMBER).orEmpty() }
    private val fallbackName by lazy { intent.getStringExtra(EXTRA_NAME) }

    private var history: List<CallRecord> = emptyList()
    private var expanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        binding.padCall.setOnClickListener { placeCall(number) }
        binding.padMessage.setOnClickListener { message() }
        binding.padWhatsapp.setOnClickListener { whatsapp() }
        binding.padBlock.setOnClickListener { block() }
        binding.padIdentify.setOnClickListener { identifyNumber() }
        binding.padViewAll.setOnClickListener { expanded = true; renderHistory() }
    }

    override fun initObservers() {
        viewModel.ui.observe(this) { ui ->
            bindHero(ui)
            binding.lblTotal.text = ui.totalDuration
            binding.lblCallCount.text = ui.totalCalls
            history = ui.history
            renderHistory()
        }
        viewModel.load(number, fallbackName)
    }

    /** Identified numbers get a green ring + name; unknown ones get a "?" + Identify CTA. */
    private fun bindHero(ui: CallInsightUi) {
        val identified = ui.verified
        if (identified) {
            binding.avatarRingVw.setBackgroundResource(R.drawable.shape_detail_avatar_ring)
            binding.lblAvatar.setBackgroundResource(R.drawable.shape_avatar)
            binding.lblAvatar.setTextColor(ContextCompat.getColor(this, R.color.white))
            binding.lblAvatar.text = CallPresenter.initials(ui.name, ui.number)
            binding.lblName.typeface = Typeface.DEFAULT_BOLD
        } else {
            binding.avatarRingVw.setBackgroundResource(R.drawable.shape_circle_surface)
            binding.lblAvatar.setBackgroundResource(0)
            binding.lblAvatar.setTextColor(ContextCompat.getColor(this, R.color.on_surface_variant))
            binding.lblAvatar.text = "?"
            binding.lblName.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        binding.lblName.text = ui.name
        binding.lblNumber.text =
            getString(R.string.detail_dot_join, ui.number, getString(R.string.detail_line_mobile))
        binding.lblNumber.visibility = if (identified) View.VISIBLE else View.GONE
        binding.lblVerified.visibility = if (identified) View.GONE else View.VISIBLE
        binding.padIdentify.visibility = if (identified) View.GONE else View.VISIBLE
    }

    /** Sends the number to the Lookup tab so the user can identify it. */
    private fun identifyNumber() {
        if (number.isBlank()) return
        startActivity(
            Intent(this, HomeShellActivity::class.java)
                .putExtra(HomeShellActivity.EXTRA_LOOKUP_NUMBER, number)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    private fun renderHistory() {
        binding.rowHistory.removeAllViews()
        val empty = history.isEmpty()
        binding.lblEmptyHistory.visibility = if (empty) View.VISIBLE else View.GONE
        binding.summaryStripVw.visibility = if (empty) View.GONE else View.VISIBLE
        binding.historyCardVw.visibility = if (empty) View.GONE else View.VISIBLE
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
        binding.padViewAll.visibility = if (more) View.VISIBLE else View.GONE
        binding.historyDividerVw.visibility = if (more) View.VISIBLE else View.GONE
    }

    private fun addHistoryHeader(text: String) {
        val header = layoutInflater
            .inflate(R.layout.cell_call_history_header, binding.rowHistory, false) as TextView
        header.text = text
        binding.rowHistory.addView(header)
    }

    private fun addHistoryRow(e: CallRecord) {
        val row = CellCallHistoryBinding.inflate(layoutInflater, binding.rowHistory, false)
        val missed = e.type == CallDirection.MISSED || e.type == CallDirection.SPAM

        row.picDir.setImageResource(CallPresenter.typeIconRes(e.type))
        val iconColor = ContextCompat.getColor(this, if (missed) R.color.danger else R.color.primary)
        row.picDir.imageTintList = ColorStateList.valueOf(iconColor)

        row.lblWhen.text = timeLabel(e.date)

        val dur = CallPresenter.durationLabel(e.durationSec)
        row.lblDuration.text =
            if (missed || dur.isEmpty()) getString(R.string.detail_not_answered) else dur

        binding.rowHistory.addView(row.root)
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

    private fun block() {
        if (number.isBlank()) return
        BlockRegistry(this).add(number)
        Toast.makeText(this, R.string.blocklist_added, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_NUMBER = "extra_number"
        private const val EXTRA_NAME = "extra_name"
        private const val COLLAPSED_COUNT = 4
        private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

        fun newIntent(context: Context, number: String, name: String?): Intent =
            Intent(context, CallInsightActivity::class.java)
                .putExtra(EXTRA_NUMBER, number)
                .putExtra(EXTRA_NAME, name)
    }
}
