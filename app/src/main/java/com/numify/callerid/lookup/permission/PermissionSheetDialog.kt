package com.numify.callerid.lookup.permission

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.numify.callerid.lookup.repository.SettingsRepository
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.numify.callerid.monetize.strategy.logKeyEvent
import com.numify.callerid.monetize.strategy.logPermissionResult
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.openActivity
import com.numify.callerid.monetize.delivery.engagement.OverlayTutorialActivity
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.feature.MainShellActivity
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.feature.overlay.OverlayPermissionUtils
import com.numify.callerid.lookup.common.WindowInsetsHelper

/**
 * Bottom-sheet replacement for MainShellActivity's old sequential first-run
 * permission chain. Lists every permission the app wants (notification,
 * phone state, call log, contacts, overlay) with a live grant status, lets the
 * user grant them individually, and offers a single **Continue** button that
 * requests everything still missing and then closes.
 *
 * Self-contained: it owns its own result launchers, so MainShellActivity only has to
 * `show()` it. Runtime permissions go through the OS dialog; the overlay
 * ("display over other apps") permission opens system Settings via
 * [OverlayPermissionUtils]. `phone_state` and `overlay` are listed unconditionally: they
 * are what the caller-ID card needs (the PHONE_STATE broadcast is only delivered
 * to holders of READ_PHONE_STATE), so they must not follow `HD_VBC_Show` — that
 * flag only gates the post-call Callback screen.
 */
class PermissionSheetDialog : BottomSheetDialogFragment() {

    /** Invoked once when the sheet finishes (Continue or dismiss). */
    var onFinished: (() -> Unit)? = null

    private data class Row(
        val key: String,
        @StringRes val title: Int,
        @StringRes val desc: Int,
        @DrawableRes val icon: Int,
        val isOverlay: Boolean = false,
        val androidPermission: String? = null,
        /** Requested via [PermissionCoordinator] (RC-driven) instead of directly. */
        val engineManaged: Boolean = false,
    )

    private lateinit var rows: List<Row>

    /** True while a Continue-initiated batch request is running. */
    private var continueInProgress = false

    /** True when we opened the overlay screen as the last step of Continue. */
    private var finishAfterOverlay = false

    private val requestPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (perm, granted) ->
            context?.logPermissionResult(perm, granted)
        }
        refreshRows()
        if (continueInProgress) {
            continueInProgress = false
            proceedToOverlayOrFinish()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val granted = context?.let { ctx -> OverlayPermissionUtils.isGranted(ctx) } ?: false
        context?.logKeyEvent(if (granted) "Permission_OVERLAY_Allow" else "Permission_OVERLAY_Deny")
        refreshRows()
        if (finishAfterOverlay) {
            finishAfterOverlay = false
            finishFlow()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = inflater.inflate(R.layout.sheet_permission_sheet, container, false)
        rows = buildRows()

        val rowsContainer = root.findViewById<LinearLayout>(R.id.holderRows)
        rows.forEach { row ->
            val rowView = inflater.inflate(R.layout.cell_permission_row, rowsContainer, false)
            rowView.tag = row.key
            rowView.findViewById<ImageView>(R.id.picIcon).setImageResource(row.icon)
            rowView.findViewById<TextView>(R.id.lblTitle).setText(row.title)
            rowView.findViewById<TextView>(R.id.lblDesc).setText(row.desc)
            rowView.findViewById<TextView>(R.id.padAllow).setOnClickListener { requestSingle(row) }
            // Granted (and permanently-denied engine rows) are hidden entirely.
            rowView.visibility = if (shouldHideRow(row)) View.GONE else View.VISIBLE
            rowsContainer.addView(rowView)
        }

        root.findViewById<TextView>(R.id.padContinue).setOnClickListener { onContinueClicked() }
        root.findViewById<TextView>(R.id.padNotNow).setOnClickListener {
            context?.logKeyEvent("PermissionSheet_NotNow")
            finishFlow()
        }

        context?.logKeyEvent("PermissionSheet_Show")
        return root
    }

    override fun onStart() {
        super.onStart()
        // Let our rounded @drawable/shape_overlay_sheet show instead of the default
        // opaque bottom-sheet background.
        (dialog as? BottomSheetDialog)
            ?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundColor(Color.TRANSPARENT)

        // Keep the sheet compact: cap the scrollable row area to ~half the screen
        // so many rows scroll instead of stretching the sheet full-height.
        view?.findViewById<View>(R.id.rowsScrollVw)?.let { scroll ->
            scroll.post {
                // The sheet may have been dismissed before this runnable fires
                // (e.g. a quick Not-now/swipe) — bail if we're already detached,
                // and read metrics off the view itself, not requireContext().
                if (!isAdded) return@post
                val maxH = (scroll.resources.displayMetrics.heightPixels * 0.5f).toInt()
                if (scroll.height > maxH) {
                    scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
                    scroll.requestLayout()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    // --- Row model ---

    private fun buildRows(): List<Row> {
        val list = mutableListOf<Row>()

        // Notification + phone state are handled by the PermissionCoordinator (see
        // requestSingle / onContinueClicked), so the sheet only primes them here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Row(
                "notification", R.string.perm_notification_title, R.string.perm_notification_desc,
                R.drawable.glyph_notifications, androidPermission = Manifest.permission.POST_NOTIFICATIONS,
                engineManaged = true,
            )
        }
        // Read-phone-state powers caller ID / post-call detection. Always asked:
        // without it the OS never delivers ACTION_PHONE_STATE_CHANGED, so the
        // caller-ID card cannot appear at all.
        list += Row(
            "phone_state", R.string.perm_phone_title, R.string.perm_phone_desc,
            R.drawable.glyph_phone_solid, androidPermission = Manifest.permission.READ_PHONE_STATE,
            engineManaged = true,
        )
        list += Row(
            "call_log", R.string.permsheet_calllog_title, R.string.perm_calllog_desc,
            R.drawable.glyph_history, androidPermission = Manifest.permission.READ_CALL_LOG,
        )
        list += Row(
            "contacts", R.string.permsheet_contacts_title, R.string.perm_contacts_desc,
            R.drawable.glyph_group, androidPermission = Manifest.permission.READ_CONTACTS,
        )
        // Always asked, for the same reason as phone_state: the caller-ID card is
        // drawn as a TYPE_APPLICATION_OVERLAY window, so without this permission
        // the card is skipped regardless of the Callback screen's geo gate.
        list += Row(
            "overlay", R.string.perm_overlay_title, R.string.perm_overlay_desc,
            R.drawable.glyph_apps, isOverlay = true,
        )
        return list
    }

    private fun isGranted(row: Row): Boolean {
        val ctx = context ?: return false
        return if (row.isOverlay) {
            OverlayPermissionUtils.isGranted(ctx)
        } else {
            val perm = row.androidPermission ?: return true
            ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    // --- UI refresh ---

    private fun refreshRows() {
        val root = view ?: return
        val rowsContainer = root.findViewById<LinearLayout>(R.id.holderRows)
        rows.forEach { row ->
            val rowView = rowsContainer.findViewWithTag<View>(row.key) ?: return@forEach
            // Once granted (or permanently denied for engine rows) the row disappears.
            rowView.visibility = if (shouldHideRow(row)) View.GONE else View.VISIBLE
        }
        // When every row is resolved via the individual Allow buttons, there's
        // nothing left to show — close the sheet automatically instead of leaving
        // it open empty (the Continue flow handles its own dismissal, so skip it
        // while a Continue batch is running).
        if (!continueInProgress && !finishAfterOverlay && rows.all { shouldHideRow(it) }) {
            finishFlow()
        }
    }

    /**
     * A row is hidden when its permission is already granted, and — for the
     * engine-managed rows (notification / phone state) only — also once the user
     * has denied it twice (Android's permanent-denial state, reached after the
     * 2nd decline from *any* screen). Call log / contacts / overlay always stay
     * visible until granted.
     */
    private fun shouldHideRow(row: Row): Boolean {
        if (isGranted(row)) return true
        if (!row.engineManaged) return false
        val act = activity ?: return false
        val perm = row.androidPermission ?: return false
        return isPermanentlyDenied(act, row.key, perm)
    }

    // --- Requests ---

    private fun requestSingle(row: Row) {
        if (isGranted(row)) return
        when {
            // Notification / phone state → delegate to the engine (RC-driven).
            row.engineManaged -> PermissionCoordinator.check(requireActivity()) {
                if (isAdded) refreshRows()
            }
            row.isOverlay -> launchOverlay(finishAfter = false)
            else -> row.androidPermission?.let { requestPerms.launch(arrayOf(it)) }
        }
    }

    private fun onContinueClicked() {
        // Notification + phone state are managed by the PermissionCoordinator; once it
        // finishes, request the sheet's own permissions (call log / contacts) and
        // then the overlay step.
        PermissionCoordinator.check(requireActivity()) {
            if (isAdded) requestSheetOwnedThenOverlay()
        }
    }

    /** Requests the sheet-owned runtime permissions (not engine-managed), then overlay. */
    private fun requestSheetOwnedThenOverlay() {
        val missing = rows
            .filter { !it.isOverlay && !it.engineManaged && !isGranted(it) }
            .mapNotNull { it.androidPermission }
        if (missing.isNotEmpty()) {
            continueInProgress = true
            requestPerms.launch(missing.toTypedArray())
        } else {
            proceedToOverlayOrFinish()
        }
    }

    /** After runtime permissions are handled, do the overlay step (if needed) then finish. */
    private fun proceedToOverlayOrFinish() {
        val overlayRow = rows.firstOrNull { it.isOverlay }
        if (overlayRow != null && !isGranted(overlayRow)) {
            launchOverlay(finishAfter = true)
        } else {
            finishFlow()
        }
    }

    private fun launchOverlay(finishAfter: Boolean) {
        // Reuse MainShellActivity's overlay flow — it opens the system page with the
        // NO_HISTORY/EXCLUDE_FROM_RECENTS intent, polls for the grant on its own
        // main-thread Handler, auto-returns the app, and drops the Settings page.
        // The sheet's own launcher had none of that (no auto-back, page lingered).
        val host = activity as? MainShellActivity
        if (host != null) {
            host.startOverlayPermissionFlow()
            // Continue's last step closes the sheet; the single-row Allow keeps it
            // open so onResume can hide the overlay row once granted.
            if (finishAfter) finishFlow()
            return
        }

        // Fallback (not hosted by MainShellActivity): own launcher, no auto-back, but
        // still the flagged intent so the Settings page doesn't linger.
        finishAfterOverlay = finishAfter
        AppOpenAdManager.skipNextAppOpenAd = true
        runCatching {
            overlayLauncher.launch(OverlayPermissionUtils.buildOverlayIntent(requireContext().packageName))
            requireContext().openActivity<OverlayTutorialActivity>(isAdd = false)
        }.onFailure {
            WindowInsetsHelper.error("PermissionSheet", "Failed to open overlay settings", it)
            if (finishAfter) finishFlow()
        }
    }

    private fun finishFlow() {
        onFinished?.invoke()
        onFinished = null
        runCatching { dismissAllowingStateLoss() }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // If dismissed by swipe/outside tap (not via finishFlow), still notify once.
        onFinished?.invoke()
        onFinished = null
    }

    companion object {
        const val TAG = "permission_sheet"

        /**
         * True when at least one of the sheet's permissions still needs granting
         * — use it to decide whether to trigger the sheet at all (avoids showing
         * an empty sheet once everything is granted). Mirrors [buildRows]' gating.
         */
        @JvmStatic
        fun hasPending(activity: FragmentActivity): Boolean {
            fun granted(perm: String) =
                ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED
            // Notification / phone state are "resolved" once granted OR denied
            // twice (permanent denial) — the sheet stops offering them, so they no
            // longer count as pending (avoids showing an all-hidden sheet).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                !isPermanentlyDenied(activity, "notification", Manifest.permission.POST_NOTIFICATIONS)
            ) return true
            if (!granted(Manifest.permission.READ_PHONE_STATE) &&
                !isPermanentlyDenied(activity, "phone_state", Manifest.permission.READ_PHONE_STATE)
            ) return true
            if (!granted(Manifest.permission.READ_CALL_LOG)) return true
            if (!granted(Manifest.permission.READ_CONTACTS)) return true
            if (!OverlayPermissionUtils.isGranted(activity)) return true
            return false
        }

        /**
         * True once the user has denied [perm] to the point Android no longer
         * shows its system dialog — i.e. it was requested at least once (from any
         * screen: the engine records it in [PermissionPreferences], the Home quick
         * actions in [SettingsRepository]) and `shouldShowRequestPermissionRationale` is
         * now false while still ungranted. On Android 11+ this is reached after
         * the 2nd decline.
         */
        @JvmStatic
        fun isPermanentlyDenied(activity: FragmentActivity, key: String, perm: String): Boolean {
            if (ContextCompat.checkSelfPermission(activity, perm) == PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val askedAnywhere = PermissionPreferences(activity).wasAsked(key) ||
                SettingsRepository(activity).hasRequestedPermission(perm)
            if (!askedAnywhere) return false
            return !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }

        /**
         * Decides whether the sheet should pop up **automatically** on app launch.
         * Combines [hasPending] with the shared [OnboardingStepConfig] gate, driven
         * by the `screen.permission_sheet` entry of the Onboarding Dynamic Flow
         * (`isEnable` + `session` = every | once | `<N>` launches | `<N>d` days).
         * The ledger is stamped by [OnboardingStepConfig.markShown] in [show]. This
         * gate is for the **auto-launch only** — a manual "Manage" tap calls [show]
         * directly and always opens (subject to [hasPending]).
         */
        @JvmStatic
        fun shouldAutoShow(activity: FragmentActivity): Boolean {
            if (!hasPending(activity)) return false
            return OnboardingStepConfig.isEligible(activity, OnboardingStepConfig.PERMISSION_SHEET_KEY)
        }

        /**
         * Shows the permission sheet on demand. Call from any click listener:
         *
         * ```
         * someButton.setOnClickListener { PermissionSheetDialog.show(this) }
         * ```
         *
         * Safe to call repeatedly — it no-ops if the sheet is already showing or
         * the host isn't in a valid state to commit a transaction. [onFinished]
         * runs once when the sheet closes (Continue, Not now, or dismiss).
         */
        @JvmStatic
        @JvmOverloads
        fun show(activity: FragmentActivity, onFinished: (() -> Unit)? = null) {
            val fm = activity.supportFragmentManager
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            // Stamp the shared ledger so the frequency gate (once / every / <N>d / <N>
            // launches) can measure from here.
            OnboardingStepConfig.markShown(activity, OnboardingStepConfig.PERMISSION_SHEET_KEY)
            PermissionSheetDialog().apply { this.onFinished = onFinished }
                .show(fm, TAG)
        }
    }
}
