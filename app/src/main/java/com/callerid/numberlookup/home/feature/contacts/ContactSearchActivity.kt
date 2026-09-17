package com.callerid.numberlookup.home.feature.contacts

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.ListDividerDecoration
import com.callerid.numberlookup.home.common.openActivity
import com.callerid.numberlookup.home.databinding.ActivityContactSearchBinding
import com.callerid.numberlookup.home.feature.calldetails.CallDetailsActivity
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.repository.ContactRecord
import com.callerid.numberlookup.home.repository.ContactRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Contact search, as its own screen.
 *
 * The Contacts tab used to carry a search field in its header, above a filter
 * rail, a favourites strip, an ad and the directory. That field had to share the
 * page with all of it, and the results had to appear in the same list that the
 * filters were also driving. Here the query owns the screen: the keyboard is up
 * on arrival, the results are the only thing below the bar, and the tab keeps its
 * own state while the user is away. It is the shape Google Contacts and Samsung
 * Contacts both use.
 *
 * Matching is over **name, number and email address** — see [matches]. Email in
 * particular is why this is worth a screen: a contact saved out of a mail app may
 * have no other text worth typing.
 */
class ContactSearchActivity : BaseActivity<ActivityContactSearchBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "ContactSearchActivity"

    override val layoutId = R.layout.activity_contact_search

    private val adapter = ContactListAdapter(::dialNumber, ::openDetail)

    /** The whole address book, read once; every keystroke filters this in memory. */
    private var pool: List<ContactRecord> = emptyList()

    /**
     * Voice search, through the system recogniser.
     *
     * The result is put into the field rather than run straight as a search, so a
     * misheard word can be corrected instead of silently returning nothing —
     * names are exactly what speech recognition is worst at.
     */
    private val voiceSearch = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult
        binding.inputSearch.setText(spoken)
        binding.inputSearch.setSelection(spoken.length)
    }

    /**
     * The recogniser wants RECORD_AUDIO on some OEM builds even though the system
     * UI is the thing doing the listening. Asked here rather than up front: the
     * screen works fully without it, and a microphone prompt on open would be a
     * demand for something most people never tap.
     */
    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchVoiceSearch() else toastNoVoice() }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.listResults.layoutManager = LinearLayoutManager(this)
        binding.listResults.adapter = adapter
        binding.listResults.addItemDecoration(
            ListDividerDecoration(binding.listResults) { adapter.isHeader(it) }
        )

        binding.buttonBack.setOnClickListener { goBack() }
        binding.buttonClearSearch.setOnClickListener { binding.inputSearch.setText("") }
        binding.buttonVoiceSearch.setOnClickListener { startVoiceSearch() }

        binding.inputSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = applyQuery(s?.toString().orEmpty())
        })
        // The IME's search key just closes the keyboard: results are already live
        // per keystroke, so there is nothing left for it to submit.
        binding.inputSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard()
                true
            } else {
                false
            }
        }

        showPrompt()
        openKeyboard()
        loadPool()
    }

    override fun initObservers() = Unit

    /**
     * Reads the address book once, off the main thread.
     *
     * In memory afterwards because this screen's whole job is to keep up with
     * typing: re-querying the provider per keystroke is the mistake the dialer
     * already had to be rescued from.
     */
    private fun loadPool() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        lifecycleScope.launch {
            pool = withContext(Dispatchers.IO) { ContactRepository(this@ContactSearchActivity).getContacts() }
            // The user may well have typed while the read was in flight.
            applyQuery(binding.inputSearch.text?.toString().orEmpty(), force = true)
        }
    }

    private var query: String = ""

    private fun applyQuery(text: String, force: Boolean = false) {
        val trimmed = text.trim()
        if (!force && trimmed == query) return
        query = trimmed
        binding.buttonClearSearch.isVisible = trimmed.isNotEmpty()
        // Clear and voice share a slot — see the layout.
        binding.buttonVoiceSearch.isVisible = trimmed.isEmpty()

        if (trimmed.isEmpty()) {
            adapter.submit(emptyList())
            showPrompt()
            return
        }

        val needle = trimmed.lowercase(Locale.getDefault())
        val digits = trimmed.filter(Char::isDigit)
        val hits = pool.filter { it.matches(needle, digits) }

        adapter.submit(hits.map { ContactRowUi.Item(it) })
        binding.listResults.isVisible = hits.isNotEmpty()
        binding.emptyState.isVisible = hits.isEmpty()
        if (hits.isEmpty()) {
            binding.textEmptyTitle.text = getString(R.string.contacts_search_none_title, trimmed)
            binding.textEmptySub.setText(R.string.contacts_search_none_sub)
        } else {
            binding.listResults.scrollToPosition(0)
        }
    }

    /**
     * Name, number or email.
     *
     * The number is compared on digits alone so that "9876" finds a contact saved
     * as "+91 98765 43210" — a saved number carries spaces, brackets and a
     * country code that nobody types into a search box. A query with no digits in
     * it skips that comparison rather than matching every number containing an
     * empty string.
     */
    private fun ContactRecord.matches(needle: String, digits: String): Boolean =
        name.lowercase(Locale.getDefault()).contains(needle) ||
            email?.lowercase(Locale.getDefault())?.contains(needle) == true ||
            (digits.isNotEmpty() && detail.filter(Char::isDigit).contains(digits))

    /** The resting state: nothing typed yet. */
    private fun showPrompt() {
        binding.listResults.isVisible = false
        binding.emptyState.isVisible = true
        binding.textEmptyTitle.setText(R.string.contacts_search_prompt_title)
        binding.textEmptySub.setText(R.string.contacts_search_prompt_sub)
    }

    private fun startVoiceSearch() {
        val needsMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        if (needsMic) micPermission.launch(Manifest.permission.RECORD_AUDIO) else launchVoiceSearch()
    }

    private fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.contacts_search_voice))
        }
        // No resolveActivity() gate: on Android 11+ it answers null unless the
        // recogniser is declared in <queries>, while startActivity would have
        // worked. Try it and handle the failure — the same call the contacts
        // insert intent makes.
        runCatching { voiceSearch.launch(intent) }.onFailure { toastNoVoice() }
    }

    private fun toastNoVoice() {
        Toast.makeText(this, R.string.contacts_search_no_voice, Toast.LENGTH_SHORT).show()
    }

    private fun openKeyboard() {
        binding.inputSearch.requestFocus()
        binding.inputSearch.post {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.inputSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.inputSearch.windowToken, 0)
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(contact: ContactRecord) {
        openActivity(CallDetailsActivity.newIntent(this, contact.detail, contact.name, R.string.contact_detail_title))
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, ContactSearchActivity::class.java)
    }
}
