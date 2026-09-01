package com.numify.callerid.lookup.feature.calllog

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.databinding.ItemCallBinding
import com.numify.callerid.lookup.databinding.ItemSectionHeaderBinding
import com.numify.callerid.lookup.feature.widgets.CallActionHandler

class CallLogAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (CallRecord) -> Unit,
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HistoryRowUi> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<HistoryRowUi>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HistoryRowUi.Header) TYPE_HEADER else TYPE_CALL

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            CallVH(ItemCallBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRowUi.Header -> (holder as HeaderVH).binding.textHeader.setText(row.titleRes)
            is HistoryRowUi.Call -> (holder as CallVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class CallVH(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRowUi.Call) {
            val e = row.entry
            val ctx = binding.root.context
            val isSpam = e.type == CallType.SPAM

            fun color(res: Int) = ContextCompat.getColor(ctx, res)
            fun tint(res: Int) = ColorStateList.valueOf(color(res))

            binding.textAvatar.text = CallActionHandler.initials(e.name, e.number)
            binding.textName.text = CallActionHandler.displayName(e.name, e.number)

            val type = ctx.getString(CallActionHandler.typeLabelRes(e.type))
            val time = CallActionHandler.timeLabel(e.date)
            val duration = CallActionHandler.durationLabel(e.durationSec)
            binding.textSub.text = buildString {
                append(type).append(" · ").append(time)
                if (duration.isNotEmpty()) append(" · ").append(duration)
            }

            // Verdict-tinted row + avatar (matches the Home list): spam reads red,
            // everything else sits on a neutral card with a primary-container avatar.
            binding.columnCall.setBackgroundResource(
                if (isSpam) R.drawable.bg_home_tile_spam else R.drawable.bg_home_tile
            )
            binding.textAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            binding.textAvatar.setTextColor(
                color(if (isSpam) R.color.spam_on else R.color.on_primary_container)
            )
            binding.textName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            // Icon + subtitle colour by verdict/type.
            val subColorRes = when (e.type) {
                CallType.MISSED -> R.color.danger
                CallType.SPAM -> R.color.spam_on
                else -> R.color.on_surface_variant
            }
            binding.imageType.setImageResource(CallActionHandler.typeIconRes(e.type))
            binding.imageType.imageTintList = tint(subColorRes)
            binding.textSub.setTextColor(color(subColorRes))

            // Spam → no action; unknown/unsaved → Identify (opens Lookup); else Call.
            val unknown = e.name.isNullOrBlank() && e.number.isNotBlank()
            when {
                isSpam -> {
                    binding.buttonCall.visibility = View.GONE
                    binding.buttonIdentify.visibility = View.GONE
                }
                unknown -> {
                    binding.buttonCall.visibility = View.GONE
                    binding.buttonIdentify.visibility = View.VISIBLE
                    binding.buttonIdentify.setOnClickListener { onIdentify(e.number) }
                }
                else -> {
                    binding.buttonCall.visibility = View.VISIBLE
                    binding.buttonIdentify.visibility = View.GONE
                    binding.buttonCall.setOnClickListener { onCall(e.number) }
                }
            }
            binding.root.setOnClickListener { onOpen(e) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CALL = 1
    }
}
