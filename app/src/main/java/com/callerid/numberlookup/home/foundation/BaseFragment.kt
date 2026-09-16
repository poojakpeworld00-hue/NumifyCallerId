package com.callerid.numberlookup.home.foundation

import android.Manifest
import android.os.Build
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent
import com.callerid.numberlookup.home.monetize.strategy.recordPermissionOutcome
import com.callerid.numberlookup.home.monetize.delivery.NativeAdPresenter
import com.callerid.numberlookup.home.monetize.delivery.NativeBannerPresenter
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.followAdContainer
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.facebook.shimmer.ShimmerFrameLayout
import java.util.Locale

/**
 * Base class behind every Fragment in the app.
 *
 * It uses ViewBinding, clears the binding safely in [onDestroyView] so nothing
 * leaks, and offers [initView] and [initObservers] hooks.
 *
 * Usage:
 * ```
 * class CallLogFragment : BaseFragment<FragmentRecentsBinding>() {
 *     override fun inflateBinding(inflater, container) =
 *         FragmentRecentsBinding.inflate(inflater, container, false)
 * }
 * ```
 */
abstract class BaseFragment<VB : ViewBinding> : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB get() = _binding!!

    /** Inflate the concrete ViewBinding for this fragment. */
    protected abstract fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?): VB

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflateBinding(inflater, container)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        showScreenAd()
    }

    /** Ad format for a fragment's own slot. See [screenAdFormat]. */
    protected enum class ScreenAdFormat { NONE, NATIVE_BANNER, MID_NATIVE, BOTTOM_BANNER }

    /**
     * The format this fragment's slot renders in. Format is a layout decision and
     * so belongs here; Remote Config still decides whether the ad appears at all,
     * through the fragment's own `ScreenAds` entry (see [showScreenAd]).
     */
    protected open val screenAdFormat: ScreenAdFormat = ScreenAdFormat.NONE

    /**
     * Loads this fragment's on-load ad slot automatically - the Fragment
     * counterpart to [BaseActivity.showBottomBanner].
     *
     * Ad resolution was once Activity-only: `ScreenPlacementPlan.showAd` was
     * called from [BaseActivity] alone, using the Activity's `simpleName`, so all
     * five Home tabs resolved to `MainShellActivity` and a `ScreenAds` key naming
     * a fragment could never match anything. Keying on the **fragment's** simple
     * name gives every tab its own entry, and `show:false` there silences just
     * that one tab.
     *
     * The slot is `@id/adNativeFrame`, optionally alongside `@id/adShimmer`; a
     * fragment without such a view, or with [screenAdFormat] set to `NONE`, does
     * nothing.
     */
    protected open fun showScreenAd() {
        val act = activity ?: return
        if (screenAdFormat == ScreenAdFormat.NONE) return
        val root = _binding?.root ?: return
        val container = root.findViewById<FrameLayout>(R.id.adNativeFrame) ?: return
        val shimmer = root.findViewById<ShimmerFrameLayout>(R.id.adShimmer)

        // Remote Config gate — IsAdsON plus this fragment's own `show` flag.
        val screen = this::class.java.simpleName
        val allowed = AdPreferenceStore.getInstance(act).getBoolean("IsAdsON") &&
            ScreenPlacementPlan.resolve(act, screen).show
        if (!allowed) {
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            // And the fences with it. This used to return here, which skipped the
            // followAdContainer wiring at the bottom of the method entirely — so
            // on an ads-off build the dividers kept the visible state the layout
            // gives them and drew two grey rules across the page with nothing
            // between them. The empty-slot case is the one that needs hiding
            // most, so it cannot be the one that gets skipped.
            hideAdDividers(root)
            return
        }

        container.visibility = View.VISIBLE
        when (screenAdFormat) {
            ScreenAdFormat.NATIVE_BANNER -> NativeBannerPresenter().displayNativeBanner(act, container, shimmer)
            ScreenAdFormat.MID_NATIVE -> NativeAdPresenter().displayMediumNative(act, container, shimmer)
            ScreenAdFormat.BOTTOM_BANNER -> ScreenPlacementPlan.showAd(screen, act, container, shimmer)
            ScreenAdFormat.NONE -> Unit
        }

        // Dividers exist only to fence off an advert — drop them when the slot
        // ends up empty (ads off, show:false, or load failure).
        root.findViewById<View>(R.id.adNativeDivider)?.followAdContainer(container)
        root.findViewById<View>(R.id.adNativeDivider1)?.followAdContainer(container)
    }

    /** Both ad fences, hidden outright — for the paths where no ad is even attempted. */
    private fun hideAdDividers(root: View) {
        root.findViewById<View>(R.id.adNativeDivider)?.visibility = View.GONE
        root.findViewById<View>(R.id.adNativeDivider1)?.visibility = View.GONE
    }

    /** Set up views, listeners, adapters. */
    protected open fun initView() {}

    /** Subscribe to ViewModel LiveData / Flows. */
    protected open fun initObservers() {}

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // --- Screen-view analytics ---
    // MainShellActivity hosts tabs via add/show/hide, so log when a fragment is
    // actually visible: on first resume and whenever it is un-hidden. Hidden
    // fragments still receive onResume on app-resume, hence the isHidden guard.

    override fun onResume() {
        super.onResume()
        if (!isHidden) logScreenView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) logScreenView()
    }

    private fun logScreenView() {
        context?.recordEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
    }

    // --- Shared runtime-permission handling ---

    /**
     * Requests [permission] through the supplied [launcher]. Once the user has
     * refused it twice — the permanently-denied state where Android stops showing
     * its dialog — it explains that in place rather than going anywhere.
     */
    protected fun requestPermissionManaged(
        permission: String,
        launcher: ActivityResultLauncher<String>
    ) {
        val prefs = SettingsRepository(requireContext())
        when {
            // First-ever request → show the system dialog.
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                launcher.launch(permission)
            }
            // Denied before but the system will still show the dialog → ask again.
            shouldShowRequestPermissionRationale(permission) -> launcher.launch(permission)
            // Permanently denied → say so, and let the user decide.
            else -> showPermissionBlockedDialog()
        }
    }

    /**
     * Explains a permanently-denied permission without leaving the app.
     *
     * This used to call [openAppSettings] outright, so a third tap on Grant threw
     * the user into Android's App info page with no warning — a different app, a
     * different back stack, and nothing saying why they were there.
     *
     * The settings page is still offered, because it is genuinely the only place
     * the permission can be turned back on: once Android stops showing its dialog
     * the app cannot ask again. Offering it is the difference — the user chooses
     * to go, rather than arriving.
     */
    protected fun showPermissionBlockedDialog() {
        val ctx = context ?: return
        runCatching {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.perm_blocked_title)
                .setMessage(R.string.perm_blocked_message)
                .setNegativeButton(R.string.perm_blocked_dismiss, null)
                .setPositiveButton(R.string.perm_blocked_open) { _, _ -> openAppSettings() }
                .show()
        }
    }

    // --- Chained runtime-permission requests ---

    /**
     * [permissions] with notifications asked for first.
     *
     * Notifications are what the app is for — a caller ID that cannot raise an
     * alert has nothing to say — so it goes at the head of every second-chance
     * chain rather than being left to the splash. Someone who tapped "Not now"
     * there meets it again the next time they ask for anything, ahead of the
     * permission that screen actually needs.
     *
     * Only on Android 13+; below Tiramisu POST_NOTIFICATIONS does not exist and
     * requesting it would burn a step on a dialog that never appears.
     * [requestPermissionChain] skips anything already granted, so on a device
     * where notifications are on this adds nothing.
     */
    protected fun notificationFirst(vararg permissions: String): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        addAll(permissions)
    }

    private val permissionChain = ArrayDeque<String>()
    private var onPermissionChainComplete: (() -> Unit)? = null
    private var lastChainPermission: String? = null

    /**
     * Set when the chain steps over a permission Android will no longer prompt
     * for, so the end of the run can say so once instead of once per permission.
     */
    private var blockedInChain = false

    private val chainLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastChainPermission?.let { context?.recordPermissionOutcome(it, granted) }
        advancePermissionChain()
    }

    /**
     * Asks for [permissions] one after another, skipping any already granted, then
     * runs [onComplete] once the whole sequence has finished - to start the
     * overlay-permission step, for instance. A permanently-denied permission sends
     * the user to the app's Settings page and pauses the chain; calling this again
     * restarts it.
     */
    protected fun requestPermissionChain(
        permissions: List<String>,
        onComplete: () -> Unit
    ) {
        permissionChain.clear()
        permissionChain.addAll(permissions)
        blockedInChain = false
        onPermissionChainComplete = onComplete
        advancePermissionChain()
    }

    private fun advancePermissionChain() {
        val ctx = context ?: return
        val prefs = SettingsRepository(ctx)
        while (permissionChain.isNotEmpty()) {
            val permission = permissionChain.removeFirst()
            if (ContextCompat.checkSelfPermission(ctx, permission)
                == PackageManager.PERMISSION_GRANTED
            ) continue

            when {
                // First-ever request → show the system dialog (callback continues the chain).
                !prefs.hasRequestedPermission(permission) -> {
                    prefs.markPermissionRequested(permission)
                    lastChainPermission = permission
                    chainLauncher.launch(permission)
                }
                // Denied before but the dialog still appears → ask again.
                shouldShowRequestPermissionRationale(permission) -> {
                    lastChainPermission = permission
                    chainLauncher.launch(permission)
                }
                // Permanently denied → skip it and keep going. This used to divert
                // to Settings and stop the chain dead, so one refused permission
                // meant the remaining ones were never asked for at all. The sheet
                // already shows which are missing; the chain's job is to ask for
                // the ones Android will still let it ask for.
                else -> {
                    blockedInChain = true
                    continue
                }
            }
            return
        }
        // Sequence exhausted — fire the completion hook.
        val done = onPermissionChainComplete
        onPermissionChainComplete = null
        done?.invoke()
    }

    /** Opens this app's system settings (App info) screen. */
    protected fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        }
    }

    // --- Shared direct-calling (CALL_PHONE) ---

    private var pendingCallNumber: String? = null

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        context?.recordPermissionOutcome(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) startCall(number) else openDialer(number)
    }

    /** Places the call directly (CALL_PHONE), requesting the permission if needed. */
    protected fun placeCall(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCall(number)
        } else {
            pendingCallNumber = number
            requestPermissionManaged(Manifest.permission.CALL_PHONE, callPermissionLauncher)
        }
    }

    private fun startCall(number: String) {
        val placed = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))); true
        }.getOrDefault(false)
        if (!placed) openDialer(number)
    }

    private fun openDialer(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }
}
