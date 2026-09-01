package com.numify.callerid.numberlookup.core

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
import com.numify.callerid.adkit.policy.AdsPrefStore
import com.numify.callerid.adkit.policy.ScreenAdPlan
import com.numify.callerid.adkit.policy.logKeyEvent
import com.numify.callerid.adkit.policy.logPermissionResult
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.adkit.runtime.NativeBannerAd
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.kit.followAdContainer
import com.numify.callerid.numberlookup.store.SettingsVault
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
 * class CallHistoryFragment : CoreFragment<PaneRecentsBinding>() {
 *     override fun inflateBinding(inflater, container) =
 *         PaneRecentsBinding.inflate(inflater, container, false)
 * }
 * ```
 */
abstract class CoreFragment<VB : ViewBinding> : Fragment() {

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
     * Auto-loads this fragment's on-load ad slot — the Fragment counterpart of
     * [CoreActivity.showBottomBanner].
     *
     * Ad resolution used to be Activity-only (`ScreenAdPlan.showAd` was called
     * solely from [CoreActivity] with the Activity's `simpleName`), so all five
     * Home tabs resolved as `HomeShellActivity` and a `ScreenAds` key naming a
     * fragment could never match. Keying on the **fragment's** simple name gives
     * each tab its own entry, and `show:false` there switches just that tab off.
     *
     * The slot is `@id/adNativeFrameVw` (+ optional `@id/adShimmerVw`); a
     * fragment with no such view, or [screenAdFormat] `NONE`, is a no-op.
     */
    protected open fun showScreenAd() {
        val act = activity ?: return
        if (screenAdFormat == ScreenAdFormat.NONE) return
        val root = _binding?.root ?: return
        val container = root.findViewById<FrameLayout>(R.id.adNativeFrameVw) ?: return
        val shimmer = root.findViewById<ShimmerFrameLayout>(R.id.adShimmerVw)

        // Remote Config gate — IsAdsON plus this fragment's own `show` flag.
        val screen = this::class.java.simpleName
        val allowed = AdsPrefStore.getInstance(act).getBoolean("IsAdsON") &&
            ScreenAdPlan.resolve(act, screen).show
        if (!allowed) {
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            return
        }

        container.visibility = View.VISIBLE
        when (screenAdFormat) {
            ScreenAdFormat.NATIVE_BANNER -> NativeBannerAd().renderNativeBanner(act, container, shimmer)
            ScreenAdFormat.MID_NATIVE -> NativeAdLoader().renderMidNative(act, container, shimmer)
            ScreenAdFormat.BOTTOM_BANNER -> ScreenAdPlan.showAd(screen, act, container, shimmer)
            ScreenAdFormat.NONE -> Unit
        }

        // Dividers exist only to fence off an advert — drop them when the slot
        // ends up empty (ads off, show:false, or load failure).
        root.findViewById<View>(R.id.adNativeDividerVw)?.followAdContainer(container)
        root.findViewById<View>(R.id.adNativeDivider1Vw)?.followAdContainer(container)
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
    // HomeShellActivity hosts tabs via add/show/hide, so log when a fragment is
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
        context?.logKeyEvent("screen_${this::class.java.simpleName.lowercase(Locale.ROOT)}")
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
        val prefs = SettingsVault(requireContext())
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
        lastChainPermission?.let { context?.logPermissionResult(it, granted) }
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
        val prefs = SettingsVault(ctx)
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
        context?.logPermissionResult(Manifest.permission.CALL_PHONE, granted)
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
