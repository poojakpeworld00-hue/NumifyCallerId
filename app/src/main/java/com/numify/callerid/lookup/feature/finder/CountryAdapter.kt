package com.numify.callerid.lookup.feature.finder

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.databinding.ItemCountryBinding

class CountryAdapter(
    private val onClick: (CountryItem) -> Unit
) : RecyclerView.Adapter<CountryAdapter.VH>() {

    private val items = mutableListOf<CountryItem>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<CountryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemCountryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCountryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = items[position]
        with(holder.binding) {
            lblFlag.text = CountryCatalog.flag(c.iso2)
            lblName.text = c.name
            lblDial.text = "+${c.dial}"
        }
    }

    override fun getItemCount(): Int = items.size
}
