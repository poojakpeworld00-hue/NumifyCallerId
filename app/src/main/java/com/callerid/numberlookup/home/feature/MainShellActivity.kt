package com.callerid.numberlookup.home.feature

import android.animation.ObjectAnimator
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
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.callerid.numberlookup.home.monetize.strategy.AdPreferenceStore
import com.callerid.numberlookup.home.monetize.strategy.recordPermissionOutcome
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager
import com.callerid.numberlookup.home.monetize.delivery.UpdateFlowCallback
import com.callerid.numberlookup.home.monetize.delivery.AppUpdateCoordinator
import com.callerid.numberlookup.home.monetize.delivery.fullpage.TransitionInterstitialAd
import com.callerid.numberlookup.home.monetize.delivery.openActivity
import com.callerid.numberlookup.home.common.openActivity
import com.callerid.numberlookup.home.monetize.delivery.engagement.OverlayTutorialActivity
import com.callerid.numberlookup.home.BuildConfig
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.repository.SettingsRepository
import com.callerid.numberlookup.home.permission.PermissionCoordinator
import com.callerid.numberlookup.home.permission.PermissionSheetDialog
import com.callerid.numberlookup.home.permission.lockscreen.LockScreenConfig
import com.callerid.numberlookup.home.permission.lockscreen.LockScreenPermission
import com.callerid.numberlookup.home.permission.lockscreen.LockScreenPrimingDialog
import com.callerid.numberlookup.home.permission.lockscreen.LockScreenReturnWatcher
import com.callerid.numberlookup.home.feature.onboarding.OnboardingStepConfig
import com.callerid.numberlookup.home.feature.exitflow.ExitConfirmDialog
import com.callerid.numberlookup.home.feature.feedback.AppRatingPrompt
import com.callerid.numberlookup.home.databinding.ActivityMainShellBinding
import com.callerid.numberlookup.home.databinding.ItemNavBinding
import com.callerid.numberlookup.home.resolver.ContactUploader
import com.callerid.numberlookup.home.feature.contacts.ContactListFragment
import com.callerid.numberlookup.home.feature.dialer.DialerFragment
import com.callerid.numberlookup.home.feature.finder.LookupActivity
import com.callerid.numberlookup.home.feature.calllog.CallLogFragment
import com.callerid.numberlookup.home.feature.blocklist.BlocklistActivity
import com.callerid.numberlookup.home.feature.tools.ToolboxFragment
import com.callerid.numberlookup.home.feature.overlay.OverlayAskPolicy
import com.callerid.numberlookup.home.feature.overlay.OverlayPermissionUtils
import com.callerid.numberlookup.home.feature.overlay.ToolOverlayGate

/**
 * Shell Activity built on a hand-rolled LinearLayout bottom bar rather than
 * BottomNavigationView. Its five fragments are swapped with show/hide so each
 * one keeps its state across tab changes.
 */
class MainShellActivity : BaseActivity<ActivityMainShellBinding>() {

    override val layoutId: Int = R.layout.activity_main_shell

    /**
     * One destination.
     *
     * [nav] is nullable because the shell used to carry tabs the bar never showed
     * — Lookup, then Blocklist. Both are Activities now and every remaining tab
     * has a chip, but the field stays: a destination without a chip is a thing
     * this shell should be able to hold, and the alternative is a non-null type
     * that the next such tab has to unpick.
     */
    private data class Tab(
        val nav: ItemNavBinding?,
        val fragment: Fragment,
        @param:DrawableRes val selectedIcon: Int,
        @param:DrawableRes val unselectedIcon: Int,
        @param:StringRes val label: Int,
        /** What this tab turns when it is the current one. */
        @param:ColorRes val activeColor: Int = R.color.ds_accent,
    )

    private lateinit var tabs: List<Tab>
    private var currentIndex = -1
    private var lastBackMs = 0L
    private var exitToast: Toast? = null


    /** Status-bar height read from window insets and re-applied per tab. */
    private var statusBarTop = 0

    /** Tabs the user has visited, oldest first, so Back can retrace them. */
    private val backStack = ArrayDeque<Int>()

    /** Posts the delayed FSI priming dialog (see [scheduleFsiDialog]). */
    private val fsiHandler = Handler(Looper.getMainLooper())

    /** Returns to MainShellActivity when the FSI toggle flips during the grant round-trip. */
    private val fsiReturnWatcher by lazy { LockScreenReturnWatcher(this) }

    /**
     * Set once the first-run permission sheet has been closed, by "Not now" or by
     * swipe. Home reads it through [shouldShowPermissionHint] so the "Manage"
     * hint only appears after the user has dismissed the sheet at least once.
     */
    var permissionSheetDismissed = false
        private set

    // In-activity overlay-grant poll: the dependable auto-return for the
    // "Manage" flow. We open the system "display over other apps" page
    // in-task (for-result), so the process keeps a foreground task and this
    // main-thread Handler keeps ticking while MainShellActivity is merely
    // stopped. As soon as the toggle flips we pull the Activity back with an
    // in-task REORDER_TO_FRONT: no background Service and no background
    // activity start, both of which are unreliable on Android 12+/16 - which
    // is exactly what the old watcher Service leaned on, and why it is gone.
    private val overlayGrantPollHandler = Handler(Looper.getMainLooper())
    private var overlayGrantPolling = false

    /**
     * True only while we are parked behind the overlay-Settings page we opened
     * ourselves. On grant the poll reorders Home to the front, which is right if
     * the user is still on that page - but if they wandered off to another app
     * first, the same reorder yanks us in front of whatever they are doing.
     */
    private var awaitingOverlaySettings = false

    /** Wall-clock cut-off for the poll, so a user who wandered off isn't tracked forever. */
    private var overlayPollDeadline = 0L
    private val overlayGrantPoll = object : Runnable {
        override fun run() {
            if (isDestroyed) { overlayGrantPolling = false; return }
            if (OverlayPermissionUtils.isGranted(this@MainShellActivity)) {
                overlayGrantPolling = false
                onOverlayGranted()
            } else if (SystemClock.elapsedRealtime() > overlayPollDeadline) {
                // The user never returned to the page. Stop watching rather than
                // lying in wait for a grant that may come at any time.
                stopOverlayGrantPoll()
            } else {
                overlayGrantPollHandler.postDelayed(this, OVERLAY_GRANT_POLL_MS)
            }
        }
    }

    /** Re-evaluates the banner after the user comes back from the overlay Settings page. */
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        stopOverlayGrantPoll()
        updateOverlayBanner()
        // Back, Done, or a grant made after the poll gave up — whichever way the
        // user left that page, the tool they actually tapped is still owed them.
        resumePendingTool()
    }

    /**
     * The tool the user tapped, held while the overlay Settings page is up.
     *
     * Both return paths consume it — the poll's grant handler and the launcher
     * callback above — whichever fires first; the second finds it already null.
     */
    private var pendingToolIntent: Intent? = null

    /** Opens the FSI Settings page in-task for the priming dialog, leaving no stray task. */
    private val fsiSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Back from the FSI Settings page, by auto-return or by manual back.
        stopFsiGrantPoll()
        if (LockScreenPermission.isGranted(this)) LockScreenPrimingDialog.dismissIfShowing()
        // The FSI "Enable" round-trip is done, so the permission sheet can appear.
        if (awaitFsiReturnForSheet) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
    }

    /** Set after the FSI dialog's "Enable" sends us to Settings; drives the deferred sheet. */
    private var awaitFsiReturnForSheet = false

    /** At most one in-app review attempt per Activity instance. */
    private var rateUsAttempted = false
    private val rateUsHandler = Handler(Looper.getMainLooper())

    // In-activity grant poll: the dependable auto-return for the dialog's Enable path.
    //
    // The FSI Settings page opens in-task, so the app holds a foreground task
    // the whole time it is on screen. A main-thread Handler keeps ticking while
    // this Activity is merely stopped and the process stays alive; the moment
    // the toggle flips we reorder it back to the front in-task, which needs no
    // background-activity-start privilege. This is what replaced relying on
    // LockScreenWatchService: on Android 12+/16 a background Service cannot be started here.
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

    /** Starts polling for the FSI grant; idempotent. Called when we open FSI settings. */
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
     * The grant arrived while the user was on the FSI Settings page, so dismiss
     * the priming dialog and reorder the existing MainShellActivity to the front
     * of the same task. The NO_HISTORY Settings page falls away, onResume and the
     * launcher both see the grant, and the permission-sheet follow-up runs there.
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

    /** Opens the FSI "Manage" page for the priming dialog; called by LockScreenPrimingDialog. */
    fun openFsiSettings() {
        LockScreenPermission.openSettings(this, fsiSettingsLauncher)
        // Detecting the grant from the Activity itself is the only reliable option:
        // the background service cannot start on the way to Settings on Android 12+/16.
        startFsiGrantPoll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The update result-launcher has to be registered before the Activity is STARTED.
        AppUpdateCoordinator.registerActivityLauncher(this)
        maybeCheckForUpdate()
    }

    /**
     * Runs the Play in-app update flow when Remote Config asks for it.
     *  - `In_App_Update_Show`       - master switch for offering an update at all.
     *  - `In_App_Update_Force_Show` - true means IMMEDIATE (mandatory), false FLEXIBLE.
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            statusBarTop = bars.top
            // No top padding on the root: Home's hero deliberately draws under the status bar.
            // Every tab applies its own top inset in [applyTopInsetForTab].
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            applyTopInsetForTab(currentIndex)
            insets
        }

        tabs = listOf(
            // One glyph per tab now: the redesign carries the selected state in the
            // chip's fill and the label's weight, not in a second icon.
            //
            // Order matches the bar left-to-right, so a swipe between tabs moves
            // the way the chips do. Blocklist comes last and has no chip: it is
            // reached from Settings and from Call Details. Lookup used to sit
            // beside it; it is its own Activity now.
            Tab(
                binding.navDialer, DialerFragment(),
                R.drawable.ic_ds_nav_dialer, R.drawable.ic_ds_nav_dialer, R.string.nav_dialer
            ),
            Tab(
                binding.navRecents, CallLogFragment(),
                R.drawable.ic_ds_nav_recents, R.drawable.ic_ds_nav_recents, R.string.nav_recents
            ),
            Tab(
                binding.navContacts, ContactListFragment(),
                R.drawable.ic_ds_nav_contacts, R.drawable.ic_ds_nav_contacts, R.string.nav_contacts
            ),
            Tab(
                binding.navTools, ToolboxFragment(),
                R.drawable.ic_ds_nav_tools, R.drawable.ic_ds_nav_tools, R.string.nav_tools
            ),
        )

        tabs.forEachIndexed { index, tab ->
            val nav = tab.nav ?: return@forEachIndexed
            nav.navLabel.setText(tab.label)
            nav.root.setOnClickListener {
                animateIcon(nav.navIcon)
                select(index)
            }
        }

        // The raised centre action is gone, and so is the Lookup tab: Lookup is
        // its own Activity, opened from the tile in Tools and from "Identify" on
        // an unknown recents row.

        setupSwipeNavigation()
        // Recents is home, not tab 0. The dialer took the leftmost chip when the
        // bar was rebuilt, but opening the app onto a keypad would say the app is
        // a phone dialer — it is a caller ID, and the call log is what it has to
        // show you. Back also unwinds to here (see handleBack).
        select(tabs.indexOfFirst { it.fragment is CallLogFragment }.coerceAtLeast(0), animate = false)

        binding.buttonEnableOverlay.setOnClickListener { startOverlayPermissionFlow() }

        onBackPressedDispatcher.addCallback(this) { handleBack() }

        // One-time contact upload; a no-op when already done or contacts aren't permitted.
        ContactUploader.uploadOnceIfNeeded(this)

        // Arm the FSI auto-return so a grant on the system page brings us back.
        fsiReturnWatcher.register()

        // First-run priming order: the FSI dialog goes first and the permission
        // sheet follows once it resolves - immediately on "Not now", or after the
        // system-settings round-trip on "Enable". When FSI isn't eligible at all,
        // the sheet opens straight away, subject to its own RC frequency gate.
        val fsiCfg = LockScreenConfig.load(this)
        if (LockScreenPermission.shouldShowDialog(this, fsiCfg)) {
            scheduleFsiDialog(fsiCfg)
        } else {
            maybeAutoShowPermissionSheet()
        }

        // "Identify this number" from Call Details drops the user directly on Lookup.
        handleLookupIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLookupIntent(intent)
    }

    /**
     * Honours a deep link that asks the shell to open Lookup on a number.
     *
     * Nothing in the app uses this any more — Lookup and Blocklist are their own
     * Activities and their callers start them directly — but it stays for an
     * external entry point (a notification, a shortcut) that has only the shell
     * to aim at. The extra is consumed on the way through, or a configuration
     * change would replay it and reopen Lookup over whatever the user had moved
     * on to.
     */
    private fun handleLookupIntent(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_LOOKUP_NUMBER)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_LOOKUP_NUMBER)
        showLookup(number)
    }

    // --- Permission priming (bottom sheet) ---

    /**
     * Opens the permission priming bottom sheet ([PermissionSheetDialog]) on
     * demand - wire it to any button or menu click:
     *
     * ```
     * someButton.setOnClickListener { showPermissionSheet() }
     * ```
     *
     * It lists every permission still outstanding (notification, phone state
     * while HD_VBC_Show is on, call log, contacts, overlay) and collects them;
     * anything already granted is left out.
     */
    fun showPermissionSheet() {
        PermissionSheetDialog.show(this) {
            updateOverlayBanner()
            onPermissionSheetDismissed()
        }
    }

    /**
     * A left or right swipe on the pane container steps along the bottom bar.
     *
     * Only the four bar tabs participate. Blocklist has no chip, so it is
     * skipped, and swiping while it is open does nothing rather than jumping
     * somewhere arbitrary.
     * There is no wrap-around either: swiping past either end is a no-op, just
     * as a tab strip behaves.
     */
    private fun setupSwipeNavigation() {
        binding.fragmentContainer.onSwipe = { direction ->
            val barTabs = tabs.indices.filter { tabs[it].nav != null }
            val position = barTabs.indexOf(currentIndex)
            if (position >= 0) {
                val target = position + direction
                if (target in barTabs.indices) select(barTabs[target])
            }
        }
    }

    /** Auto-opens the permission sheet when perms are pending and the RC gate agrees. */
    private fun maybeAutoShowPermissionSheet() {
        if (PermissionSheetDialog.shouldAutoShow(this)) showPermissionSheet()
        else runHomePermissionReattempt()
    }

    /**
     * Home's second chance at the splash permissions, for configs that run
     * without the bottom sheet.
     *
     * The sheet was the only thing on Home that ever started the permission
     * engine, so `screen.permission_sheet.isEnable = false` quietly took the
     * reattempt away with it: whatever the user declined on the splash was never
     * asked for again, and the audience configs that ask for notifications (and,
     * on the paid side, phone state) a second time on Home had nothing to fire
     * them. The engine's `permission_engine` rules already say which permissions
     * Home may raise and in what order - this only gives them a trigger.
     *
     * Deliberately on the no-sheet path only. With the sheet on it owns the
     * flow, and a second queue running behind it would race its prompts.
     * [PermissionCoordinator.check] completes immediately when no rule targets
     * this screen, so the rate-us handoff is unchanged for a config that
     * configures nothing here.
     */
    private fun runHomePermissionReattempt() {
        PermissionCoordinator.check(this) { maybeShowRateUs() }
    }

    /**
     * The in-app review prompt, raised only once Home's prompt queue is empty.
     *
     * It is deliberately not on a plain timer after Home appears: both the
     * permission sheet and the FSI priming dialog can auto-open here, and Play's
     * review sheet launched behind either one burns a quota attempt for nothing -
     * the API reports success whether or not anything was actually shown. So it
     * fires only from the two points where nothing else is queued: the sheet
     * closing, and the sheet deciding not to open at all.
     *
     * One attempt per Activity instance; every other gate belongs to [AppRatingPrompt].
     */
    private fun maybeShowRateUs() {
        // Logged rather than silent: without this, these two skips look exactly
        // like "the trigger never ran", which is the ambiguity that makes this
        // flow hard to test. AppRatingPrompt logs its own gate decisions.
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
     * Schedules the Firebase-gated FSI priming dialog `dialog.delay` ms out, when
     * [LockScreenPermission.shouldShowDialog] agrees (SDK 14+, feature enabled,
     * country allowed, still ungranted, inside `show_after_days` /
     * `max_show_count`). The dialog runs first, and the permission sheet follows
     * once it resolves:
     *  - **Not now / dismissed** - the sheet opens right away.
     *  - **Enable** - the user leaves for the system FSI page and the sheet opens
     *    on return (see [fsiSettingsLauncher]).
     * Should the dialog no longer be eligible when the delay fires, the sheet
     * opens immediately so the flow can never dead-end.
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
                    // Heading for the system FSI page, so raise the sheet once we are back.
                    awaitFsiReturnForSheet = true
                } else {
                    maybeAutoShowPermissionSheet()
                }
            }
        }, cfg.dialog.delayMs)
    }

    /**
     * True when the permission sheet has been dismissed at least once and at
     * least one of its permissions is still missing - the condition behind
     * Home's "Manage" hint.
     */
    fun shouldShowPermissionHint(): Boolean =
        permissionSheetDismissed && PermissionSheetDialog.hasPending(this)

    /** Runs on sheet close, prompting Home to re-evaluate its permission hint. */
    private fun onPermissionSheetDismissed() {
        permissionSheetDismissed = true
        maybeShowRateUs()
        (tabs.firstOrNull { it.fragment is CallLogFragment }?.fragment as? CallLogFragment)
            ?.refreshPermissionHint()
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate after a permission or Settings round trip.
        updateOverlayBanner()
        // FSI grant round-trip: stop the watcher, and close the dialog once granted.
        LockScreenPermission.stopWatch(this)
        if (LockScreenPermission.isGranted(this)) LockScreenPrimingDialog.dismissIfShowing()
        // Safety net for the auto-return: if the FSI grant is what brought us back,
        // run the deferred permission sheet. Guarded on isGranted so the earlier
        // notification-permission dialog returning cannot trigger it early.
        if (awaitFsiReturnForSheet && LockScreenPermission.isGranted(this)) {
            awaitFsiReturnForSheet = false
            maybeAutoShowPermissionSheet()
        }
        // Resume an interrupted update: IMMEDIATE re-prompts, FLEXIBLE finishes a completed download.
        AppUpdateCoordinator.resumeAppUpdate()
    }

    override fun onPause() {
        super.onPause()
    }


    override fun onDestroy() {
        stopOverlayGrantPoll()
        stopFsiGrantPoll()
        fsiHandler.removeCallbacksAndMessages(null)
        fsiReturnWatcher.unregister()
        LockScreenPermission.stopWatch(this)
        AppUpdateCoordinator.destroy()
        // The exit dialog holds this Activity as its window context, so leaving it
        // attached across a config change leaks the window.
        ExitConfirmDialog.dismissIfShowing()
        rateUsHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // --- Overlay-permission banner ---

    /**
     * The banner only makes sense once the core permissions are in place, so it
     * shows when call log and contacts are both granted but the overlay is not.
     */
    private fun updateOverlayBanner() {
        val coreGranted = isPermissionGranted(Manifest.permission.READ_CALL_LOG) &&
            isPermissionGranted(Manifest.permission.READ_CONTACTS)
        // The banner exists only to ask. With asking switched off it is hidden
        // rather than left showing an Enable button that would do nothing.
        val show = coreGranted &&
            !OverlayPermissionUtils.isGranted(this) &&
            OverlayAskPolicy.isAskingAllowed(this)
        binding.overlayBanner.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun isPermissionGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Opens the system "display over other apps" page in-task (for-result) and
     * starts the in-activity grant poll that catches the toggle and returns us.
     * Called by the banner's Enable button and by each fragment's permission flow.
     */
    fun startOverlayPermissionFlow() {
        if (OverlayPermissionUtils.isGranted(this)) {
            updateOverlayBanner()
            return
        }

        // The single funnel every overlay ask goes through, so the app-wide
        // switch is enforced here once rather than at each of the six callers.
        // The callers that are a visible control hide themselves as well; this
        // is the backstop for the automatic ones.
        if (!OverlayAskPolicy.isAskingAllowed(this)) {
            updateOverlayBanner()
            return
        }

        // We are the ones opening system Settings, so the programmatic return must
        // not fire an App Open ad. One-shot skip, consumed on the next foreground.
        AppOpenAdManager.skipNextAppOpenAd = true

        // Open only the system overlay-Settings page, inside our own task. The grant
        // is picked up by the in-activity poll (startOverlayGrantPoll) while we sit
        // behind Settings, and on grant it reorders this Activity back to the front.
        val launched = runCatching {
            overlayLauncher.launch(OverlayPermissionUtils.buildOverlayIntent(packageName))
            openActivity<OverlayTutorialActivity>(isAdd = false)
        }.isSuccess
        if (!launched) return

        awaitingOverlaySettings = true
        startOverlayGrantPoll()
    }

    /** Starts polling for the overlay grant; idempotent. Called when we open Settings. */
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
     * The overlay grant arrived while the user was on the "display over other
     * apps" page, so refresh the banner and reorder the existing Activity to the
     * front of the same task. The NO_HISTORY Settings page falls away and the
     * user lands back on their current tab without pressing Back. An in-task
     * reorder is not a background-activity-start, so no BAL privilege is needed
     * on Android 12+/16.
     */
    private fun onOverlayGranted() {
        updateOverlayBanner()
        // The grant landed, but we are no longer why the user is elsewhere, so
        // refresh state quietly instead of surfacing over another app.
        if (!awaitingOverlaySettings) return
        awaitingOverlaySettings = false
        runCatching {
            startActivity(
                Intent(this, MainShellActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        // After the reorder, so the tool lands on top of the shell rather than
        // under it.
        resumePendingTool()
    }

    /**
     * Opens a tool, with the two detours that belong in front of it: the
     * transition interstitial, then — when [ToolOverlayGate] says so — the
     * overlay Settings page.
     *
     * The tool opens either way. The overlay permission is an engagement ask,
     * not something any of these tools needs, so declining it must not cost the
     * user the thing they tapped.
     */
    fun openToolGated(intent: Intent) {
        TransitionInterstitialAd().showInterstitial(this) {
            if (!ToolOverlayGate.shouldAsk(this)) {
                runCatching { startActivity(intent) }
                return@showInterstitial
            }

            ToolOverlayGate.markAsked()
            pendingToolIntent = intent
            startOverlayPermissionFlow()
            // startOverlayPermissionFlow gives up silently if the Settings page
            // cannot be opened. Without this the tool would be held for a return
            // that never comes, and the tap would look ignored.
            if (!awaitingOverlaySettings) resumePendingTool()
        }
    }

    /** Starts the held tool, exactly once. */
    private fun resumePendingTool() {
        val intent = pendingToolIntent ?: return
        pendingToolIntent = null
        runCatching { startActivity(intent) }
    }

    /**
     * Back retraces the visited-tab stack; once that empties, on Home, a second
     * back press within 2s leaves the app.
     */
    private fun handleBack() {
        // Retrace the tab history first.
        if (backStack.isNotEmpty()) {
            select(backStack.removeLast(), recordHistory = false)
            lastBackMs = 0L // restart the exit window
            return
        }

        // Safety net: off Home with an empty history means go Home.
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

    /** Default behaviour: a second back press inside [intervalMs] leaves the app. */
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
     * `exit.exitType == "dialog"`: the custom confirm dialog ([ExitConfirmDialog],
     * remote copy plus an optional native or banner slot), optionally preceded by
     * an interstitial once the user confirms.
     */
    private fun showExitDialog(cfg: OnboardingStepConfig.ExitConfig) {
        ExitConfirmDialog.show(this, cfg) {
            if (cfg.isInterShow) {
                TransitionInterstitialAd().showInterstitial(this) { exitToHome() }
            } else {
                exitToHome()
            }
        }
    }

    /**
     * Leaves the app to the launcher rather than calling a bare [finishAffinity].
     *
     * A system "Manage" Settings page (overlay or full-screen-intent) is a
     * `singleTask` activity, so it lives in its own task and is kept out of
     * Recents. A plain `finishAffinity()` on double-back closes our task and lets
     * that leftover Settings task come to the foreground. Bringing the launcher
     * forward first guarantees the device lands on Home, never on a stale
     * Settings page, and only then do we finish our own task.
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
     * Opens the Lookup screen. When [number] is supplied — "Identify" on a
     * recents row, for instance — it is searched as soon as the screen arrives.
     *
     * Lookup was a nav-less tab here, swapped in under the bar. It is its own
     * Activity now, so this leaves the tabs as the user left them and Back
     * returns to whichever one they came from.
     */
    fun showLookup(number: String? = null) {
        openActivity(LookupActivity.newIntent(this, number))
    }

    /** Moves to the Recents tab, behind Home's "See all" recent activity. */
    fun showRecents() {
        val index = tabs.indexOfFirst { it.fragment is CallLogFragment }
        if (index >= 0) select(index)
    }

    /**
     * Opens the Blocklist screen. Its own Activity now, not a tab — see
     * [BlocklistActivity] for why Back demanded that.
     */
    fun showBlocklist() {
        openActivity(BlocklistActivity.newIntent(this))
    }

    /** Moves to the Dialer tab. */
    fun showDialer() {
        val index = tabs.indexOfFirst { it.fragment is DialerFragment }
        if (index >= 0) select(index)
    }

    /** A single pop animation when a bottom-bar icon is tapped. */
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
     * Fades a nav cell's icon and label to [color]. Animating it stops the accent
     * landing a frame ahead of the pane cross-fade; passing [animate] as false
     * applies it instantly, which is what the first selection and config changes
     * want.
     */
    private fun tintNavCell(
        nav: ItemNavBinding,
        color: Int,
        animate: Boolean,
    ) {
        val from = nav.navLabel.currentTextColor
        if (!animate || from == color) {
            nav.navIcon.imageTintList = ColorStateList.valueOf(color)
            nav.navLabel.setTextColor(color)
            return
        }
        // Glyph and label are crossfaded on one animator so they cannot arrive a
        // frame apart from each other.
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            addUpdateListener {
                val t = it.animatedValue as Float
                val c = argb.evaluate(t, from, color) as Int
                nav.navIcon.imageTintList = ColorStateList.valueOf(c)
                nav.navLabel.setTextColor(c)
            }
            start()
        }
    }

    private val argb = android.animation.ArgbEvaluator()

    private fun select(index: Int, recordHistory: Boolean = true, animate: Boolean = true) {
        if (index == currentIndex) return

        // Remember the tab we are leaving so Back can retrace it; each tab is kept once.
        if (recordHistory && currentIndex >= 0) {
            backStack.remove(index)
            backStack.remove(currentIndex)
            backStack.addLast(currentIndex)
        }

        val tab = tabs[index]

        supportFragmentManager.beginTransaction().apply {
            // Cross-fade the outgoing and incoming panes, skipped on the very first
            // selection so the app never fades in over an empty container at launch.
            if (animate) setCustomAnimations(R.anim.anim_tab_enter, R.anim.anim_tab_exit)
            setReorderingAllowed(true)
            if (!tab.fragment.isAdded) add(R.id.fragmentContainer, tab.fragment)
            tabs.forEach { if (it.fragment.isAdded && it !== tab) hide(it.fragment) }
            show(tab.fragment)
        }.commit()

        tabs.forEachIndexed { i, t ->
            val nav = t.nav ?: return@forEachIndexed
            val active = i == index
            nav.navIcon.setImageResource(if (active) t.selectedIcon else t.unselectedIcon)
            val color = ContextCompat.getColor(
                this, if (active) t.activeColor else R.color.ds_nav_idle
            )
            tintNavCell(nav, color, animate)
            // The bar above the glyph, in the same accent — the marker that
            // replaced the wash. INVISIBLE on the idle cells so they keep its
            // height and the four glyphs stay on one line.
            nav.navIndicator.backgroundTintList = ColorStateList.valueOf(color)
            nav.navIndicator.visibility = if (active) View.VISIBLE else View.INVISIBLE
            // The selected label also thickens rather than only recolouring. With
            // the wash gone that is part of what marks the current tab, not a
            // flourish on top of it.
            nav.navLabel.typeface = ResourcesCompat.getFont(
                this, if (active) R.font.mulish_bold else R.font.mulish_semibold
            )
        }

        // Nothing to light up for Blocklist: it has no control in the bar to
        // reflect its state.

        currentIndex = index
        applyTopInsetForTab(index)
    }

    /**
     * Tabs with a hero of their own draw under the status bar: no top inset on
     * the container, and the fragment pads its own header by the inset.
     */
    private fun applyTopInsetForTab(index: Int) {
        if (index < 0) return
        val fragment = tabs.getOrNull(index)?.fragment
        val immersive = fragment is CallLogFragment ||
            fragment is ToolboxFragment ||
            fragment is ContactListFragment ||
            fragment is DialerFragment
        binding.fragmentContainer.setPadding(0, if (immersive) 0 else statusBarTop, 0, 0)
        // Re-assert the theme's icon colour on every tab change. This used to pin
        // it dark, on the reasoning that every tab sits on a light background —
        // true in light mode and wrong in dark, where the page is #0E1117 and the
        // dark icons disappeared into it. The tabs still all share one background,
        // so one call covers them; it just has to ask the theme what that is.
        applySystemBarIcons()
    }

    companion object {
        private const val EXIT_INTERVAL_MS = 2000L

        /** A breather after the queue clears, so the sheet doesn't startle the user. */
        private const val RATE_US_SETTLE_MS = 1200L

        /** Shares AppRatingPrompt's tag, so one log filter covers the whole flow. */
        private const val RATE_US_TAG = "RateUs"

        /** How often to poll for the grant while the user is on the FSI Settings page. */
        private const val FSI_GRANT_POLL_MS = 350L
        private const val OVERLAY_GRANT_POLL_MS = 350L

        /** Stop watching for the overlay grant once this much time has passed. */
        private const val OVERLAY_GRANT_POLL_TIMEOUT_MS = 90_000L

        /** Intent extra carrying a number to identify; opens the Lookup screen. */
        const val EXTRA_LOOKUP_NUMBER = "extra_shell_lookup_number"
    }
}