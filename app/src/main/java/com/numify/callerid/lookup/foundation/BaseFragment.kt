package com.numify.callerid.lookup.foundation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.ScreenPlacementPlan
import com.numify.callerid.monetize.strategy.recordEvent
import com.numify.callerid.monetize.strategy.recordPermissionOutcome
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.monetize.delivery.NativeBannerPresenter
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.common.followAdContainer
import com.numify.callerid.lookup.repository.SettingsRepository
import com.facebook.shimmer.ShimmerFrameLayout
import java.util.Locale

/**
 * Base class for every Fragment in the app.
 *
 * Uses ViewBinding, safely clears the binding in [onDestroyView] to avoid
 * memory leaks, and exposes [initView] / [initObservers] hooks.
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
     * Which format this fragment's slot renders. The *format* is a layout
     * decision and lives here; Remote Config still owns whether the ad shows at
     * all, through the fragment's own `ScreenAds` entry (see [showScreenAd]).
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
     * Requests [permission] through the given [launcher], but once the user has denied
     * it twice (permanently denied — the system will no longer show its dialog), opens
     * the app's settings page instead so they can enable it manually.
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
            // Permanently denied → the dialog won't appear, so send them to Settings.
            else -> openAppSettings()
        }
    }

    // --- Chained runtime-permission requests ---

    private val permissionChain = ArrayDeque<String>()
    private var onPermissionChainComplete: (() -> Unit)? = null
    private var lastChainPermission: String? = null

    private val chainLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        lastChainPermission?.let { context?.recordPermissionOutcome(it, granted) }
        advancePermissionChain()
    }

    /**
     * Requests [permissions] one after another (skipping any already granted), then
     * runs [onComplete] once the whole sequence is finished — e.g. to kick off the
     * overlay-permission step. A permanently-denied permission diverts to the app's
     * Settings page and pauses the chain; calling this again restarts it.
     */
    protected fun requestPermissionChain(
        permissions: List<String>,
        onComplete: () -> Unit
    ) {
        permissionChain.clear()
        permissionChain.addAll(permissions)
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
                // Permanently denied → divert to Settings and pause here.
                else -> openAppSettings()
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
