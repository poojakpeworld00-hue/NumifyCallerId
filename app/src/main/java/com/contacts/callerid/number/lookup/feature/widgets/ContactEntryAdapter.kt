package com.contacts.callerid.number.lookup.feature.widgets

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.contacts.callerid.number.lookup.repository.ContactRecord
import com.contacts.callerid.number.lookup.databinding.ItemContactBinding

class ContactEntryAdapter(
    private val items: List<ContactRecord>
) : RecyclerView.Adapter<ContactEntryAdapter.VH>() {

    inner class VH(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            textAvatar.text = item.initials
            textName.text = item.name
            textNumber.text = item.detail
        }
    }

    override fun getItemCount(): Int = items.size
}
