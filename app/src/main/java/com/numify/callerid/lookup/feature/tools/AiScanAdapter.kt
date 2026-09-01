package com.numify.callerid.lookup.feature.tools

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ItemAiScanBinding

/**
 * Rows for both assistant tools. The action differs only in label and weight —
 * Block is destructive and tinted, Save is an ordinary outlined button — so one
 * adapter serves both rather than duplicating a list for a one-word change.
 */
class AiScanAdapter(
    private val actionLabel: Int,
    private val destructive: Boolean,
    private val onAction: (AiScanRow) -> Unit
) : RecyclerView.Adapter<AiScanAdapter.VH>() {

    private val items = mutableListOf<AiScanRow>()

    fun submit(rows: List<AiScanRow>) {
        items.clear()
        items += rows
        notifyDataSetChanged()
    }

    /**
     * Drops a row the user has just acted on, so the list reflects the action
     * immediately instead of waiting for a rescan.
     */
    fun remove(row: AiScanRow) {
        val index = items.indexOf(row)
        if (index == -1) return
        items.removeAt(index)
        notifyItemRemoved(index)
    }

    inner class VH(val binding: ItemAiScanBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemAiScanBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        with(holder.binding) {
            textScanNumber.text = row.number
            textScanReason.text = row.reason
            textScanAvatar.text = row.number.filter(Char::isDigit).takeLast(2)
            buttonScanAction.setText(actionLabel)
            buttonScanAction.setBackgroundResource(
                if (destructive) R.drawable.bg_btn_danger else R.drawable.bg_btn_outline
            )
            buttonScanAction.setTextColor(
                root.context.getColor(if (destructive) R.color.on_primary else R.color.on_surface)
            )
            buttonScanAction.setOnClickListener { onAction(row) }
        }
    }

    override fun getItemCount(): Int = items.size
}
