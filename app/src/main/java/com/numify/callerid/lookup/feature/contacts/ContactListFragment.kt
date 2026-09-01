package com.numify.callerid.lookup.feature.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.numify.callerid.lookup.R
import com.numify.callerid.monetize.delivery.NativeBannerPresenter
import com.numify.callerid.lookup.foundation.BaseFragment
import com.numify.callerid.lookup.common.openActivity
import com.numify.callerid.lookup.databinding.PaneContactsBinding
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.calldetails.CallDetailsActivity
import com.numify.callerid.lookup.common.followAdContainer

class ContactListFragment : BaseFragment<PaneContactsBinding>() {

    /** Contacts: native banner in the existing in-list slot. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: ContactListViewModel by viewModels()
    private val adapter = ContactListAdapter(::dialNumber, ::openDetail)
    private val favoritesAdapter = SpeedDialStripAdapter(::openDetail)
    private lateinit var layoutManager: LinearLayoutManager

    private var sectionLetters: List<String> = emptyList()
    private var letterToPosition: Map<String, Int> = emptyMap()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        PaneContactsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeaderVw.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeaderVw) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }

        layoutManager = LinearLayoutManager(requireContext())
        binding.rollContacts.layoutManager = layoutManager
        binding.rollContacts.adapter = adapter

        // Native banner at the bottom of the contacts screen.
        // Ad slot + dividers are handled by BaseFragment.showScreenAd() — see
        // CallLogFragment for why this no longer calls NativeBannerPresenter directly.

        binding.inpSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                viewModel.setQuery(text)
                binding.padClearSearch.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
        })
        binding.padClearSearch.setOnClickListener { binding.inpSearch.setText("") }
        binding.padContactsAdd.setOnClickListener { openAddContact() }

        binding.rollFavorites.adapter = favoritesAdapter

        binding.segAll.setOnClickListener { viewModel.setFilter(ContactFilter.ALL) }
        binding.segFavorites.setOnClickListener { viewModel.setFilter(ContactFilter.FAVORITES) }
        binding.segRecents.setOnClickListener { viewModel.setFilter(ContactFilter.RECENTS) }
        binding.segGroups.setOnClickListener { viewModel.setFilter(ContactFilter.GROUPS) }

        setupAlphaIndexTouch()
        binding.padGrant.setOnClickListener {
            requestPermissionChain(
                listOf(Manifest.permission.READ_CONTACTS)
            ) {
                if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
                (activity as? MainShellActivity)?.startOverlayPermissionFlow()
            }
        }

        if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from Settings so a freshly granted permission
        // loads the contact list without needing to leave the screen.
        if (view != null) {
            if (hasContactsPermission()) onPermissionGranted() else showPermissionState()
        }
    }

    override fun initObservers() {
        viewModel.favorites.observe(viewLifecycleOwner) { favorites ->
            favoritesAdapter.submit(favorites)
            showFavoritesStrip()
        }
        viewModel.filter.observe(viewLifecycleOwner) { active ->
            showFavoritesStrip()
            highlightTab(binding.segAll, active == ContactFilter.ALL)
            highlightTab(binding.segFavorites, active == ContactFilter.FAVORITES)
            highlightTab(binding.segRecents, active == ContactFilter.RECENTS)
            highlightTab(binding.segGroups, active == ContactFilter.GROUPS)
        }
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)

            val count = rows.count { it is ContactRowUi.Item }
            binding.lblContactsCount.text =
                if (count > 0) getString(R.string.contacts_count_fmt, count)
                else getString(R.string.nav_contacts)

            sectionLetters = rows.filterIsInstance<ContactRowUi.Header>().map { it.letter }
            letterToPosition = buildMap {
                rows.forEachIndexed { index, row ->
                    if (row is ContactRowUi.Header && !containsKey(row.letter)) put(row.letter, index)
                }
            }
            buildAlphaIndex()

            val hasData = rows.isNotEmpty()
            binding.alphaIndexVw.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.lblEmpty.visibility =
                if (!hasData && hasContactsPermission()) View.VISIBLE else View.GONE
        }
    }

    private fun buildAlphaIndex() {
        binding.alphaIndexVw.removeAllViews()
        sectionLetters.forEach { letter ->
            val tv = TextView(requireContext()).apply {
                text = letter
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), R.color.primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            binding.alphaIndexVw.addView(tv)
        }
    }

    private fun setupAlphaIndexTouch() {
        binding.alphaIndexVw.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val count = sectionLetters.size
                    if (count > 0 && v.height > 0) {
                        val idx = ((event.y / v.height) * count).toInt().coerceIn(0, count - 1)
                        val letter = sectionLetters[idx]
                        scrollToLetter(letter)
                        showBubble(letter)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    binding.letterBubbleVw.visibility = View.GONE
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun scrollToLetter(letter: String) {
        val position = letterToPosition[letter] ?: return
        layoutManager.scrollToPositionWithOffset(position, 0)
    }

    private fun showBubble(letter: String) {
        binding.letterBubbleVw.text = letter
        binding.letterBubbleVw.visibility = View.VISIBLE
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionGranted() {
        binding.permStateVw.visibility = View.GONE
        binding.rollContacts.visibility = View.VISIBLE
        viewModel.load()
        // First-time upload of device contacts to the server.
        com.numify.callerid.lookup.resolver.ContactUploader
            .uploadOnceIfNeeded(requireContext())
    }

    private fun showPermissionState() {
        binding.permStateVw.visibility = View.VISIBLE
        binding.rollContacts.visibility = View.GONE
        binding.lblEmpty.visibility = View.GONE
        binding.alphaIndexVw.visibility = View.GONE
    }

    /**
     * The strip only earns its space on the unfiltered list. On the Favorites tab
     * it would repeat the list underneath it, and on Recents/Groups it would show
     * people who are not in the list at all.
     */
    private fun showFavoritesStrip() {
        val onAll = viewModel.filter.value == ContactFilter.ALL
        val visible = onAll && favoritesAdapter.itemCount > 0
        val state = if (visible) View.VISIBLE else View.GONE
        binding.favSectionVw.visibility = state
        binding.rollFavorites.visibility = state
    }

    private fun highlightTab(tab: TextView, active: Boolean) {
        tab.isActivated = active
        tab.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (active) R.color.white else R.color.on_surface_variant
            )
        )
        // Selected: tint the leading icon white. Unselected: clear the tint so the
        // icon keeps its own colour.
        TextViewCompat.setCompoundDrawableTintList(
            tab,
            if (active) {
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                null
            }
        )
    }

    private fun openAddContact() {
        // Don't gate on resolveActivity(): on Android 11+ (enforced on 16) it
        // returns null unless the contacts app is declared in <queries>, even
        // though startActivity() would launch fine. Just try and handle failure.
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Toast.makeText(requireContext(), R.string.app_unavailable, Toast.LENGTH_SHORT).show()
            }
    }

    private fun dialNumber(number: String) = placeCall(number)

    private fun openDetail(contact: com.numify.callerid.lookup.repository.ContactRecord) {
        requireActivity().openActivity(CallDetailsActivity.newIntent(requireContext(), contact.detail, contact.name))
    }
}
