package com.callerid.numberlookup.home.monetize.delivery.engagement.sections

import com.callerid.numberlookup.home.feature.widgets.EmptyStateView
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.monetize.delivery.engagement.lists.CallTimelineAdapter
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.CallLogRepository
import com.callerid.numberlookup.home.monetize.strategy.recordPermissionOutcome

/**
 * The default, first tab of the post-call screen: a recent-call list. Each row's
 * call button places a direct outgoing call with ACTION_CALL, falling back to the
 * dialer only where CALL_PHONE has not been granted.
 */
class CallTimelineFragment : Fragment() {

    private val adapter = CallTimelineAdapter(::callNumber)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_recent_calls, container, false)

        val recycler = view.findViewById<RecyclerView>(R.id.listRecents)
        val empty = view.findViewById<EmptyStateView>(R.id.textEmpty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        // No animator: the list is read once and submitted once, so the only thing
        // the default one contributed was a fade that a screenshot could catch
        // half-finished - rows whose names looked washed out or missing.
        recycler.itemAnimator = null
        recycler.adapter = adapter

        val calls = loadRecentCalls()
        adapter.submit(calls)

        val isEmpty = calls.isEmpty()
        if (isEmpty) {
            empty.show(
                R.drawable.ic_history, R.string.recents_empty, R.string.recents_empty_sub
            )
        }
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
     * Places a direct outgoing call with ACTION_CALL, bypassing the dialer. It
     * requests CALL_PHONE first when that is not already granted, and falls back
     * to the dialer only on a refusal, so the action can never crash.
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
