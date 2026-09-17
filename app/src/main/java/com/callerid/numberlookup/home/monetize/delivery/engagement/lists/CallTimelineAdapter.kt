package com.callerid.numberlookup.home.monetize.delivery.engagement.lists

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.CallRecord
import com.callerid.numberlookup.home.repository.CallType

/**
 * Recent-call list for the post-call screen.
 *
 * The row opens the number through [onOpen]; only the call button dials it
 * through [onCall]. The whole row used to dial, and dialing here is immediate -
 * ACTION_CALL, no confirmation - on a screen that puts itself in front the
 * moment a call ends, over whatever the user was doing. One stray touch was a
 * real call to a real person. Every other list in the app already splits it this
 * way: the row opens, the button calls.
 */
class CallTimelineAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (CallRecord) -> Unit,
) : RecyclerView.Adapter<CallTimelineAdapter.VH>() {

    private var items: List<CallRecord> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recent_call, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivType: ImageView = itemView.findViewById(R.id.imageType)
        private val tvName: TextView = itemView.findViewById(R.id.textName)
        private val tvNumber: TextView = itemView.findViewById(R.id.textNumber)
        private val btnCall: ImageView = itemView.findViewById(R.id.buttonCall)

        fun bind(entry: CallRecord) {
            val name = entry.name?.takeIf { it.isNotBlank() }
            tvName.text = name ?: entry.number
            tvNumber.text = entry.number
            tvNumber.visibility = if (name == null) View.GONE else View.VISIBLE

            ivType.setImageResource(iconFor(entry.type))
            ivType.setColorFilter(colorFor(entry.type), PorterDuff.Mode.SRC_IN)

            btnCall.setOnClickListener { if (entry.number.isNotBlank()) onCall(entry.number) }
            itemView.setOnClickListener { if (entry.number.isNotBlank()) onOpen(entry) }
        }

        private fun iconFor(type: CallType): Int = when (type) {
            CallType.INCOMING -> R.drawable.ic_call_received
            CallType.OUTGOING -> R.drawable.ic_call_made
            CallType.MISSED, CallType.SPAM -> R.drawable.ic_call_missed
        }

        private fun colorFor(type: CallType): Int = when (type) {
            CallType.MISSED, CallType.SPAM -> Color.parseColor("#D32F2F") // danger
            else -> Color.parseColor("#1565C0")                           // primary
        }
    }
}
