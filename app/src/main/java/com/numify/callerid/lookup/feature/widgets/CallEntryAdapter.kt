package com.numify.callerid.lookup.feature.widgets

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallLogEntry
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.databinding.CellCallBinding

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

    inner class VH(val binding: CellCallBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellCallBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        val isSpam = item.type == CallType.SPAM

        fun color(res: Int) = ContextCompat.getColor(ctx, res)
        fun tint(res: Int) = ColorStateList.valueOf(color(res))

        with(holder.binding) {
            lblAvatar.text = item.initials
            lblName.text = item.name
            lblSub.text = item.info

            // Verdict container: spam rows read red before the text does; everything
            // else sits on a neutral surface card with a primary-container avatar.
            rowCallVw.setBackgroundResource(
                if (isSpam) R.drawable.shape_home_tile_spam else R.drawable.shape_home_tile
            )
            lblAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            lblAvatar.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_primary_container))
            lblName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            val (iconRes, subColorRes) = when (item.type) {
                CallType.INCOMING -> R.drawable.glyph_call_received to R.color.on_surface_variant
                CallType.OUTGOING -> R.drawable.glyph_call_made to R.color.on_surface_variant
                CallType.MISSED -> R.drawable.glyph_call_missed to R.color.danger
                CallType.SPAM -> R.drawable.glyph_warning to R.color.spam_on
            }
            picType.setImageResource(iconRes)
            picType.imageTintList = tint(subColorRes)
            lblSub.setTextColor(color(subColorRes))

            // Spam → no action (auto-blocked); unknown number → Identify (opens Lookup);
            // otherwise the Call button.
            val unknown = !item.identified && item.number.isNotBlank()
            when {
                isSpam -> {
                    padCall.visibility = android.view.View.GONE
                    padIdentify.visibility = android.view.View.GONE
                }
                unknown -> {
                    padCall.visibility = android.view.View.GONE
                    padIdentify.visibility = android.view.View.VISIBLE
                    padIdentify.setOnClickListener { onIdentify(item.number) }
                }
                else -> {
                    padCall.visibility = android.view.View.VISIBLE
                    padIdentify.visibility = android.view.View.GONE
                    padCall.setOnClickListener { if (item.number.isNotBlank()) onCall(item.number) }
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
