package com.callerid.numberlookup.home.feature.widgets

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.repository.CallLogEntry
import com.callerid.numberlookup.home.repository.CallType
import com.callerid.numberlookup.home.databinding.ItemCallBinding

class CallEntryAdapter(
    initial: List<CallLogEntry> = emptyList(),
    private val onCall: (String) -> Unit = {},
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<CallEntryAdapter.VH>() {

    private var items: List<CallLogEntry> = initial

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallLogEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val isSpam = item.type == CallType.SPAM

        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        fun tint(res: Int) = ColorStateList.valueOf(color(res))

        with(holder.binding) {
            textAvatar.text = item.initials
            textName.text = item.name
            textSub.text = item.info

            // Verdict container: spam rows read red before the text does; everything
            // else sits on a neutral surface card with a primary-container avatar.
            columnCall.setBackgroundResource(
                if (isSpam) R.drawable.bg_home_tile_spam else R.drawable.bg_home_tile
            )
            textAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            textAvatar.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_primary_container))
            textName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            val (iconRes, subColorRes) = when (item.type) {
                CallType.INCOMING -> R.drawable.ic_call_received to R.color.on_surface_variant
                CallType.OUTGOING -> R.drawable.ic_call_made to R.color.on_surface_variant
                CallType.MISSED -> R.drawable.ic_call_missed to R.color.danger
                CallType.SPAM -> R.drawable.ic_warning to R.color.spam_on
            }
            imageType.setImageResource(iconRes)
            imageType.imageTintList = tint(subColorRes)
            textSub.setTextColor(color(subColorRes))

            // Spam → no action (auto-blocked); unknown number → Identify (opens Lookup);
            // otherwise the Call button.
            val unknown = !item.identified && item.number.isNotBlank()
            when {
                isSpam -> {
                    buttonCall.visibility = android.view.View.GONE
                    buttonIdentify.visibility = android.view.View.GONE
                }
                unknown -> {
                    buttonCall.visibility = android.view.View.GONE
                    buttonIdentify.visibility = android.view.View.VISIBLE
                    buttonIdentify.setOnClickListener { onIdentify(item.number) }
                }
                else -> {
                    buttonCall.visibility = android.view.View.VISIBLE
                    buttonIdentify.visibility = android.view.View.GONE
                    buttonCall.setOnClickListener { if (item.number.isNotBlank()) onCall(item.number) }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
