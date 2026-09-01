package com.numify.callerid.lookup.permission

import android.app.Activity
import android.content.Context
import com.numify.callerid.monetize.strategy.recordEvent
import com.numify.callerid.monetize.strategy.recordPermissionOutcome
import com.numify.callerid.lookup.common.WindowInsetsHelper
import com.numify.callerid.lookup.feature.onboarding.OnboardingStepConfig
import java.lang.ref.WeakReference

/**
 * Global, Firebase-controlled permission engine.
 *
 * A single entry point — [check] — is the trigger. Call it from wherever you
 * want the flow to run (a button click, a specific screen's onResume, etc.):
 *
 * ```
 * someButton.setOnClickListener { PermissionCoordinator.check(this) }
 * ```
 *
 * It is NOT auto-triggered anymore; [init] (called once from the Application)
 * only warms the Remote Config so the config is ready by the time you trigger.
 *
 * For the Activity it is called with, the engine:
 *  1. reads the `permission_engine` Remote Config (via [PermissionRepository]),
 *  2. finds rules that target this Activity by simple name ([ScreenRouteMatcher]),
 *  3. drops permissions that are already granted, not applicable on this SDK, or
 *     already shown when `show_once` is set,
 *  4. orders the rest by `priority` ([PermissionRequestQueue]),
 *  5. waits each rule's `delay` ([PermissionScheduler]) then shows the request
 *     ([PermissionLauncher]) — advancing to the next only after the previous
 *     one completes (sequential flow).
 *
 * All state is guarded so repeated `onResume` calls, fast screen switches, and
 * Activity teardown can't double-prompt or leak.
 */
object PermissionCoordinator {

    private const val TAG = "PermissionCoordinator"

    private val scheduler = PermissionScheduler()

    /** The Activity whose queue is currently being processed. */
    private var activeRef: WeakReference<Activity>? = null
    private var running = false

    /** Fired once when the current run finishes normally (see [check]). */
    private var pendingOnComplete: (() -> Unit)? = null

    /**
     * One-time startup hook. Kicks a fresh Remote Config fetch so the newest
     * configuration is active as early as possible. Safe to call from
     * `Application.onCreate` (after `FirebaseApp.initializeApp`).
     */
    fun init(context: Context) {
        try {
            PermissionRepository.refreshFromRemote()
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "init failed", e)
        }
    }

    /**
     * Trigger the permission flow for [activity].
     *
     * [onComplete] (optional) fires **once, on the main thread, when the flow is
     * done** — i.e. after every configured permission has been asked, or
     * immediately when there was nothing to ask. Use it to redirect:
     *
     * ```
     * PermissionCoordinator.check(this) {
     *     startActivity(Intent(this, NextActivity::class.java))
     *     finish()
     * }
     * ```
     *
     * It does NOT fire when a run is interrupted (a different Activity triggers
     * the engine, or the Activity is torn down mid-flow) — in those cases a
     * redirect would be inappropriate.
     */
    @JvmStatic
    @JvmOverloads
    fun check(activity: Activity, onComplete: (() -> Unit)? = null) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                fireComplete(onComplete)
                return
            }
            val name = activity::class.java.simpleName

            // Already working on this exact Activity → let it finish; don't stack.
            val current = activeRef?.get()
            if (running && current === activity) return
            // A different Activity is now in the foreground → abandon the old run.
            if (running && current !== activity) abort()

            // From here on, completion is owned by complete()/abort().
            pendingOnComplete = onComplete

            val allRules = PermissionRepository.rules()
            val matched = if (allRules.isEmpty()) emptyList()
            else ScreenRouteMatcher.rulesFor(name, allRules)
            if (matched.isEmpty()) {
                WindowInsetsHelper.log(TAG, "No permission rules for $name")
                complete()
                return
            }

            val prefs = PermissionPreferences(activity)
            val pending = matched.filter { rule -> isStillNeeded(activity, rule, prefs) }
            if (pending.isEmpty()) {
                WindowInsetsHelper.log(TAG, "All configured permissions already satisfied on $name")
                complete()
                return
            }

            WindowInsetsHelper.log(TAG, "Queueing ${pending.size} permission(s) for $name")
            running = true
            activeRef = WeakReference(activity)
            processNext(PermissionRequestQueue(pending))
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "check() failed", e)
            abort()
        }
    }

    /**
     * Trigger the permission flow for the onboarding screen keyed [screenKey]
     * (`"language"`, `"onboarding"`, `"splash"`, `"fsi_permission"`) — sourced
     * directly from that screen's `screen.<screenKey>.permissions[]` in the
     * Onboarding Dynamic Flow config, instead of [check]'s Activity-name
     * matching against the (now retired) `permission_engine` block.
     *
     * Each permission entry's own country gate is applied before it's queued.
     * Sequencing, dedup, and completion semantics are identical to [check].
     */
    @JvmStatic
    @JvmOverloads
    fun checkScreenPermissions(activity: Activity, screenKey: String, onComplete: (() -> Unit)? = null) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                fireComplete(onComplete)
                return
            }

            val current = activeRef?.get()
            if (running && current === activity) return
            if (running && current !== activity) abort()

            pendingOnComplete = onComplete

            val entries = if (screenKey == "splash") {
                OnboardingStepConfig.splashConfig(activity).permissions
            } else {
                OnboardingStepConfig.stepConfig(activity, screenKey)?.permissions ?: emptyList()
            }
            val rules = entries
                .filter { OnboardingStepConfig.isCountryAllowed(activity, it.countryCheckEnabled, it.excludedCountries) }
                .flatMap { entry -> entry.permissionNames }
                .distinct()
                .mapIndexedNotNull { index, permissionName ->
                    val spec = PermissionUtils.specForAndroidPermission(permissionName)
                    if (spec == null) {
                        WindowInsetsHelper.log(TAG, "'$permissionName' not in PermissionUtils.CATALOG — skipped")
                        return@mapIndexedNotNull null
                    }
                    PermissionRule(
                        key = spec.key,
                        enabled = true,
                        activities = emptyList(),
                        delayMs = 0L,
                        priority = index,
                        showOnce = false,
                    )
                }

            val prefs = PermissionPreferences(activity)
            val pending = rules.filter { rule -> isStillNeeded(activity, rule, prefs) }
            if (pending.isEmpty()) {
                WindowInsetsHelper.log(TAG, "No pending screen permissions for '$screenKey'")
                complete()
                return
            }

            WindowInsetsHelper.log(TAG, "Queueing ${pending.size} permission(s) for screen '$screenKey'")
            running = true
            activeRef = WeakReference(activity)
            processNext(PermissionRequestQueue(pending))
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "checkScreenPermissions() failed", e)
            abort()
        }
    }

    /**
     * Requests a single permission by config [key] on demand, **independent of
     * the Activity-matching used by [check]**.
     *
     * Use this for an explicit trigger point (e.g. the FSI "Enable" button)
     * where you want exactly one permission asked — regardless of whether the
     * current Activity is listed in that permission's `activities` — and then to
     * continue. This is why the FSI screens can prime `notification` even though
     * the Remote Config only lists Splash/Main for it.
     *
     * [onComplete] always fires once, on the main thread, when done: after the
     * OS dialog resolves, or immediately when the permission is already granted,
     * not applicable on this SDK, gated off, or its `show_once` was already used.
     */
    @JvmStatic
    @JvmOverloads
    fun request(activity: Activity, key: String, onComplete: (() -> Unit)? = null) {
        try {
            if (activity.isFinishing || activity.isDestroyed) {
                fireComplete(onComplete); return
            }
            val spec = PermissionUtils.spec(key)
            if (spec == null) {
                WindowInsetsHelper.log(TAG, "request(): unknown permission key '$key' — skipped")
                fireComplete(onComplete); return
            }
            if (!PermissionUtils.isApplicableOnThisSdk(spec) ||
                !PermissionUtils.isPrefGateOpen(activity, spec) ||
                PermissionUtils.isGranted(activity, spec)
            ) {
                fireComplete(onComplete); return
            }

            // Config lookup: rule is keyed by permission (not by Activity), so a
            // targeted request works from any screen. When the rule exists but is
            // disabled in Remote Config, honour that and skip — this is the remote
            // off-switch for the FSI notification prime. (A missing rule = no
            // config for this key → still ask, driven by the spec alone.)
            val rule = PermissionRepository.rules().firstOrNull { it.key == key }
            if (rule != null && !rule.enabled) {
                WindowInsetsHelper.log(TAG, "request(): '$key' disabled in config — skipped")
                fireComplete(onComplete); return
            }
            val prefs = PermissionPreferences(activity)
            if (rule?.showOnce == true && prefs.wasShown(key)) {
                fireComplete(onComplete); return
            }

            prefs.markAsked(key)
            if (rule?.showOnce == true) prefs.markShown(key)

            val shortName = spec.androidPermission.substringAfterLast('.')
            activity.recordEvent("Permission_${shortName}_Show")
            WindowInsetsHelper.log(TAG, "request(): asking '$key' on ${activity::class.java.simpleName}")

            PermissionLauncher.launch(activity, spec.androidPermission) { granted ->
                activity.recordPermissionOutcome(spec.androidPermission, granted)
                WindowInsetsHelper.log(TAG, "request(): '$key' granted=$granted")
                fireComplete(onComplete)
            }
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "request() failed", e)
            fireComplete(onComplete)
        }
    }

    /** Drops permissions that don't need requesting right now. */
    private fun isStillNeeded(
        activity: Activity,
        rule: PermissionRule,
        prefs: PermissionPreferences,
    ): Boolean {
        val spec = PermissionUtils.spec(rule.key)
        if (spec == null) {
            WindowInsetsHelper.log(TAG, "Unknown permission key '${rule.key}' — skipped")
            return false
        }
        if (!PermissionUtils.isApplicableOnThisSdk(spec)) return false
        if (!PermissionUtils.isPrefGateOpen(activity, spec)) {
            WindowInsetsHelper.log(TAG, "Pref gate '${spec.enabledPrefGate}' closed — '${rule.key}' skipped")
            return false
        }
        if (PermissionUtils.isGranted(activity, spec)) return false
        if (rule.showOnce && prefs.wasShown(rule.key)) return false
        return true
    }

    private fun processNext(queue: PermissionRequestQueue) {
        val rule = queue.poll() ?: run { complete(); return }
        val spec = PermissionUtils.spec(rule.key) ?: run { processNext(queue); return }

        val activity = activeRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            abort(); return
        }
        // Re-verify — the user may have granted it via Settings meanwhile.
        if (PermissionUtils.isGranted(activity, spec)) {
            processNext(queue); return
        }

        scheduler.schedule(rule.delayMs) {
            val act = activeRef?.get()
            if (act == null || act.isFinishing || act.isDestroyed) {
                abort(); return@schedule
            }
            if (PermissionUtils.isGranted(act, spec)) {
                processNext(queue); return@schedule
            }

            val prefs = PermissionPreferences(act)
            prefs.markAsked(rule.key)
            if (rule.showOnce) prefs.markShown(rule.key)

            // Analytics — mirrors the app's existing permission events
            // (Permission_<NAME>_Show / _Allow / _Deny).
            val shortName = spec.androidPermission.substringAfterLast('.')
            act.recordEvent("Permission_${shortName}_Show")

            WindowInsetsHelper.log(TAG, "Requesting '${rule.key}' on ${act::class.java.simpleName}")
            PermissionLauncher.launch(act, spec.androidPermission) { granted ->
                // Report on the same Activity context that launched the request.
                (activeRef?.get() ?: act).recordPermissionOutcome(spec.androidPermission, granted)
                WindowInsetsHelper.log(TAG, "Result '${rule.key}' granted=$granted")
                // Sequential: only now advance to the next permission.
                processNext(queue)
            }
        }
    }

    /** Normal end of a run — resets state and fires [pendingOnComplete]. */
    private fun complete() {
        scheduler.clear()
        running = false
        activeRef = null
        val cb = pendingOnComplete
        pendingOnComplete = null
        fireComplete(cb)
    }

    /** Interrupted end (teardown / hijack) — resets state, does NOT redirect. */
    private fun abort() {
        scheduler.clear()
        running = false
        activeRef = null
        pendingOnComplete = null
    }

    private fun fireComplete(cb: (() -> Unit)?) {
        if (cb == null) return
        try {
            cb()
        } catch (e: Exception) {
            WindowInsetsHelper.error(TAG, "onComplete callback threw", e)
        }
    }
}
