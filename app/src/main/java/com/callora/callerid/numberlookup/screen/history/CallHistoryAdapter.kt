package com.callora.callerid.numberlookup.screen.history

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.store.CallRecord
import com.callora.callerid.numberlookup.store.CallDirection
import com.callora.callerid.numberlookup.databinding.CellCallBinding
import com.callora.callerid.numberlookup.databinding.CellSectionHeaderBinding
import com.callora.callerid.numberlookup.screen.shared.CallPresenter

class CallHistoryAdapter(
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
            HeaderVH(CellSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            CallVH(CellCallBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRowUi.Header -> (holder as HeaderVH).binding.lblHeader.setText(row.titleRes)
            is HistoryRowUi.Call -> (holder as CallVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: CellSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class CallVH(val binding: CellCallBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRowUi.Call) {
            val e = row.entry
            val ctx = binding.root.context
            val isSpam = e.type == CallDirection.SPAM

            fun color(res: Int) = ContextCompat.getColor(ctx, res)
            fun tint(res: Int) = ColorStateList.valueOf(color(res))

            binding.lblAvatar.text = CallPresenter.initials(e.name, e.number)
            binding.lblName.text = CallPresenter.displayName(e.name, e.number)

            val type = ctx.getString(CallPresenter.typeLabelRes(e.type))
            val time = CallPresenter.timeLabel(e.date)
            val duration = CallPresenter.durationLabel(e.durationSec)
            binding.lblSub.text = buildString {
                append(type).append(" · ").append(time)
                if (duration.isNotEmpty()) append(" · ").append(duration)
            }

            // Verdict-tinted row + avatar (matches the Home list): spam reads red,
            // everything else sits on a neutral card with a primary-container avatar.
            binding.rowCallVw.setBackgroundResource(
                if (isSpam) R.drawable.shape_home_tile_spam else R.drawable.shape_home_tile
            )
            binding.lblAvatar.backgroundTintList =
                tint(if (isSpam) R.color.spam_avatar_bg else R.color.primary_container)
            binding.lblAvatar.setTextColor(
                color(if (isSpam) R.color.spam_on else R.color.on_primary_container)
            )
            binding.lblName.setTextColor(color(if (isSpam) R.color.spam_on else R.color.on_surface))

            // Icon + subtitle colour by verdict/type.
            val subColorRes = when (e.type) {
                CallDirection.MISSED -> R.color.danger
                CallDirection.SPAM -> R.color.spam_on
                else -> R.color.on_surface_variant
            }
            binding.picType.setImageResource(CallPresenter.typeIconRes(e.type))
            binding.picType.imageTintList = tint(subColorRes)
            binding.lblSub.setTextColor(color(subColorRes))

            // Spam → no action; unknown/unsaved → Identify (opens Lookup); else Call.
            val unknown = e.name.isNullOrBlank() && e.number.isNotBlank()
            when {
                isSpam -> {
                    binding.padCall.visibility = View.GONE
                    binding.padIdentify.visibility = View.GONE
                }
                unknown -> {
                    binding.padCall.visibility = View.GONE
                    binding.padIdentify.visibility = View.VISIBLE
                    binding.padIdentify.setOnClickListener { onIdentify(e.number) }
                }
                else -> {
                    binding.padCall.visibility = View.VISIBLE
                    binding.padIdentify.visibility = View.GONE
                    binding.padCall.setOnClickListener { onCall(e.number) }
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
