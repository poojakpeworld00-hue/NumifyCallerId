package com.callerid.numberlookup.home.monetize.delivery.engagement.lists

import com.callerid.numberlookup.home.common.TimeFormats
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.monetize.model.AlertEntry
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.common.triggerClick
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlertFeedAdapter(
    private val list: List<AlertEntry>,
    private val onDelete: (AlertEntry) -> Unit
) :
    RecyclerView.Adapter<AlertFeedAdapter.ReminderViewHolder>() {

    class ReminderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.textTitle)
        val time: TextView = itemView.findViewById(R.id.textTime)
        val delete: ImageView = itemView.findViewById(R.id.imageDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReminderViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alert_entry, parent, false)
        return ReminderViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReminderViewHolder, position: Int) {
        val reminder = list[position]
        holder.title.text = reminder.title
        val day = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(reminder.dateTime))
        holder.time.text = "$day, " + TimeFormats.clock(holder.itemView.context, reminder.dateTime)
        holder.delete.triggerClick {
            onDelete(reminder)
        }
    }

    override fun getItemCount() = list.size
}
