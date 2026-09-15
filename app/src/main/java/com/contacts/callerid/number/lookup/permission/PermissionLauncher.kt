package com.contacts.callerid.number.lookup.permission

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.contacts.callerid.number.lookup.common.WindowInsetsHelper
import com.contacts.callerid.number.lookup.monetize.strategy.recordPermissionOutcome

/**
 * Issues one runtime-permission request without needing any code in the host
 * Activity.
 *
 * It attaches an invisible [Fragment] to the Activity's FragmentManager - the
 * same well-worn technique libraries such as RxPermissions rely on. That fragment
 * owns a real `registerForActivityResult` launcher, so results arrive reliably
 * and survive configuration changes, and it detaches itself afterwards.
 *
 * Should the host somehow not be a [FragmentActivity], it falls back to
 * [ActivityCompat.requestPermissions] on a best-effort basis; the engine then
 * re-checks the grant state on the next resume.
 */
class PermissionLauncher : Fragment() {

    private var androidPermission: String? = null
    private var onResult: ((Boolean) -> Unit)? = null
    private var launched = false

    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Registration must happen before the fragment is STARTED — onCreate is
        // the correct, lifecycle-safe place.
        launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            androidPermission?.let { requireContext().recordPermissionOutcome(it, granted) }
            val cb = onResult
            onResult = null
            detach()
            cb?.invoke(granted)
        }
    }

    override fun onStart() {
        super.onStart()
        // Fire exactly once, after the registry is fully live.
        val perm = androidPermission
        if (!launched && perm != null) {
            launched = true
            runCatching { launcher.launch(perm) }.onFailure {
                WindowInsetsHelper.error(TAG, "launch() failed for $perm", it)
                val cb = onResult
                onResult = null
                detach()
                cb?.invoke(false)
            }
        }
    }

    private fun detach() {
        val fm = fragmentManagerOrNull() ?: return
        runCatching {
            fm.beginTransaction().remove(this).commitAllowingStateLoss()
        }
    }

    private fun fragmentManagerOrNull() =
        if (isAdded) parentFragmentManager else null

    companion object {
        private const val TAG = "PermissionCoordinator"
        private const val FRAGMENT_TAG = "permission_launcher_fragment"

        /**
         * Requests [androidPermission] against [activity] and reports the grant
         * result on the main thread. Returns false-through-[onResult] if it
         * cannot attach.
         */
        fun launch(
            activity: Activity,
            androidPermission: String,
            onResult: (Boolean) -> Unit,
        ) {
            if (activity is FragmentActivity && !activity.isFinishing && !activity.isDestroyed) {
                val fm = activity.supportFragmentManager
                if (fm.isStateSaved) {
                    // Too late in the lifecycle to commit safely — skip cleanly.
                    WindowInsetsHelper.log(TAG, "State already saved; skipping request for $androidPermission")
                    onResult(false)
                    return
                }
                // Reuse-safe: always a fresh instance per request.
                val fragment = PermissionLauncher().apply {
                    this.androidPermission = androidPermission
                    this.onResult = onResult
                }
                runCatching {
                    fm.beginTransaction()
                        .add(fragment, FRAGMENT_TAG)
                        .commitAllowingStateLoss()
                }.onFailure {
                    WindowInsetsHelper.error(TAG, "Failed to attach PermissionLauncher", it)
                    onResult(false)
                }
            } else {
                // Fallback: classic request; result is observed on next resume.
                WindowInsetsHelper.log(TAG, "Host is not a FragmentActivity; using ActivityCompat fallback")
                runCatching {
                    ActivityCompat.requestPermissions(
                        activity, arrayOf(androidPermission), FALLBACK_REQUEST_CODE
                    )
                }
                onResult(false)
            }
        }

        /** Request code used only by the non-FragmentActivity fallback path. */
        private const val FALLBACK_REQUEST_CODE = 7301
    }
}
