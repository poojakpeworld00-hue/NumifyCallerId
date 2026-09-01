package com.callora.callerid.numberlookup.screen.spamlist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.store.BlockedNumber
import com.callora.callerid.numberlookup.databinding.CellBlocklistBinding

/**
 * Renders the blocklist as a flat list of blocked numbers. Each row carries a
 * red-tinted "spam" or a neutral treatment, an avatar, the number and a direct
 * Unblock pill. Tapping the row opens details; the pill unblocks in one step.
 */
class SpamListAdapter(
    private val onUnblock: (BlockedNumber) -> Unit,
    private val onRowClick: (BlockedNumber) -> Unit,
) : RecyclerView.Adapter<SpamListAdapter.VH>() {

    private var rows: List<BlockedRowState> = emptyList()
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<BlockedRowState>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellBlocklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
        animateIn(holder.itemView, position)
    }

    override fun getItemCount(): Int = rows.size

    /** Staggered spring-in the first time each row scrolls into view (40ms cascade). */
    private fun animateIn(view: View, position: Int) {
        if (position <= lastAnimated) return
        lastAnimated = position
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = view.resources.displayMetrics.density * 8f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(position * 40L)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    inner class VH(val binding: CellBlocklistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BlockedRowState) {
            val ctx = binding.root.context
            binding.lblLabel.text = row.label
            binding.lblNumber.text = row.entry.number

            if (row.isSpam) {
                binding.blockRowVw.setBackgroundResource(R.drawable.shape_row_spam)
                binding.avatarBoxVw.setBackgroundResource(R.drawable.shape_avatar_spam)
                binding.lblBang.visibility = View.VISIBLE
                binding.picAvatar.visibility = View.GONE
                binding.lblLabel.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.lblNumber.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.padUnblock.setBackgroundResource(R.drawable.shape_unblock_spam)
                binding.padUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
            } else {
                binding.blockRowVw.setBackgroundResource(R.drawable.shape_row_neutral)
                binding.avatarBoxVw.setBackgroundResource(R.drawable.shape_avatar_neutral)
                binding.lblBang.visibility = View.GONE
                binding.picAvatar.visibility = View.VISIBLE
                binding.lblLabel.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                binding.lblNumber.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                binding.padUnblock.setBackgroundResource(R.drawable.shape_unblock_neutral)
                binding.padUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            }

            binding.padUnblock.setOnClickListener { onUnblock(row.entry) }
            binding.blockRowVw.setOnClickListener { onRowClick(row.entry) }
        }
    }
}
