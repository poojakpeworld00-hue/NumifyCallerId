package com.contacts.callerid.number.lookup.feature.language

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.common.RowEntrance
import com.contacts.callerid.number.lookup.databinding.ItemLanguageBinding

class LanguageAdapter(
    private val onClick: (LanguageOption) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.VH>() {

    private val items = mutableListOf<LanguageOption>()
    private var selectedTag: String = ""

    /** The language currently applied; its row shows "Current language". */
    private var currentTag: String = ""

    /**
     * How far into the screen's stagger this list starts, in rows.
     *
     * The design runs one continuous 60ms cascade down the page rather than
     * restarting it per card — its "all languages" rows begin at `60 * (i + 2)`,
     * picking up exactly where the two suggested rows left off. Setting this on
     * the second list reproduces that.
     */
    var staggerOffset: Int = 0

    /** Positions whose entrance has already run, so scrolling back does not replay it. */
    private val entranceShown = mutableSetOf<Int>()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(list: List<LanguageOption>) {
        items.clear()
        items.addAll(list)
        entranceShown.clear()
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

            // Country flag on the language's own colour.
            textChip.text = item.flag
            textChip.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(ctx, item.chipColor))

            textNative.text = item.nativeName
            textName.text =
                if (item.tag == currentTag) ctx.getString(R.string.language_current)
                else item.name

            // On the tinted row both lines shift into the brand ramp, which is what
            // keeps the pair legible against the tinted row instead of only the top line.
            textNative.setTextColor(
                ContextCompat.getColor(ctx, if (selected) R.color.ds_accent else R.color.ds_ink)
            )
            textName.setTextColor(
                ContextCompat.getColor(
                    ctx, if (selected) R.color.ds_accent_on_tint else R.color.ds_ink_muted
                )
            )

            // Row wash and filled checkbox are both driven by activated state.
            root.isActivated = selected
            checkBox.isActivated = selected

            // First time this row is bound it rides in on the cascade; after that it
            // sits still, so scrolling the long list back up does not re-animate it.
            if (entranceShown.add(position)) {
                RowEntrance.play(root, RowEntrance.STAGGER_MS * (position + staggerOffset))
            } else {
                RowEntrance.reset(root)
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        // A recycled row must not carry a half-played entrance into its next use.
        RowEntrance.reset(holder.binding.root)
    }

    override fun getItemCount(): Int = items.size
}
