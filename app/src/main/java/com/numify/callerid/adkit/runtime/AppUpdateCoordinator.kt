package com.numify.callerid.adkit.runtime

import android.app.Activity
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.isImmediateUpdateAllowed
import java.lang.ref.WeakReference

/**
 * In-App Update Manager — supports IMMEDIATE (force) and FLEXIBLE update flows.
 * Uses ActivityResultLauncher (no deprecated onActivityResult).
 */
object AppUpdateCoordinator {

    private const val TAG = "AppUpdateCoordinator"

    private var activityRef: WeakReference<Activity>? = null
    private var updateManager: AppUpdateManager? = null
    private var callback: UpdateFlowCallback? = null
    private var updateType: Int = AppUpdateType.IMMEDIATE
    private var updateLauncher: ActivityResultLauncher<IntentSenderRequest>? = null

    /**
     * Call this in onCreate() BEFORE the activity is STARTED.
     * Registers the ActivityResultLauncher.
     */
    fun registerLauncher(activity: ComponentActivity) {
        updateLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    Log.d(TAG, "User accepted the update.")
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        callback?.onUpdateSuccess()
                    }
                    // For FLEXIBLE, success comes via InstallStateUpdatedListener
                }
                Activity.RESULT_CANCELED -> {
                    Log.d(TAG, "User canceled the update.")
                    callback?.onUpdateCanceled()
                    if (updateType == AppUpdateType.IMMEDIATE) {
                        activity.finish()
                    }
                }
                else -> {
                    Log.e(TAG, "Update flow failed, resultCode=${result.resultCode}")
                    callback?.onUpdateFailed()
                }
            }
        }
    }

    /**
     * Initializes and starts checking for updates.
     *
     * @param activity The host activity.
     * @param isForceUpdate true = IMMEDIATE (mandatory), false = FLEXIBLE (optional).
     * @param callback Receives update events.
     */
    fun init(
        activity: Activity,
        isForceUpdate: Boolean = false,
        callback: UpdateFlowCallback
    ) {
        this.activityRef = WeakReference(activity)
        this.callback = callback
        this.updateType = if (isForceUpdate) AppUpdateType.IMMEDIATE else AppUpdateType.FLEXIBLE
        this.updateManager = AppUpdateManagerFactory.create(activity)

        if (updateType == AppUpdateType.FLEXIBLE) {
            updateManager?.registerListener(installStateUpdatedListener)
        }

        checkForUpdates()
    }

    /** Listener for FLEXIBLE update — auto-completes when downloaded. */
    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.d(TAG, "Flexible update downloaded — completing install.")
                updateManager?.completeUpdate()
            }
            InstallStatus.INSTALLED -> {
                Log.d(TAG, "Update installed successfully.")
                callback?.onUpdateSuccess()
                cleanup()
            }
            InstallStatus.FAILED -> {
                Log.e(TAG, "Flexible update failed.")
                callback?.onUpdateFailed()
            }
            else -> {
                Log.d(TAG, "Install status: ${state.installStatus()}")
            }
        }
    }

    /** Checks for updates and starts the flow if available. */
    private fun checkForUpdates() {
        val manager = updateManager ?: return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                val isAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                val isAllowed = when (updateType) {
                    AppUpdateType.FLEXIBLE -> info.isFlexibleUpdateAllowed
                    AppUpdateType.IMMEDIATE -> info.isImmediateUpdateAllowed
                    else -> false
                }

                if (isAvailable && isAllowed) {
                    val launcher = updateLauncher
                    if (launcher != null) {
                        // Modern API — ActivityResultLauncher
                        manager.startUpdateFlowForResult(
                            info,
                            launcher,
                            AppUpdateOptions.newBuilder(updateType).build()
                        )
                    } else {
                        // Fallback — deprecated but works if registerLauncher wasn't called
                        activityRef?.get()?.let { activity ->
                            @Suppress("DEPRECATION")
                            manager.startUpdateFlowForResult(
                                info, updateType, activity, 123
                            )
                        }
                    }
                } else {
                    Log.d(TAG, "No update available or not allowed.")
                    // No update needed — not an error, not a success. Just continue.
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Update check failed: ${e.message}")
                callback?.onUpdateFailed()
            }
    }

    /**
     * Call from onResume() to resume interrupted updates.
     * - IMMEDIATE: resumes the mandatory update screen.
     * - FLEXIBLE: completes install if already downloaded.
     */
    fun resumeUpdate() {
        val manager = updateManager ?: return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (updateType == AppUpdateType.IMMEDIATE &&
                info.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            ) {
                val launcher = updateLauncher
                if (launcher != null) {
                    manager.startUpdateFlowForResult(
                        info, launcher,
                        AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
                    )
                }
            } else if (updateType == AppUpdateType.FLEXIBLE &&
                info.installStatus() == InstallStatus.DOWNLOADED
            ) {
                // Flexible update was downloaded while app was in background — install now
                manager.completeUpdate()
            }
        }
    }

    /** Cleans up references. Call in onDestroy(). */
    fun destroy() {
        cleanup()
        updateLauncher = null
    }

    private fun cleanup() {
        try { updateManager?.unregisterListener(installStateUpdatedListener) } catch (_: Exception) {}
        updateManager = null
        activityRef = null
        callback = null
    }
}

/** Callback interface for update events. */
interface UpdateFlowCallback {
    fun onUpdateSuccess()
    fun onUpdateCanceled()
    fun onUpdateFailed()
}
