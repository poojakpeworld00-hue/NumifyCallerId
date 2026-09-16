package com.contacts.callerid.number.lookup.feature.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.provider.ContactsContract
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
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.monetize.delivery.NativeBannerPresenter
import com.contacts.callerid.number.lookup.foundation.BaseFragment
import com.contacts.callerid.number.lookup.common.openActivity
import com.contacts.callerid.number.lookup.common.ListDividerDecoration
import com.contacts.callerid.number.lookup.databinding.FragmentContactsBinding
import com.contacts.callerid.number.lookup.feature.MainShellActivity
import com.contacts.callerid.number.lookup.feature.calldetails.CallDetailsActivity
import com.contacts.callerid.number.lookup.repository.SettingsRepository
import com.contacts.callerid.number.lookup.common.followAdContainer

class ContactListFragment : BaseFragment<FragmentContactsBinding>() {

    /** Contacts: native banner in the existing in-list slot. */
    override val screenAdFormat = ScreenAdFormat.NATIVE_BANNER

    private val viewModel: ContactListViewModel by viewModels()
    private val adapter = ContactListAdapter(::dialNumber, ::openDetail)
    private val favoritesAdapter = SpeedDialStripAdapter(::openDetail)
    private lateinit var layoutManager: LinearLayoutManager

    private var sectionLetters: List<String> = emptyList()
    private var letterToPosition: Map<String, Int> = emptyMap()

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentContactsBinding.inflate(inflater, container, false)

    override fun initView() {
        // Hero bleeds under the status bar; pad its content down by the inset.
        val baseTop = binding.heroHeader.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.heroHeader) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = baseTop + top)
            insets
        }

        layoutManager = LinearLayoutManager(requireContext())
        binding.listContacts.layoutManager = layoutManager
        binding.listContacts.adapter = adapter
        // Hairlines between rows inside the card, skipping the letter headings.
        binding.listContacts.addItemDecoration(
            ListDividerDecoration(binding.listContacts) { adapter.isHeader(it) }
        )

        // Native banner at the bottom of the contacts screen.
        // Ad slot + dividers are handled by BaseFragment.showScreenAd() — see
        // CallLogFragment for why this no longer calls NativeBannerPresenter directly.

        // Search is a screen now, not a field on this page.
        //
        // startActivity directly, NOT openActivity: that helper routes every
        // launch through the transition interstitial, and the ad only calls back
        // to actually start the Activity once it has decided not to show. When
        // that decision stalls — no fill, no network, a half-initialised SDK —
        // the tap silently does nothing. A search box that sometimes opens is
        // worse than one that never did, and this is a control, not a screen
        // transition worth monetising.
        binding.buttonContactsSearch.setOnClickListener {
            startActivity(ContactSearchActivity.newIntent(requireContext()))
        }
        binding.buttonContactsAdd.setOnClickListener { openAddContact() }

        binding.listFavorites.adapter = favoritesAdapter
        binding.favSection.setOnClickListener { toggleFavorites() }
        applyFavoritesExpansion()

        binding.tabAll.setOnClickListener { viewModel.applyFilter(ContactFilter.ALL) }
        binding.tabFavorites.setOnClickListener { viewModel.applyFilter(ContactFilter.FAVORITES) }
        binding.tabRecents.setOnClickListener { viewModel.applyFilter(ContactFilter.RECENTS) }
        binding.tabGroups.setOnClickListener { viewModel.applyFilter(ContactFilter.GROUPS) }

        setupAlphaIndexTouch()
        binding.buttonGrant.setOnClickListener {
            // Same order as Recents: notifications, then this screen's own
            // permission, then the overlay where Remote Config permits it.
            requestPermissionChain(
                notificationFirst(Manifest.permission.READ_CONTACTS)
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
            highlightTab(binding.tabAll, active == ContactFilter.ALL)
            highlightTab(binding.tabFavorites, active == ContactFilter.FAVORITES)
            highlightTab(binding.tabRecents, active == ContactFilter.RECENTS)
            highlightTab(binding.tabGroups, active == ContactFilter.GROUPS)
        }
        viewModel.rows.observe(viewLifecycleOwner) { rows ->
            adapter.submit(rows)

            val count = rows.count { it is ContactRowUi.Item }
            binding.textContactsCount.text =
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
            binding.alphaIndex.visibility = if (hasData) View.VISIBLE else View.GONE
            binding.textEmpty.visibility =
                if (!hasData && hasContactsPermission()) View.VISIBLE else View.GONE
        }
    }

    private fun buildAlphaIndex() {
        binding.alphaIndex.removeAllViews()
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
            binding.alphaIndex.addView(tv)
        }
    }

    private fun setupAlphaIndexTouch() {
        binding.alphaIndex.setOnTouchListener { v, event ->
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
                    binding.letterBubble.visibility = View.GONE
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
        binding.letterBubble.text = letter
        binding.letterBubble.visibility = View.VISIBLE
    }

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionGranted() {
        binding.permState.visibility = View.GONE
        binding.listContacts.visibility = View.VISIBLE
        viewModel.load()
        // First-time upload of device contacts to the server.
        com.contacts.callerid.number.lookup.resolver.ContactUploader
            .uploadOnceIfNeeded(requireContext())
    }

    private fun showPermissionState() {
        binding.permState.visibility = View.VISIBLE
        binding.listContacts.visibility = View.GONE
        binding.textEmpty.visibility = View.GONE
        binding.alphaIndex.visibility = View.GONE
    }

    /**
     * The strip only earns its space on the unfiltered list. On the Favorites tab
     * it would simply repeat the list beneath it, and on Recents or Groups it
     * would show people who are not in that list at all.
     *
     * The header and the rail are separate decisions: the header is shown
     * whenever there is anything starred to head, and the rail additionally
     * depends on whether the user has this section folded up.
     */
    private fun showFavoritesStrip() {
        val onAll = viewModel.filter.value == ContactFilter.ALL
        val hasFavorites = onAll && favoritesAdapter.itemCount > 0
        binding.favSection.visibility = if (hasFavorites) View.VISIBLE else View.GONE
        applyFavoritesExpansion()
    }

    /**
     * Folds the favourites rail away, remembering the choice.
     *
     * Persisted rather than held in the fragment: a preference that resets every
     * time the tab is rebuilt is not a preference, and this section costs a band
     * of screen on every launch for someone who does not use it.
     */
    private fun toggleFavorites() {
        val prefs = SettingsRepository(requireContext())
        prefs.favoritesExpanded = !prefs.favoritesExpanded
        applyFavoritesExpansion(animate = true)
    }

    /**
     * Only the caret moves.
     *
     * The header used to carry `selectableItemBackground`, so a tap washed grey
     * across the full width of the screen — which announces "this row was
     * pressed" far more loudly than it shows what actually changed. The caret
     * turning is the feedback, and it turns rather than cuts so the eye can
     * follow it to the rail appearing or going.
     *
     * [animate] is false on the paths that merely restore state — first layout,
     * a filter change, the list reloading — where a spinning caret would be
     * reporting a change the user did not make.
     */
    private fun applyFavoritesExpansion(animate: Boolean = false) {
        if (view == null) return
        val expanded = SettingsRepository(requireContext()).favoritesExpanded
        val headerShown = binding.favSection.visibility == View.VISIBLE
        binding.listFavorites.visibility = if (headerShown && expanded) View.VISIBLE else View.GONE

        val target = if (expanded) 0f else -90f
        if (animate) {
            binding.iconFavExpand.animate().rotation(target).setDuration(180L).start()
        } else {
            binding.iconFavExpand.animate().cancel()
            binding.iconFavExpand.rotation = target
        }
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

    private fun openDetail(contact: com.contacts.callerid.number.lookup.repository.ContactRecord) {
        requireActivity().openActivity(CallDetailsActivity.newIntent(requireContext(), contact.detail, contact.name))
    }
}
