package com.numify.callerid.lookup.feature.blocklist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.BlockedNumber
import com.numify.callerid.lookup.databinding.ItemBlocklistBinding

/**
 * Renders the blocklist as a flat list of blocked numbers. Each row carries a
 * red-tinted "spam" or a neutral treatment, an avatar, the number and a direct
 * Unblock pill. Tapping the row opens details; the pill unblocks in one step.
 */
class BlockedNumberAdapter(
    private val onUnblock: (BlockedNumber) -> Unit,
    private val onRowClick: (BlockedNumber) -> Unit,
) : RecyclerView.Adapter<BlockedNumberAdapter.VH>() {

    private var rows: List<BlockedNumberState> = emptyList()
    private var lastAnimated = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<BlockedNumberState>) {
        rows = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBlocklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

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

    inner class VH(val binding: ItemBlocklistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BlockedNumberState) {
            val ctx = binding.root.context
            binding.textLabel.text = row.label
            binding.textNumber.text = row.entry.number

            if (row.isSpam) {
                binding.blockRow.setBackgroundResource(R.drawable.bg_row_spam)
                binding.avatarBox.setBackgroundResource(R.drawable.bg_avatar_spam)
                binding.textBang.visibility = View.VISIBLE
                binding.imageAvatar.visibility = View.GONE
                binding.textLabel.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.textNumber.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
                binding.buttonUnblock.setBackgroundResource(R.drawable.bg_unblock_spam)
                binding.buttonUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.spam_on))
            } else {
                binding.blockRow.setBackgroundResource(R.drawable.bg_row_neutral)
                binding.avatarBox.setBackgroundResource(R.drawable.bg_avatar_neutral)
                binding.textBang.visibility = View.GONE
                binding.imageAvatar.visibility = View.VISIBLE
                binding.textLabel.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
                binding.textNumber.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant))
                binding.buttonUnblock.setBackgroundResource(R.drawable.bg_unblock_neutral)
                binding.buttonUnblock.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            }

            binding.buttonUnblock.setOnClickListener { onUnblock(row.entry) }
            binding.blockRow.setOnClickListener { onRowClick(row.entry) }
        }
    }
}
