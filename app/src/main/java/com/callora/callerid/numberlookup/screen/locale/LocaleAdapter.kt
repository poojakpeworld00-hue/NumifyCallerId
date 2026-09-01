package com.callora.callerid.numberlookup.screen.locale

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callora.callerid.numberlookup.R
import com.callora.callerid.numberlookup.databinding.CellLanguageBinding

class LocaleAdapter(
    private val onClick: (LocaleOption) -> Unit
) : RecyclerView.Adapter<LocaleAdapter.VH>() {

    private val items = mutableListOf<LocaleOption>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LocaleOption>) {
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

    inner class VH(val binding: CellLanguageBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onClick(items[position])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellLanguageBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val selected = item.tag == selectedTag
        with(holder.binding) {
            val ctx = root.context
            lblFlag.text = item.flag
            lblNative.text = item.nativeName
            lblName.text =
                if (item.tag == currentTag) ctx.getString(R.string.language_current)
                else item.name
            // Native name leads; on the selected (primaryContainer) row it takes the
            // on-container color so contrast holds in both light and dark.
            lblNative.setTextColor(
                ContextCompat.getColor(
                    ctx, if (selected) R.color.on_primary_container else R.color.on_surface
                )
            )
            // Selection tint (row) + filled rdo are both driven by activated state.
            root.isActivated = selected
            rdo.isActivated = selected
        }
    }

    override fun getItemCount(): Int = items.size
}
