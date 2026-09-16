package com.callerid.numberlookup.home.feature.tools

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.common.ListDividerDecoration
import com.callerid.numberlookup.home.common.openActivity
import com.callerid.numberlookup.home.feature.finder.LookupActivity
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityAiScanBinding
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.delivery.RewardedAdPresenter
import com.callerid.numberlookup.home.feature.blocklist.BlockReward
import com.callerid.numberlookup.home.repository.BlocklistRepository
import com.callerid.numberlookup.home.repository.CallLogRepository
import com.callerid.numberlookup.home.repository.ContactRepository
import com.callerid.numberlookup.home.repository.assistant.CallLogScan
import com.callerid.numberlookup.home.repository.assistant.CallerInsight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two assistant tools, which are the same screen twice: read the whole call
 * log, rank what comes back, offer one action per row.
 *
 * Everything is decided on the device from the call log, contacts and blocklist.
 * No network, no model, nothing leaves the phone — which is what lets these sit
 * in the Tools tab without changing the app's data-safety disclosure.
 */
class AiScanActivity : BaseActivity<ActivityAiScanBinding>() {

    override val layoutId: Int = R.layout.activity_ai_scan

    private val mode by lazy { intent.getStringExtra(EXTRA_MODE) ?: MODE_SPAM }
    private val callLog by lazy { CallLogRepository(this) }
    private val contacts by lazy { ContactRepository(this) }
    private val blocklist by lazy { BlocklistRepository(this) }

    private val adapter by lazy {
        AiScanAdapter(
            actionLabel = if (mode == MODE_SPAM) R.string.scan_action_block else R.string.action_identify,
            destructive = mode == MODE_SPAM,
            onAction = ::runRowAction
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.scanRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonScanBack.setOnClickListener { performBack() }
        binding.textScanTitle.setText(
            if (mode == MODE_SPAM) R.string.tools_spam_scan else R.string.tools_unsaved
        )
        binding.listScan.layoutManager = LinearLayoutManager(this)
        binding.listScan.adapter = adapter
        // Hairlines between rows inside the card, as on every other list.
        binding.listScan.addItemDecoration(ListDividerDecoration(binding.listScan))
        scan()
    }

    /**
     * Re-scanned on every entry rather than cached: the user acts on these rows,
     * and coming back to a list still offering to block something they blocked
     * thirty seconds ago reads as broken.
     */
    override fun onResume() {
        super.onResume()
        if (binding.listScan.adapter != null && !binding.progressScan.isVisible) scan()
        // Warm the ad BlockReward shows once the free block slots are gone.
        RewardedAdPresenter.preload(this)
    }

    private fun scan() {
        binding.progressScan.isVisible = true
        binding.listScan.isVisible = false
        binding.columnScanEmpty.isVisible = false

        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { collect() }
            binding.progressScan.isVisible = false
            if (rows.isEmpty()) {
                binding.textScanEmpty.setText(
                    if (mode == MODE_SPAM) R.string.scan_empty_spam else R.string.scan_empty_unsaved
                )
                binding.columnScanEmpty.isVisible = true
                binding.textScanSubtitle.text = ""
            } else {
                adapter.submit(rows)
                binding.listScan.isVisible = true
                binding.textScanSubtitle.text = resources.getString(
                    if (mode == MODE_SPAM) R.string.scan_found_spam else R.string.scan_found_unsaved,
                    rows.size
                )
            }
        }
    }

    /**
     * Contacts and the blocklist are read once into sets, not queried per number.
     * A 1,500-call log has hundreds of distinct numbers, and a content-provider
     * lookup for each would take seconds on a mid-range phone.
     */
    private fun collect(): List<AiScanRow> {
        val history = runCatching { callLog.getCalls(limit = 2000) }.getOrDefault(emptyList())
        if (history.isEmpty()) return emptyList()

        val savedTails = runCatching {
            contacts.getContacts().mapNotNull { tail(it.detail) }.toHashSet()
        }.getOrDefault(hashSetOf())
        val blockedTails = runCatching {
            blocklist.getAll().mapNotNull { tail(it) }.toHashSet()
        }.getOrDefault(hashSetOf())

        val isKnown: (String) -> Boolean = { n -> tail(n)?.let { it in savedTails } ?: false }
        val isBlocked: (String) -> Boolean = { n -> tail(n)?.let { it in blockedTails } ?: false }

        return if (mode == MODE_SPAM) {
            CallLogScan.spamCandidates(history, isBlocked, isKnown).map {
                AiScanRow(
                    number = it.number,
                    reason = when (val i = it.insight) {
                        is CallerInsight.Nuisance ->
                            getString(R.string.scan_reason_never_answered, it.calls)
                        is CallerInsight.MissedStreak ->
                            getString(R.string.scan_reason_streak, i.count, it.calls)
                        else -> getString(R.string.scan_reason_never_answered, it.calls)
                    }
                )
            }
        } else {
            CallLogScan.unsavedCallers(history, isKnown, isBlocked).map {
                AiScanRow(
                    number = it.number,
                    reason = getString(R.string.scan_reason_unsaved, it.calls, it.answered)
                )
            }
        }
    }

    private fun runRowAction(row: AiScanRow) {
        if (mode == MODE_SPAM) {
            BlockReward.allow(this, row.number) {
                blocklist.add(row.number)
                adapter.remove(row)
                Toast.makeText(
                    this, getString(R.string.ai_blocked_toast, row.number), Toast.LENGTH_SHORT
                ).show()
                if (adapter.itemCount == 0) scan()
            }
        } else {
            // Identify, not Save. This handed the number to the system contact
            // editor, which asks the user to name someone they have just been
            // told they do not know — the question they actually have is "who is
            // this?", and that is the lookup. Saving is still one tap further
            // on: the result screen offers it once there is a name to save.
            //
            // The row is left in place. Looking a number up does not make it a
            // saved contact, so removing it here would be a lie the next scan
            // would undo.
            openActivity(LookupActivity.newIntent(this, row.number))
        }
    }

    /** Last 9 digits — the same tolerant match the rest of the app uses. */
    private fun tail(number: String?): String? =
        number?.filter(Char::isDigit)?.takeLast(9)?.takeIf { it.length >= 7 }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val MODE_SPAM = "spam"
        const val MODE_UNSAVED = "unsaved"

        fun newIntent(context: Context, mode: String): Intent =
            Intent(context, AiScanActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}

/** One row: the number and the one-line reason it is on this list. */
data class AiScanRow(val number: String, val reason: String)
