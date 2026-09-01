package com.numify.callerid.lookup.feature.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ItemAiAnswerBinding
import com.numify.callerid.lookup.databinding.ItemAiQuestionBinding
import com.numify.callerid.lookup.entity.AiAction
import com.numify.callerid.lookup.entity.AiActionKind
import com.numify.callerid.lookup.entity.AiMessage

/**
 * The Ask AI transcript: questions on the right, answers on the left with their
 * actions and follow-ups underneath.
 *
 * [AiMessage.Thinking] reuses the answer layout with its trays hidden rather
 * than adding a fourth view type — it is the same bubble, just briefly holding
 * a placeholder.
 */
class AiChatAdapter(
    private val onAction: (AiAction) -> Unit,
    private val onFollowUp: (String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AiMessage>()

    fun add(message: AiMessage) {
        items += message
        notifyItemInserted(items.lastIndex)
    }

    /** Swaps the in-flight placeholder for the real answer, in place. */
    fun replaceThinking(answer: AiMessage.Answer) {
        val index = items.indexOfLast { it is AiMessage.Thinking }
        if (index == -1) {
            add(answer)
            return
        }
        items[index] = answer
        notifyItemChanged(index)
    }

    fun isEmpty(): Boolean = items.isEmpty()

    override fun getItemViewType(position: Int): Int =
        if (items[position] is AiMessage.Question) TYPE_QUESTION else TYPE_ANSWER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_QUESTION) {
            QuestionVH(ItemAiQuestionBinding.inflate(inflater, parent, false))
        } else {
            AnswerVH(ItemAiAnswerBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AiMessage.Question -> (holder as QuestionVH).bind(item)
            is AiMessage.Answer -> (holder as AnswerVH).bind(item)
            AiMessage.Thinking -> (holder as AnswerVH).bindThinking()
        }
    }

    override fun getItemCount(): Int = items.size

    // --- holders ------------------------------------------------------------

    inner class QuestionVH(private val b: ItemAiQuestionBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(item: AiMessage.Question) {
            b.textAiQuestion.text = item.text
        }
    }

    inner class AnswerVH(private val b: ItemAiAnswerBinding) : RecyclerView.ViewHolder(b.root) {

        fun bindThinking() {
            b.textAiAnswer.setText(R.string.ai_thinking)
            b.rowAiActions.visibility = View.GONE
            b.rowAiFollowUps.visibility = View.GONE
        }

        fun bind(item: AiMessage.Answer) {
            b.textAiAnswer.text = item.text

            val slots = listOf(b.buttonAiActionOne, b.buttonAiActionTwo, b.buttonAiActionThree)
            slots.forEachIndexed { index, view ->
                val action = item.actions.getOrNull(index)
                if (action == null) {
                    view.visibility = View.GONE
                } else {
                    view.visibility = View.VISIBLE
                    view.setText(labelFor(action.kind))
                    styleFor(view, action.kind)
                    view.setOnClickListener { onAction(action) }
                }
            }
            b.rowAiActions.visibility =
                if (item.actions.isEmpty()) View.GONE else View.VISIBLE

            val chips = listOf(b.buttonAiFollowOne, b.buttonAiFollowTwo)
            chips.forEachIndexed { index, view ->
                val text = item.followUps.getOrNull(index)
                if (text == null) {
                    view.visibility = View.GONE
                } else {
                    view.visibility = View.VISIBLE
                    view.text = text
                    view.setOnClickListener { onFollowUp(text) }
                }
            }
            b.rowAiFollowUps.visibility =
                if (item.followUps.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun labelFor(kind: AiActionKind): Int = when (kind) {
            AiActionKind.BLOCK -> R.string.ai_action_block
            AiActionKind.REPORT -> R.string.ai_action_report
            AiActionKind.MESSAGE -> R.string.ai_action_message
            AiActionKind.CALL -> R.string.ai_action_call
            AiActionKind.DETAILS -> R.string.ai_action_details
        }

        /** Only the destructive action is tinted; the rest stay outlined. */
        private fun styleFor(view: TextView, kind: AiActionKind) {
            val danger = kind == AiActionKind.BLOCK
            view.setBackgroundResource(
                if (danger) R.drawable.bg_btn_danger else R.drawable.bg_btn_outline
            )
            view.setTextColor(
                view.context.getColor(if (danger) R.color.on_primary else R.color.on_surface)
            )
        }
    }

    private companion object {
        const val TYPE_QUESTION = 0
        const val TYPE_ANSWER = 1
    }
}
