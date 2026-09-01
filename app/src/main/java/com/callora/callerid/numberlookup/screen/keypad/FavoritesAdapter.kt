package com.callora.callerid.numberlookup.screen.keypad

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.callora.callerid.numberlookup.store.FavoriteNumber
import com.callora.callerid.numberlookup.databinding.CellFrequentBinding
import com.callora.callerid.numberlookup.screen.shared.CallPresenter

/** Favorite/most-used contacts shown as horizontal cards. Tapping a card fills the
 *  dialer; the green badge dials. */
class FavoritesAdapter(
    private val onClick: (String) -> Unit,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.VH>() {

    private var items: List<FavoriteNumber> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<FavoriteNumber>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: CellFrequentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellFrequentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val hasName = !item.name.isNullOrBlank()
        holder.binding.lblName.text = CallPresenter.displayName(item.name, item.number)
        holder.binding.lblAvatar.text = CallPresenter.initials(item.name, item.number)
        // Show the number only when the name is the headline (otherwise it'd duplicate).
        holder.binding.lblCount.text = item.number
        holder.binding.lblCount.visibility = if (hasName) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onClick(item.number) }
        holder.binding.padCall.setOnClickListener { onCall(item.number) }
    }

    override fun getItemCount(): Int = items.size
}
