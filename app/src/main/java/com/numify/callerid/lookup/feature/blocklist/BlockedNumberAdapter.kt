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
 * Renders the blocklist as a flat list of blocked numbers. Every row carries
 * either a red-tinted "spam" treatment or a neutral one, plus an avatar, the
 * number and a direct Unblock pill. Tapping the row opens details, while the pill
 * unblocks in a single step.
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

            // The row itself no longer changes shape for a spam-flagged entry — the
            // card, the danger disc and the quiet Unblock are the same on every row,
            // because every row here is blocked. Only the title carries the extra
            // signal, which keeps the list scannable instead of striped.
            binding.textLabel.setTextColor(
                ContextCompat.getColor(ctx, if (row.isSpam) R.color.ds_danger else R.color.ds_ink)
            )

            binding.buttonUnblock.setOnClickListener { onUnblock(row.entry) }
            binding.blockRow.setOnClickListener { onRowClick(row.entry) }
        }
    }
}
