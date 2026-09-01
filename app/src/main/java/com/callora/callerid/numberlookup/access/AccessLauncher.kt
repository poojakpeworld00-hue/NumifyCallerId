package com.callora.callerid.numberlookup.access

import android.app.Activity
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.callora.callerid.numberlookup.kit.EdgeInsets

/**
 * Performs a single runtime-permission request without requiring any code
 * inside the host Activity.
 *
 * It works by attaching an invisible [Fragment] to the Activity's
 * FragmentManager (the same battle-tested approach used by libraries like
 * RxPermissions). The fragment owns a proper `registerForActivityResult`
 * launcher, so results are delivered reliably and survive configuration
 * changes, then it detaches itself.
 *
 * If the host is somehow not a [FragmentActivity], it falls back to
 * [ActivityCompat.requestPermissions] (best-effort — the grant state is then
 * re-checked by the engine on the next resume).
 */
class AccessLauncher : Fragment() {

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
                EdgeInsets.error(TAG, "launch() failed for $perm", it)
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
        private const val TAG = "AccessEngine"
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
                    EdgeInsets.log(TAG, "State already saved; skipping request for $androidPermission")
                    onResult(false)
                    return
                }
                // Reuse-safe: always a fresh instance per request.
                val fragment = AccessLauncher().apply {
                    this.androidPermission = androidPermission
                    this.onResult = onResult
                }
                runCatching {
                    fm.beginTransaction()
                        .add(fragment, FRAGMENT_TAG)
                        .commitAllowingStateLoss()
                }.onFailure {
                    EdgeInsets.error(TAG, "Failed to attach AccessLauncher", it)
                    onResult(false)
                }
            } else {
                // Fallback: classic request; result is observed on next resume.
                EdgeInsets.log(TAG, "Host is not a FragmentActivity; using ActivityCompat fallback")
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
