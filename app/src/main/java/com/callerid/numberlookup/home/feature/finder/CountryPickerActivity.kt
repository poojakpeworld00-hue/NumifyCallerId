package com.callerid.numberlookup.home.feature.finder

import android.app.Activity
import android.content.Intent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.common.ListDividerDecoration
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.databinding.ActivityCountryPickerBinding

/** Searchable country list. Returns the chosen country's ISO/dial/name. */
class CountryPickerActivity : BaseActivity<ActivityCountryPickerBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "CountryPickerActivity"

    override val layoutId: Int = R.layout.activity_country_picker

    private lateinit var adapter: CountryAdapter

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.countryRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        adapter = CountryAdapter { country ->
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_ISO, country.iso2)
                    .putExtra(EXTRA_DIAL, country.dial)
                    .putExtra(EXTRA_NAME, country.name)
            )
            finish()
        }
        binding.listCountries.layoutManager = LinearLayoutManager(this)
        binding.listCountries.adapter = adapter
        binding.listCountries.addItemDecoration(ListDividerDecoration(binding.listCountries))
        adapter.submit(CountryCatalog.all)

        binding.buttonBack.setOnClickListener { goBack() }
        binding.inputSearch.addTextChangedListener { text -> filter(text?.toString().orEmpty()) }
    }

    private fun filter(query: String) {
        val q = query.trim()
        val list = if (q.isEmpty()) {
            CountryCatalog.all
        } else {
            CountryCatalog.all.filter {
                it.name.contains(q, ignoreCase = true) ||
                    it.dial.contains(q) ||
                    it.iso2.contains(q, ignoreCase = true)
            }
        }
        adapter.submit(list)
    }

    companion object {
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_DIAL = "extra_dial"
        const val EXTRA_NAME = "extra_name"
    }
}
