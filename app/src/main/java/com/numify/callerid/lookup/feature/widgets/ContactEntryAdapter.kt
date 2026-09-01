package com.numify.callerid.lookup.feature.widgets

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.repository.ContactRecord
import com.numify.callerid.lookup.databinding.CellContactBinding

class ContactEntryAdapter(
    private val items: List<ContactRecord>
) : RecyclerView.Adapter<ContactEntryAdapter.VH>() {

    inner class VH(val binding: CellContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            lblAvatar.text = item.initials
            lblName.text = item.name
            lblNumber.text = item.detail
        }
    }

    override fun getItemCount(): Int = items.size
}
