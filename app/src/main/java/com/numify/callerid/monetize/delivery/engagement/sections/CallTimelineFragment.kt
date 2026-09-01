package com.numify.callerid.monetize.delivery.engagement.sections

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.monetize.delivery.engagement.lists.CallTimelineAdapter
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallLogRepository
import com.numify.callerid.monetize.strategy.recordPermissionOutcome

/**
 * Default ("first") tab of the post-call screen: a recent-call list. Each row's
 * call button places a direct outgoing call (ACTION_CALL), falling back to the
 * dialer only when CALL_PHONE isn't granted.
 */
class CallTimelineFragment : Fragment() {

    private val adapter = CallTimelineAdapter(::callNumber)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_recent_calls, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.listRecents)
        val empty = view.findViewById<TextView>(R.id.textEmpty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        val calls = loadRecentCalls()
        adapter.submit(calls)

        val isEmpty = calls.isEmpty()
        recycler.visibility = if (isEmpty) View.GONE else View.VISIBLE
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE

        return view
    }

    /** Reads recent calls (de-duped by number) when call-log access is granted. */
    private fun loadRecentCalls() = try {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) emptyList()
        else CallLogRepository(requireContext()).getCalls(limit = 200)
            .filter { it.number.isNotBlank() && !it.number.equals("Unknown", ignoreCase = true) }
            .distinctBy { it.number }
            .take(30)
    } catch (e: Exception) {
        emptyList()
    }

    private var pendingCallNumber: String? = null

    /** Re-attempts the call (direct or dialer) once the CALL_PHONE prompt returns. */
    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        context?.recordPermissionOutcome(Manifest.permission.CALL_PHONE, granted)
        val number = pendingCallNumber
        pendingCallNumber = null
        if (number != null) if (granted) startDirectCall(number) else openDialer(number)
    }

    /**
     * Places a direct outgoing call (ACTION_CALL) — no dialer. Requests CALL_PHONE
     * first if it isn't granted; falls back to the dialer only if the user denies,
     * so the action never crashes.
     */
    private fun callNumber(number: String) {
        if (!isAdded || number.isBlank()) return
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startDirectCall(number)
        } else {
            pendingCallNumber = number
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun startDirectCall(number: String) {
        val placed = runCatching {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))); true
        }.getOrDefault(false)
        if (!placed) openDialer(number)
    }

    private fun openDialer(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
    }
}
