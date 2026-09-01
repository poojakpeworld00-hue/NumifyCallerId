package com.numify.callerid.monetize.delivery.engagement.lists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.monetize.model.AlertEntry
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.common.triggerClick
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
        val format = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        holder.time.text = format.format(Date(reminder.dateTime))
        holder.delete.triggerClick {
            onDelete(reminder)
        }
    }

    override fun getItemCount() = list.size
}
