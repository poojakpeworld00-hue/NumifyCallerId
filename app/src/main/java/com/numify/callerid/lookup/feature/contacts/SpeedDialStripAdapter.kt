package com.numify.callerid.lookup.feature.contacts

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.databinding.CellContactFavoriteBinding
import com.numify.callerid.lookup.repository.ContactRecord

/**
 * The horizontal strip of starred contacts above the contact list.
 *
 * It shows the same people the "Favorites" tab does, but without costing a tab
 * switch — the tab still exists for when the list itself needs filtering.
 */
class SpeedDialStripAdapter(
    private val onPick: (ContactRecord) -> Unit,
) : RecyclerView.Adapter<SpeedDialStripAdapter.VH>() {

    private var items: List<ContactRecord> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ContactRecord>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(CellContactFavoriteBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class VH(private val binding: CellContactFavoriteBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: ContactRecord) {
            binding.lblFavInitial.text = entry.initials.take(1)
            // First word only; see the layout's note on width.
            binding.lblFavName.text = entry.name.substringBefore(' ')
            binding.favRootVw.setOnClickListener { onPick(entry) }
        }
    }
}
