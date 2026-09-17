package com.callerid.numberlookup.home.feature.finder

import com.callerid.numberlookup.home.R
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.databinding.ItemSearchHistoryBinding
import com.callerid.numberlookup.home.feature.widgets.CallActionHandler

class SearchHistoryAdapter(
    private val onClick: (SearchHistoryEntry) -> Unit,
    private val onCall: (SearchHistoryEntry) -> Unit,
    // Tapping a still-locked name asks the host to gate the reveal behind a
    // rewarded ad; the host calls [revealName] once the reward is earned.
    private val onRevealName: (SearchHistoryEntry) -> Unit = {},
    // Past the free line the eye becomes a lock, and the host is asked for the
    // paywall instead of an ad.
    private val onUpgrade: () -> Unit = {},
) : RecyclerView.Adapter<SearchHistoryAdapter.VH>() {

    private val items = mutableListOf<SearchHistoryEntry>()

    /** rawNumbers whose caller name has been unlocked (rewarded ad watched) this session. */
    private val revealed = mutableSetOf<String>()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<SearchHistoryEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** Un-masks [rawNumber]'s name after its rewarded ad and refreshes that row. */
    fun revealName(rawNumber: String) {
        if (revealed.add(rawNumber)) {
            val i = items.indexOfFirst { it.rawNumber == rawNumber }
            if (i != RecyclerView.NO_POSITION) notifyItemChanged(i)
        }
    }

    /**
     * Re-hides every name (drops all reveals). Call when the page is entered again
     * so names must be re-earned with a fresh rewarded ad each visit.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun resetReveals() {
        if (revealed.isEmpty()) return
        revealed.clear()
        notifyDataSetChanged()
    }

    /** Whether this entry still hides its name behind a rewarded ad. */
    private fun isLocked(item: SearchHistoryEntry): Boolean =
        item.name != null && item.rawNumber !in revealed

    inner class VH(val binding: ItemSearchHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onClick(items[p])
            }
            binding.imageHistCall.setOnClickListener {
                val p = bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onCall(items[p])
            }
            // The name is revealed only via the explicit eye button (rewarded ad) —
            // never by tapping the row/name directly.
            binding.imageHistReveal.setOnClickListener {
                val p = bindingAdapterPosition
                if (p == RecyclerView.NO_POSITION) return@setOnClickListener
                if (NameRevealPolicy.isRevealable(itemView.context, p)) onRevealName(items[p])
                else onUpgrade()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSearchHistoryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            textHistAvatar.text = CallActionHandler.initials(item.name, item.rawNumber)
            val locked = isLocked(item)
            // Locked: blur the name and surface the eye button to unlock it.
            textHistName.text = if (locked) blurName(item.name!!) else (item.name ?: item.number)
            imageHistReveal.visibility = if (locked) View.VISIBLE else View.GONE
            // Same rule as the names on the detail screen: the first few rows
            // offer the eye, the rest wear a lock and lead to Premium. Which one
            // this row shows is the only difference between them.
            imageHistReveal.setImageResource(
                if (NameRevealPolicy.isRevealable(root.context, position)) R.drawable.ic_ds_eye
                else R.drawable.ic_lock
            )
            textHistSub.text = item.subtitle ?: item.number
        }
    }

    override fun getItemCount(): Int = items.size

    /** First letter + dots (e.g. "John" → "J•••"), matching the lookup-card blur. */
    private fun blurName(name: String): String =
        if (name.isNotEmpty()) name[0] + "•".repeat(name.length - 1) else name
}
