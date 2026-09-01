package com.numify.callerid.lookup.feature.assistant

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.databinding.ItemAiSuggestionBinding
import com.numify.callerid.lookup.entity.AiSuggestion

/** The ranked starter chips on the empty hub. */
class AiSuggestionAdapter(
    private val onPick: (AiSuggestion) -> Unit
) : RecyclerView.Adapter<AiSuggestionAdapter.VH>() {

    private var items: List<AiSuggestion> = emptyList()

    fun submit(list: List<AiSuggestion>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(val binding: ItemAiSuggestionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAiSuggestionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        with(holder.binding) {
            textAiSuggestion.text = item.label
            // Activated drives the tinted state in bg_ai_suggestion, so the
            // context-derived chip reads ahead of the generic backfill.
            textAiSuggestion.isActivated = item.highlighted
            textAiSuggestion.setOnClickListener { onPick(item) }
        }
    }

    override fun getItemCount(): Int = items.size
}
