package com.contacts.callerid.number.lookup.feature.blocklist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.contacts.callerid.number.lookup.repository.CallRecord
import com.contacts.callerid.number.lookup.databinding.ItemBlockPickBinding

/** Lists recent call-log numbers so the user can tap one to block it. */
class BlockOptionAdapter(
    private val onPick: (CallRecord) -> Unit
) : RecyclerView.Adapter<BlockOptionAdapter.VH>() {

    private var items: List<CallRecord> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CallRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBlockPickBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: ItemBlockPickBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: CallRecord) {
            val name = entry.name?.takeIf { it.isNotBlank() }
            binding.textName.text = name ?: entry.number
            // Hide the secondary line when there's no name to avoid showing the number twice.
            binding.textNumber.text = entry.number
            binding.textNumber.visibility =
                if (name == null) android.view.View.GONE else android.view.View.VISIBLE
            binding.root.setOnClickListener { onPick(entry) }
        }
    }
}
