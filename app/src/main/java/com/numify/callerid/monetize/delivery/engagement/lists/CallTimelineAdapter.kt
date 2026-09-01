package com.numify.callerid.monetize.delivery.engagement.lists

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType

/**
 * Recent-call list for the post-call screen. Each row shows the caller and a call
 * button, and tapping either the button or the row reports the number back
 * through [onCall], ignoring blank ones, which the host then places as a direct
 * call.
 */
class CallTimelineAdapter(
    private val onCall: (String) -> Unit
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

            val dial = { if (entry.number.isNotBlank()) onCall(entry.number) }
            btnCall.setOnClickListener { dial() }
            itemView.setOnClickListener { dial() }
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
