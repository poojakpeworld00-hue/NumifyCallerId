package com.numify.callerid.lookup.feature.dialer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.repository.FavoriteNumber
import com.numify.callerid.lookup.databinding.ItemFrequentBinding
import com.numify.callerid.lookup.feature.widgets.CallActionHandler

/** Favorite/most-used contacts shown as horizontal cards. Tapping a card fills the
 *  dialer; the green badge dials. */
class SpeedDialAdapter(
    private val onClick: (String) -> Unit,
    private val onCall: (String) -> Unit
) : RecyclerView.Adapter<SpeedDialAdapter.VH>() {

    private var items: List<FavoriteNumber> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<FavoriteNumber>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemFrequentBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFrequentBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val hasName = !item.name.isNullOrBlank()
        holder.binding.lblName.text = CallActionHandler.displayName(item.name, item.number)
        holder.binding.lblAvatar.text = CallActionHandler.initials(item.name, item.number)
        // Show the number only when the name is the headline (otherwise it'd duplicate).
        holder.binding.lblCount.text = item.number
        holder.binding.lblCount.visibility = if (hasName) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onClick(item.number) }
        holder.binding.padCall.setOnClickListener { onCall(item.number) }
    }

    override fun getItemCount(): Int = items.size
}
