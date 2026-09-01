package com.numify.callerid.lookup.feature

import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.numify.callerid.monetize.strategy.AdPreferenceStore
import com.numify.callerid.monetize.strategy.logPermissionResult
import com.numify.callerid.monetize.delivery.AppOpenAdManager
import com.numify.callerid.monetize.delivery.UpdateFlowCallback
import com.numify.callerid.monetize.delivery.AppUpdateCoordinator
import com.numify.callerid.monetize.delivery.fullpage.TransitionInterstitialAd
import com.numify.callerid.monetize.delivery.openActivity
import com.numify.callerid.monetize.delivery.engagement.OverlayTutorialActivity
import com.numify.callerid.lookup.BuildConfig
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.SettingsRepository
import com.numify.callerid.lookup.permission.PermissionSheetDialog
import com.numify.callerid.lookup.permission.lockscreen.LockScreenConfig
import com.numify.callerid.lookup.permission.lockscreen.LockScreenPermission
import com.numify.callerid.lookup.permission.lockscreen.LockScreenPrimingDialog
import com.numify.callerid.lookup.permission.lockscreen.LockScreenReturnWatcher
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import com.numify.callerid.lookup.feature.exitflow.ExitConfirmDialog
import com.numify.callerid.lookup.feature.feedback.AppRatingPrompt
import com.numify.callerid.lookup.databinding.ActivityMainShellBinding
import com.numify.callerid.lookup.databinding.ItemNavBinding
import com.numify.callerid.lookup.resolver.ContactUploader
import com.numify.callerid.lookup.feature.contacts.ContactListFragment
import com.numify.callerid.lookup.feature.finder.NumberFinderFragment
import com.numify.callerid.lookup.feature.calllog.CallLogFragment
import com.numify.callerid.lookup.feature.blocklist.BlockedNumbersFragment
import com.numify.callerid.lookup.feature.tools.ToolboxFragment
import com.numify.callerid.lookup.feature.overlay.OverlayPermissionUtils

/**
 * Host Activity with a custom LinearLayout bottom bar (not BottomNavigationView).
 * Manages five fragments using show/hide to preserve their state.
 */
class MainShellActivity : BaseActivity<ActivityMainShellBinding>() {

    override val layoutId: Int = R.layout.activity_main_shell

    /**
     * A destination. [nav] is null for the raised centre action (Lookup), which is
     * hosted by its own FAB outside the bar rather than by a `cell_nav` include.
     */
    private data class Tab(
        val nav: ItemNavBinding?,
        val fragment: Fragment,
        @param:DrawableRes val selectedIcon: Int,
        @param:DrawableRes val unselectedIcon: Int,
        @param:StringRes val label: Int
    )

    private lateinit var tabs: List<Tab>
    private var currentIndex = -1
    private var lastBackMs = 0L
    private var exitToast: Toast? = null

    /** Status-bar height captured from window insets; applied per-tab. */
    private var statusBarTop = 0

    /** Visited-tab history for back navigation (most recent last). */
    private val backStack = ArrayDeque<Int>()

    /** Posts the delayed FSI priming dialog (see [scheduleFsiDialog]). */
    private val fsiHandler = Handler(Looper.getMainLooper())

    /** Brings MainShellActivity back when the FSI toggle flips on (dialog grant round-trip). */
    private val fsiReturnWatcher by lazy { LockScreenReturnWatcher(this) }

    /**
     * True once the first-run permission sheet has been dismissed ("Not now" or
     * swipe). Home uses it (via [shouldShowPermissionHint]) to surface a "Manage"
     * hint only *after* the user has closed the sheet at least once.
     */
    var permissionSheetDismissed = false
        private set

    // In-activity overlay-grant poll — the RELIABLE auto-return for the "Manage"
    // overlay flow. The system "display over other apps" page is opened IN-TASK
    // (launched for-result), so the process keeps a foreground task and this
    // main-thread Handler keeps ticking while MainShellActivity is merely stopped. The
    // instant the toggle flips we pull MainShellActivity back with an in-task
    // REORDER_TO_FRONT — no background Service and no background-activity-start,
    // both unreliable on Android 12+/16 (which is what the old watcher Service
    // relied on, and why it was removed).
    private val overlayGrantPollHandler = Handler(Looper.getMainLooper())
    private var overlayGrantPolling = false

    /**
     * True only while we are sitting behind the overlay-Settings page WE launched.
     * The poll reorders Home to the front on grant, which is correct while the user
     * is on that page — but if they walked away to another app first, the same
     * reorder yanks the app in front of whatever they are doing. Gate on this.
     */
    private var awaitingOverlaySettings = false

    /** Wall-clock stop for the poll, so a walked-away user isn't tracked forever. */
    private var overlayPollDeadline = 0L
    private val overlayGrantPoll = object : Runnable {
        override fun run() {
            if (isDestroyed) { overlayGrantPolling = false; return }
            if (OverlayPermissionUtils.isGranted(this@MainShellActivity)) {
                overlayGrantPolling = false
                onOverlayGranted()
            } else if (SystemClock.elapsedRealtime() > overlayPollDeadline) {
                // User never came back to the page. Stop watching rather than
                // waiting to pounce whenever the grant eventually happens.
                stopOverlayGrantPoll()
            } else {
                overlayGrantPollHandler.postDelayed(this, OVERLAY_GRANT_POLL_MS)
            }
        }
    }

    /** Re-checks the banner when the user returns from the overlay Settings page. */
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        stopOverlayGrantPoll()
        updateOverlayBanner()
    }

    /** Launches the FSI Settings page in-task for the priming dialog (no lingering task). */
    private val fsiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Back from the FSI Settings page (auto-return or manual back).
        stopFsiGrantPoll()
        if (LockScreenPermission.isGranted(this)) LockScreenPrimingDialog.dismissIfShowing()
        // The FSI "Enable" round-trip has returned → now surface the permission sheet.
        if (awaitFsiReturnForSheet) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
    }

    /** True after the FSI dialog's "Enable" sends us to Settings; drives the deferred sheet. */
    private var awaitFsiReturnForSheet = false

    /** One in-app review attempt per Activity instance. */
    private var rateUsAttempted = false
    private val rateUsHandler = Handler(Looper.getMainLooper())

    // In-activity grant poll — the RELIABLE auto-return for the dialog's Enable path.
    //
    // The FSI Settings page is opened IN-TASK, so this app keeps a foreground task
    // the whole time it's shown. A main-thread Handler keeps ticking while MainShellActivity
    // is merely stopped (the process stays alive); the instant the toggle flips we pull
    // MainShellActivity back with an in-task REORDER_TO_FRONT (no background-activity-start,
    // so no BAL privilege needed). This replaces relying on LockScreenWatchService — a
    // background Service can't be started on the way to Settings on Android 12+/16.
    private val fsiGrantPollHandler = Handler(Looper.getMainLooper())
    private var fsiGrantPolling = false
    private val fsiGrantPoll = object : Runnable {
        override fun run() {
            if (isDestroyed) { fsiGrantPolling = false; return }
            if (LockScreenPermission.isGranted(this@MainShellActivity)) {
                fsiGrantPolling = false
                onFsiGranted()
            } else {
                fsiGrantPollHandler.postDelayed(this, FSI_GRANT_POLL_MS)
            }
        }
    }

    /** Begin polling for the FSI grant (idempotent). Called when we open FSI settings. */
    private fun startFsiGrantPoll() {
        if (fsiGrantPolling) return
        fsiGrantPolling = true
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
        fsiGrantPollHandler.postDelayed(fsiGrantPoll, FSI_GRANT_POLL_MS)
    }

    private fun stopFsiGrantPoll() {
        fsiGrantPolling = false
        fsiGrantPollHandler.removeCallbacks(fsiGrantPoll)
    }

    /**
     * Grant detected while the user sat on the FSI Settings page → dismiss the
     * priming dialog and reorder the EXISTING MainShellActivity to the front of the same
     * task, so the (NO_HISTORY) Settings page drops away and onResume/the launcher
     * react to the grant. The permission-sheet follow-up runs from there.
     */
    private fun onFsiGranted() {
        LockScreenPrimingDialog.dismissIfShowing()
        runCatching {
            startActivity(
                Intent(this, MainShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    /** Opens the FSI "Manage" page for the priming dialog (called from LockScreenPrimingDialog). */
    fun openFsiSettings() {
        LockScreenPermission.openSettings(this, fsiSettingsLauncher)
        // Reliable grant detection from the Activity itself (the background service
        // can't start on the way to Settings on Android 12+/16).
        startFsiGrantPoll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Must register the update result-launcher before the activity is STARTED.
        AppUpdateCoordinator.registerLauncher(this)
        maybeCheckForUpdate()
    }

    /**
     * Triggers the Play in-app update flow when Remote Config enables it.
     *  - `In_App_Update_Show`       → master switch for offering an update.
     *  - `In_App_Update_Force_Show` → true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     */
    private fun maybeCheckForUpdate() {
        val pref = AdPreferenceStore.getInstance(this)
        if (!pref.getBoolean("In_App_Update_Show")) return

        AppUpdateCoordinator.init(
            activity = this,
            isForceUpdate = pref.getBoolean("In_App_Update_Force_Show"),
            callback = object : UpdateFlowCallback {
                override fun onUpdateSuccess() {}
                override fun onUpdateCanceled() {}
                override fun onUpdateFailed() {}
            }
        )
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarTop = bars.top
            // No top padding on the root — Home's hero draws under the status bar.
            // Each tab gets its own top inset applied in [applyTopInsetForTab].
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            applyTopInsetForTab(currentIndex)
            insets
        }

        tabs = listOf(
            Tab(
                binding.navRecentsVw, CallLogFragment(),
                R.drawable.navtab_recent_selected, R.drawable.navtab_recent_unselected, R.string.nav_recents
            ),
            Tab(
                binding.navContactsVw, ContactListFragment(),
                R.drawable.navtab_contact_selected, R.drawable.navtab_contact_unselected, R.string.nav_contacts
            ),
            Tab(
                binding.navBlocklistVw, BlockedNumbersFragment(),
                R.drawable.ic_block, R.drawable.ic_block, R.string.nav_blocklist
            ),
            Tab(
                binding.navToolsVw, ToolboxFragment(),
                R.drawable.ic_qa_tools, R.drawable.ic_qa_tools, R.string.nav_tools
            ),
            Tab(
                null, NumberFinderFragment(),
                R.drawable.navtab_lookup_selected, R.drawable.navtab_lookup_unselected, R.string.nav_lookup
            )
        )

        tabs.forEachIndexed { index, tab ->
            val nav = tab.nav ?: return@forEachIndexed
            nav.navLabelVw.setText(tab.label)
            nav.root.setOnClickListener {
                animateIcon(nav.navIconVw)
                select(index)
            }
        }

        binding.fabLookupVw.setOnClickListener {
            animateIcon(binding.fabLookupIconVw)
            showLookup()
        }

        setupSwipeNavigation()
        select(0, animate = false)

        binding.padEnableOverlay.setOnClickListener { startOverlayPermissionFlow() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        // One-time contact upload (no-op if already done or contacts not permitted yet).
        ContactUploader.uploadOnceIfNeeded(this)

        // Arm the FSI auto-return so a grant on the system page pulls us back.
        fsiReturnWatcher.register()

        // First-run priming order: the FSI dialog comes FIRST; the permission sheet
        // follows once the FSI dialog is resolved (Not now → immediately; Enable →
        // after the system-settings round-trip returns). When FSI isn't eligible,
        // the sheet auto-shows straight away (subject to its RC frequency gate).
        val fsiCfg = LockScreenConfig.load(this)
        if (LockScreenPermission.shouldShowDialog(this, fsiCfg)) {
            scheduleFsiDialog(fsiCfg)
        } else {
            maybeAutoShowPermissionSheet()
        }

        // "Identify this number" from Call Details lands us straight on Lookup.
        handleLookupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLookupIntent(intent)
    }

    private fun handleLookupIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_BLOCKLIST, false) == true) {
            intent.removeExtra(EXTRA_OPEN_BLOCKLIST)
            showBlocklist()
            return
        }
        val number = intent?.getStringExtra(EXTRA_LOOKUP_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_LOOKUP_NUMBER)
        showLookup(number)
    }

    // --- Permission priming (bottom sheet) ---

    /**
     * Shows the permission priming bottom sheet ([PermissionSheetDialog]) on
     * demand — hook this to any button/menu click:
     *
     * ```
     * someButton.setOnClickListener { showPermissionSheet() }
     * ```
     *
     * The sheet lists every permission still needed (notification, phone state
     * when HD_VBC_Show is on, call log, contacts, overlay) and lets the user
     * grant them; already-granted ones are hidden.
     */
    fun showPermissionSheet() {
        PermissionSheetDialog.show(this) {
            updateOverlayBanner()
            onPermissionSheetDismissed()
        }
    }

    /**
     * Left/right swipe on the pane container moves along the bottom bar.
     *
     * Only the four bar tabs take part. Lookup is the raised centre FAB — an
     * action rather than a position in the strip — so it is skipped, and a swipe
     * while Lookup is open does nothing rather than teleporting somewhere
     * arbitrary. No wrap-around either: swiping past either end is a no-op, the
     * same as a tab strip.
     */
    private fun setupSwipeNavigation() {
        binding.fragContainer.onSwipe = { direction ->
            val barTabs = tabs.indices.filter { tabs[it].nav != null }
            val position = barTabs.indexOf(currentIndex)
            if (position >= 0) {
                val target = position + direction
                if (target in barTabs.indices) select(barTabs[target])
            }
        }
    }

    /** Auto-shows the permission sheet when pending perms + the RC frequency gate allow. */
    private fun maybeAutoShowPermissionSheet() {
        if (PermissionSheetDialog.shouldAutoShow(this)) showPermissionSheet()
        else maybeShowRateUs()
    }

    /**
     * The in-app review prompt, once Home's prompt queue is clear.
     *
     * Deliberately not fired on a plain timer after Home appears: the permission
     * sheet and the FSI priming dialog both auto-open here, and Play's review
     * sheet launched behind either of them is a wasted quota attempt — the API
     * reports success whether or not anything was actually displayed. So this
     * runs only from the two points where nothing else is queued: the sheet
     * closing, and the sheet deciding not to open at all.
     *
     * One attempt per Activity instance; [AppRatingPrompt] owns every other gate.
     */
    private fun maybeShowRateUs() {
        // Logged, not silent: these two skips are indistinguishable from "the
        // trigger never ran" otherwise, which is exactly the ambiguity that makes
        // this flow hard to test. AppRatingPrompt logs the gate decisions themselves.
        if (rateUsAttempted || isFinishing || isDestroyed) {
            if (BuildConfig.DEBUG) {
                Log.d(RATE_US_TAG, "trigger skipped — attempted=$rateUsAttempted finishing=$isFinishing")
            }
            return
        }
        if (LockScreenPrimingDialog.isShowing()) {
            if (BuildConfig.DEBUG) Log.d(RATE_US_TAG, "trigger deferred — FSI dialog on screen")
            return
        }
        if (BuildConfig.DEBUG) Log.d(RATE_US_TAG, "queue clear → evaluating gate")
        if (!AppRatingPrompt.shouldPrompt(this)) return
        rateUsAttempted = true
        rateUsHandler.postDelayed({
            if (!isFinishing && !isDestroyed) AppRatingPrompt.launch(this)
        }, RATE_US_SETTLE_MS)
    }

    /**
     * Schedules the Firebase-gated FSI priming dialog after `dialog.delay` ms, when
     * [LockScreenPermission.shouldShowDialog] passes (SDK 14+, feature on, country allowed,
     * ungranted, within `show_after_days` / `max_show_count`). The dialog runs FIRST;
     * when it's resolved the permission sheet follows:
     *  - **Not now / dismissed** → the sheet shows immediately.
     *  - **Enable** → the user leaves to the system FSI page; the sheet is shown on
     *    return (see [fsiSettingsLauncher]).
     * If the dialog is no longer eligible when the delay fires, the sheet shows
     * straight away so the flow never dead-ends.
     */
    private fun scheduleFsiDialog(cfg: LockScreenConfig) {
        fsiHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!LockScreenPermission.shouldShowDialog(this, cfg)) {
                maybeAutoShowPermissionSheet()
                return@postDelayed
            }
            LockScreenPermission.markDialogShown(this)
            LockScreenPrimingDialog.show(this, cfg) { enabled ->
                if (enabled) {
                    // Off to the system FSI page — surface the sheet once we're back.
                    awaitFsiReturnForSheet = true
                } else {
                    maybeAutoShowPermissionSheet()
                }
            }
        }, cfg.dialog.delayMs)
    }

    /**
     * True when the permission sheet has been dismissed at least once and at
     * least one of its permissions is still missing — the condition for Home's
     * "Manage" hint.
     */
    fun shouldShowPermissionHint(): Boolean =
        permissionSheetDismissed && PermissionSheetDialog.hasPending(this)

    /** Runs when the sheet closes; nudges Home to (re)evaluate its permission hint. */
    private fun onPermissionSheetDismissed() {
        permissionSheetDismissed = true
        maybeShowRateUs()
        (tabs.firstOrNull { it.fragment is CallLogFragment }?.fragment as? CallLogFragment)
            ?.refreshPermissionHint()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after returning from a permission/Settings round trip.
        updateOverlayBanner()
        // FSI grant round-trip: stop the watcher and, once granted, close the dialog.
        LockScreenPermission.stopWatch(this)
        if (LockScreenPermission.isGranted(this)) LockScreenPrimingDialog.dismissIfShowing()
        // Safety net for the auto-return: if the FSI grant landed us back here, run
        // the deferred permission sheet. Guarded on isGranted so the earlier
        // notification-permission-dialog return can't trigger it prematurely.
        if (awaitFsiReturnForSheet && LockScreenPermission.isGranted(this)) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
        // Resume an interrupted update (IMMEDIATE re-prompts; FLEXIBLE completes a finished download).
        AppUpdateCoordinator.resumeUpdate()
    }

    override fun onDestroy() {
        stopOverlayGrantPoll()
        stopFsiGrantPoll()
        fsiHandler.removeCallbacksAndMessages(null)
        fsiReturnWatcher.unregister()
        LockScreenPermission.stopWatch(this)
        AppUpdateCoordinator.destroy()
        // The exit dialog holds this Activity as its window context — leaving it
        // attached on a config change leaks the window.
        ExitConfirmDialog.dismissIfShowing()
        rateUsHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Overlay-permission banner ---

    /**
     * The banner is only relevant once the core permissions are in place: show it
     * when call-log AND contacts are granted but the overlay permission is not.
     */
    private fun updateOverlayBanner() {
        val coreGranted = isPermissionGranted(Manifest.permission.READ_CALL_LOG) &&
            isPermissionGranted(Manifest.permission.READ_CONTACTS)
        val show = coreGranted && !OverlayPermissionUtils.isGranted(this)
        binding.overlayBannerVw.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the system "display over other apps" page IN-TASK (for-result) and
     * starts the in-activity grant poll to catch the toggle and auto-return.
     * Invoked by the banner's Enable button and by each fragment's permission flow.
     */
    fun startOverlayPermissionFlow() {
        if (OverlayPermissionUtils.isGranted(this)) {
            updateOverlayBanner()
            return
        }

        // We open system Settings ourselves — the programmatic return to the app
        // must NOT trigger an App Open ad. One-shot skip, consumed on next foreground.
        AppOpenAdManager.skipNextAppOpenAd = true

        // Open ONLY the system overlay-Settings page, in our own task. The grant is
        // caught by the in-activity poll (startOverlayGrantPoll) while we sit behind
        // Settings; on grant it reorders MainShellActivity back to the front.
        val launched = runCatching {
            overlayLauncher.launch(OverlayPermissionUtils.buildOverlayIntent(packageName))
            openActivity<OverlayTutorialActivity>(isAdd = false)
        }.isSuccess
        if (!launched) return

        awaitingOverlaySettings = true
        startOverlayGrantPoll()
    }

    /** Begin polling for the overlay grant (idempotent). Called when we open Settings. */
    private fun startOverlayGrantPoll() {
        if (overlayGrantPolling) return
        overlayGrantPolling = true
        overlayPollDeadline = SystemClock.elapsedRealtime() + OVERLAY_GRANT_POLL_TIMEOUT_MS
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
        overlayGrantPollHandler.postDelayed(overlayGrantPoll, OVERLAY_GRANT_POLL_MS)
    }

    private fun stopOverlayGrantPoll() {
        overlayGrantPolling = false
        awaitingOverlaySettings = false
        overlayGrantPollHandler.removeCallbacks(overlayGrantPoll)
    }

    /**
     * Overlay grant detected while the user sat on the "display over other apps"
     * page → refresh the banner and reorder the EXISTING MainShellActivity to the front
     * of the same task, so the (NO_HISTORY) Settings page drops away and the user
     * lands back on their current tab without pressing Back. In-task REORDER = no
     * background-activity-start, so it needs no BAL privilege on Android 12+/16.
     */
    private fun onOverlayGranted() {
        updateOverlayBanner()
        // Grant landed, but we are no longer the reason the user is elsewhere —
        // refresh state silently instead of surfacing over another app.
        if (!awaitingOverlaySettings) return
        awaitingOverlaySettings = false
        runCatching {
            startActivity(
                Intent(this, MainShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
    }

    /**
     * Back retraces the visited-tab stack; once it empties (on Home) a
     * double-back within 2s exits the app.
     */
    private fun handleBack() {
        // Retrace the tab history first.
        if (backStack.isNotEmpty()) {
            select(backStack.removeLast(), recordHistory = false)
            lastBackMs = 0L // restart the exit window
            return
        }

        // Safety net: not on Home with empty history -> go Home.
        val homeIndex = tabs.indexOfFirst { it.fragment is CallLogFragment }.coerceAtLeast(0)
        if (currentIndex != homeIndex) {
            select(homeIndex, recordHistory = false)
            lastBackMs = 0L
            return
        }

        val cfg = OnboardingStepConfig.exitConfig(this)
        val managed = cfg.isEnable &&
            OnboardingStepConfig.isCountryAllowed(this, cfg.countryCheckEnabled, cfg.excludedCountries)
        if (managed && cfg.exitType.equals("dialog", ignoreCase = true) && cfg.dialogEnabled) {
            showExitDialog(cfg)
        } else {
            doubleBackToExit(
                intervalMs = if (managed) cfg.doubleBackIntervalSec * 1000L else EXIT_INTERVAL_MS,
                toastText = cfg.doubleBackToastText.ifBlank { getString(R.string.press_back_again) },
            )
        }
    }

    /** Default behaviour: a second back-press within [intervalMs] exits the app. */
    private fun doubleBackToExit(intervalMs: Long, toastText: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackMs < intervalMs) {
            exitToast?.cancel()
            exitToHome()
        } else {
            lastBackMs = now
            exitToast = Toast.makeText(this, toastText, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    /**
     * `exit.exitType == "dialog"`: the custom confirm dialog ([ExitConfirmDialog] — remote
     * copy + an optional native/banner ad slot), optionally fronted by an
     * interstitial once the user confirms.
     */
    private fun showExitDialog(cfg: OnboardingStepConfig.ExitConfig) {
        ExitConfirmDialog.show(this, cfg) {
            if (cfg.isInterShow) {
                TransitionInterstitialAd().presentInterstitial(this) { exitToHome() }
            } else {
                exitToHome()
            }
        }
    }

    /**
     * Exits the app to the Home launcher (instead of a bare [finishAffinity]).
     *
     * A system "Manage" Settings page (overlay / full-screen-intent) is a
     * `singleTask` activity, so it lives in its **own** task, excluded from
     * Recents. A plain `finishAffinity()` on double-back closes our task and lets
     * that lingering Settings task surface in the foreground. Bringing Home to the
     * front first guarantees the device lands on the launcher, never on a leftover
     * Settings page, then we finish our task.
     */
    private fun exitToHome() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finishAffinity()
    }

    /**
     * Switches to the Lookup tab. If [number] is given (e.g. from Home search),
     * the Lookup fragment runs the search for it on arrival.
     */
    fun showLookup(number: String? = null) {
        val index = tabs.indexOfFirst { it.fragment is NumberFinderFragment }
        if (index < 0) return
        select(index)
        if (!number.isNullOrBlank()) {
            (tabs[index].fragment as? NumberFinderFragment)?.requestSearch(number)
        }
    }

    /** Switches to the Recents tab (Home's "See all" recent activity). */
    fun showRecents() {
        val index = tabs.indexOfFirst { it.fragment is CallLogFragment }
        if (index >= 0) select(index)
    }

    /** Switches to the Blocklist tab. */
    fun showBlocklist() {
        val index = tabs.indexOfFirst { it.fragment is BlockedNumbersFragment }
        if (index >= 0) select(index)
    }

    /** One-shot pop when a bottom-bar icon is tapped. */
    private fun animateIcon(icon: View) {
        icon.animate().cancel()
        icon.scaleX = 0.7f
        icon.scaleY = 0.7f
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    /**
     * Fades a nav cell's icon + label to [color]. Animated so the violet doesn't
     * snap in a frame ahead of the pane cross-fade; instant when [animate] is false
     * (first selection, and config changes).
     */
    private fun tintNavCell(nav: ItemNavBinding, color: Int, animate: Boolean) {
        val from = nav.navLabelVw.currentTextColor
        if (!animate || from == color) {
            nav.navIconVw.imageTintList = ColorStateList.valueOf(color)
            nav.navLabelVw.setTextColor(color)
            return
        }
        ValueAnimator.ofArgb(from, color).apply {
            duration = 200L
            addUpdateListener {
                val c = it.animatedValue as Int
                nav.navIconVw.imageTintList = ColorStateList.valueOf(c)
                nav.navLabelVw.setTextColor(c)
            }
            start()
        }
    }

    private fun select(index: Int, recordHistory: Boolean = true, animate: Boolean = true) {
        if (index == currentIndex) return

        // Record the tab we're leaving so Back can retrace to it (each tab kept once).
        if (recordHistory && currentIndex >= 0) {
            backStack.remove(index)
            backStack.remove(currentIndex)
            backStack.addLast(currentIndex)
        }

        val tab = tabs[index]

        supportFragmentManager.beginTransaction().apply {
            // Cross-fade the outgoing and incoming panes. Skipped on the very first
            // selection so the app doesn't fade in over a blank container at launch.
            if (animate) setCustomAnimations(R.anim.anim_tab_enter, R.anim.anim_tab_exit)
            setReorderingAllowed(true)
            if (!tab.fragment.isAdded) add(R.id.fragContainer, tab.fragment)
            tabs.forEach { if (it.fragment.isAdded && it !== tab) hide(it.fragment) }
            show(tab.fragment)
        }.commit()

        tabs.forEachIndexed { i, t ->
            val nav = t.nav ?: return@forEachIndexed
            val active = i == index
            nav.navIconVw.setImageResource(if (active) t.selectedIcon else t.unselectedIcon)
            val color = ContextCompat.getColor(
                this, if (active) R.color.primary else R.color.on_surface_variant
            )
            tintNavCell(nav, color, animate)
            nav.navIndicatorVw.visibility = if (active) View.VISIBLE else View.INVISIBLE
        }

        // The centre action is always tinted; it only reacts to being the active
        // destination by lifting slightly.
        binding.fabLookupVw.animate().cancel()
        binding.fabLookupVw.animate()
            .scaleX(if (tabs[index].nav == null) 1.08f else 1f)
            .scaleY(if (tabs[index].nav == null) 1.08f else 1f)
            .setDuration(if (animate) 220L else 0L)
            .setInterpolator(OvershootInterpolator())
            .start()

        currentIndex = index
        applyTopInsetForTab(index)
    }

    /**
     * Tabs with a blue hero (Home, Recents, Contacts, Lookup) draw under the status
     * bar — no top inset on the container, light status-bar icons, and the fragment
     * pads its own hero.
     */
    private fun applyTopInsetForTab(index: Int) {
        if (index < 0) return
        val fragment = tabs.getOrNull(index)?.fragment
        val immersive = fragment is CallLogFragment ||
            fragment is BlockedNumbersFragment ||
            fragment is ToolboxFragment ||
            fragment is ContactListFragment ||
            fragment is NumberFinderFragment
        binding.fragContainer.setPadding(0, if (immersive) 0 else statusBarTop, 0, 0)
        // All v2 tabs (Home / Recents / Contacts / Lookup) now use a LIGHT background,
        // so the status-bar icons are always dark.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = true
    }

    companion object {
        private const val EXIT_INTERVAL_MS = 2000L

        /** Breather after the queue clears, so the sheet doesn't jump the user. */
        private const val RATE_US_SETTLE_MS = 1200L

        /** Same tag as AppRatingPrompt, so one filter shows the whole flow. */
        private const val RATE_US_TAG = "RateUs"

        /** Grant-poll cadence while the user is on the FSI Settings page. */
        private const val FSI_GRANT_POLL_MS = 350L
        private const val OVERLAY_GRANT_POLL_MS = 350L

        /** Give up watching for the overlay grant after this long. */
        private const val OVERLAY_GRANT_POLL_TIMEOUT_MS = 90_000L

        /** Intent extra: a number to identify — routes straight to the Lookup tab. */
        const val EXTRA_LOOKUP_NUMBER = "extra_lookup_number"
        const val EXTRA_OPEN_BLOCKLIST = "extra_open_blocklist"
    }
}