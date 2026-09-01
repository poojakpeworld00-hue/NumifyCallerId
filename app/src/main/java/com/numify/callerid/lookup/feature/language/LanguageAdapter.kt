package com.numify.callerid.lookup.feature.language

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ItemLanguageBinding

class LanguageAdapter(
    private val onClick: (LanguageOption) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.VH>() {

    private val items = mutableListOf<LanguageOption>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LanguageOption>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setSelected(tag: String) {
        if (tag == selectedTag) return
        selectedTag = tag
        notifyDataSetChanged()
    }

    fun setCurrent(tag: String) {
        currentTag = tag
    }

    inner class VH(val binding: ItemLanguageBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemLanguageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val selected = item.tag == selectedTag
        with(holder.binding) {
            val ctx = root.context
            textFlag.text = item.flag
            textNative.text = item.nativeName
            textName.text =
                if (item.tag == currentTag) ctx.getString(R.string.language_current)
                else item.name
            // Native name leads; on the selected (primaryContainer) row it takes the
            // on-container color so contrast holds in both light and dark.
            textNative.setTextColor(
                ContextCompat.getColor(
                    ctx, if (selected) R.color.on_primary_container else R.color.on_surface
                )
            )
            // Selection tint (row) + filled radio are both driven by activated state.
            root.isActivated = selected
            radio.isActivated = selected
        }
    }

    override fun getItemCount(): Int = items.size
}
