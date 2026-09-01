package com.numify.callerid.lookup.feature.finder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.monetize.delivery.RewardedAdPresenter
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.databinding.ActivityLookupHistoryBinding

/**
 * Standalone list of recent number lookups. Tapping a row returns its number to the
 * caller (Lookup tab) to re-run the search; the phone icon dials directly.
 */
class SearchHistoryActivity : BaseActivity<ActivityLookupHistoryBinding>() {

    override val layoutId: Int = R.layout.activity_lookup_history

    private val viewModel: SearchHistoryViewModel by viewModels()
    private val adapter = SearchHistoryAdapter(
        onClick = ::returnNumber,
        onCall = { entry -> placeCall(entry.rawNumber) },
        onRevealName = ::revealName
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lookupHistoryRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }
        binding.buttonClearAll.setOnClickListener { viewModel.clear() }
        binding.listHistory.layoutManager = LinearLayoutManager(this)
        binding.listHistory.adapter = adapter
        // Warm up the rewarded ad that gates revealing caller names.
        RewardedAdPresenter.preload(this)
    }

    override fun initObservers() {
        viewModel.history.observe(this) { items ->
            adapter.submit(items)
            val empty = items.isEmpty()
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.buttonClearAll.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload in case the list changed while away (e.g. a new lookup was run).
        viewModel.load()
    }

    private fun returnNumber(entry: SearchHistoryEntry) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NUMBER, entry.rawNumber))
        finish()
    }

    /** Gate the name reveal behind a rewarded ad, then un-mask that row. */
    private fun revealName(entry: SearchHistoryEntry) {
        val name = entry.name ?: return
        NameRevealReward.reveal(this, name, entry.number) { adapter.revealName(entry.rawNumber) }
    }

    companion object {
        const val EXTRA_NUMBER = "extra_number"

        fun newIntent(context: Context): Intent =
            Intent(context, SearchHistoryActivity::class.java)
    }
}
