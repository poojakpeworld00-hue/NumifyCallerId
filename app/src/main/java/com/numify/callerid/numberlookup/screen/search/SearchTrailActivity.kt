package com.numify.callerid.numberlookup.screen.search

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
import com.numify.callerid.adkit.runtime.RewardedAdLoader
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.databinding.ScreenLookupHistoryBinding

/**
 * Standalone list of recent number lookups. Tapping a row returns its number to the
 * caller (Lookup tab) to re-run the search; the phone icon dials directly.
 */
class SearchTrailActivity : CoreActivity<ScreenLookupHistoryBinding>() {

    override val layoutId: Int = R.layout.screen_lookup_history

    private val viewModel: SearchTrailViewModel by viewModels()
    private val adapter = SearchTrailAdapter(
        onClick = ::returnNumber,
        onCall = { entry -> placeCall(entry.rawNumber) },
        onRevealName = ::revealName
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lookupHistoryRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }
        binding.padClearAll.setOnClickListener { viewModel.clear() }
        binding.rollHistory.layoutManager = LinearLayoutManager(this)
        binding.rollHistory.adapter = adapter
        // Warm up the rewarded ad that gates revealing caller names.
        RewardedAdLoader.preload(this)
    }

    override fun initObservers() {
        viewModel.history.observe(this) { items ->
            adapter.submit(items)
            val empty = items.isEmpty()
            binding.emptyStateVw.visibility = if (empty) View.VISIBLE else View.GONE
            binding.padClearAll.visibility = if (empty) View.GONE else View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload in case the list changed while away (e.g. a new lookup was run).
        viewModel.load()
    }

    private fun returnNumber(entry: TrailEntry) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_NUMBER, entry.rawNumber))
        finish()
    }

    /** Gate the name reveal behind a rewarded ad, then un-mask that row. */
    private fun revealName(entry: TrailEntry) {
        val name = entry.name ?: return
        RewardNameUnlock.reveal(this, name, entry.number) { adapter.revealName(entry.rawNumber) }
    }

    companion object {
        const val EXTRA_NUMBER = "extra_number"

        fun newIntent(context: Context): Intent =
            Intent(context, SearchTrailActivity::class.java)
    }
}
