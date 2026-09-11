package com.numify.callerid.lookup.feature.calllog

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.common.AvatarPalette
import com.numify.callerid.lookup.repository.CallRecord
import com.numify.callerid.lookup.repository.CallType
import com.numify.callerid.lookup.databinding.ItemCallBinding
import com.numify.callerid.lookup.databinding.ItemSectionHeaderBinding
import com.numify.callerid.lookup.feature.widgets.CallActionHandler

class CallLogAdapter(
    private val onCall: (String) -> Unit,
    private val onOpen: (CallRecord) -> Unit,
    private val onIdentify: (String) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var rows: List<HistoryRowUi> = emptyList()

    /** Saved contact pictures by number tail, supplied by CallLogViewModel. */
    private var photos: Map<String, String> = emptyMap()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<HistoryRowUi>) {
        rows = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPhotos(map: Map<String, String>) {
        if (photos == map) return
        photos = map
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is HistoryRowUi.Header) TYPE_HEADER else TYPE_CALL

    /**
     * Whether [position] is a date heading. Read by the divider decoration so a
     * hairline is not drawn against a heading, which is a break in the list
     * rather than another row of it.
     */
    fun isHeader(position: Int): Boolean = rows.getOrNull(position) is HistoryRowUi.Header

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemSectionHeaderBinding.inflate(inflater, parent, false))
        } else {
            CallVH(ItemCallBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is HistoryRowUi.Header -> (holder as HeaderVH).binding.textHeader.setText(row.titleRes)
            is HistoryRowUi.Call -> (holder as CallVH).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    class HeaderVH(val binding: ItemSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class CallVH(val binding: ItemCallBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: HistoryRowUi.Call) {
            val e = row.entry
            val ctx = binding.root.context
            val isSpam = e.type == CallType.SPAM

            fun color(res: Int) = ContextCompat.getColor(ctx, res)
            fun tint(res: Int) = ColorStateList.valueOf(color(res))

            binding.textAvatar.text = CallActionHandler.initials(e.name, e.number)
            binding.textName.text = CallActionHandler.displayName(e.name, e.number)

            val type = ctx.getString(CallActionHandler.typeLabelRes(e.type))
            val time = CallActionHandler.timeLabel(e.date)
            val duration = CallActionHandler.durationLabel(e.durationSec)
            binding.textSub.text = buildString {
                append(type).append(" · ").append(time)
                if (duration.isNotEmpty()) append(" · ").append(duration)
            }

            // Verdict-tinted row + avatar: spam reads red, everything else shows
            // the container's own white through.
            //
            // Both are flat now. The rows sit flush inside one grouped card, so a
            // rounded wash would draw corners mid-list and notch the container's
            // outline; and a row that painted white would hide the hairline the
            // divider decoration draws on top of it.
            if (isSpam) {
                binding.columnCall.setBackgroundResource(R.drawable.bg_ds_row_spam)
            } else {
                binding.columnCall.background = null
            }
            // The avatar takes its own colour from the caller, exactly as the
            // contacts list does, so the same caller keeps the same colour
            // between launches and a scroll through recents is scannable by
            // colour.
            //
            // Keyed off the number rather than the displayed name: the name on a
            // call-log row is whatever the network labelled that particular call,
            // so one caller arrives as "Jayakar New …" on one row and a longer
            // CNAP string on the next, and the same person got two colours.
            //
            // Spam is the exception and keeps the danger red: on this list the
            // avatar is the verdict, and a spam call drawn in a cheerful palette
            // colour would be the one row that most needs to look wrong.
            binding.textAvatar.backgroundTintList = if (isSpam) {
                tint(R.color.ds_danger)
            } else {
                AvatarPalette.tintFor(ctx, avatarKey(e))
            }
            binding.textAvatar.setTextColor(color(R.color.ds_on_accent))
            loadPhoto(e)
            binding.textName.setTextColor(color(if (isSpam) R.color.ds_danger else R.color.ds_ink))

            // Icon + subtitle colour by verdict/type.
            val subColorRes = when (e.type) {
                CallType.MISSED -> R.color.ds_danger
                CallType.SPAM -> R.color.ds_danger
                else -> R.color.ds_ink_muted
            }
            binding.imageType.setImageResource(CallActionHandler.typeIconRes(e.type))
            binding.imageType.imageTintList = tint(subColorRes)
            binding.textSub.setTextColor(color(subColorRes))

            // Spam → no action; unknown/unsaved → Identify (opens Lookup); else Call.
            val unknown = e.name.isNullOrBlank() && e.number.isNotBlank()
            when {
                isSpam -> {
                    binding.buttonCall.visibility = View.GONE
                    binding.buttonIdentify.visibility = View.GONE
                }
                unknown -> {
                    binding.buttonCall.visibility = View.GONE
                    binding.buttonIdentify.visibility = View.VISIBLE
                    binding.buttonIdentify.setOnClickListener { onIdentify(e.number) }
                }
                else -> {
                    binding.buttonCall.visibility = View.VISIBLE
                    binding.buttonIdentify.visibility = View.GONE
                    binding.buttonCall.setOnClickListener { onCall(e.number) }
                }
            }
            binding.root.setOnClickListener { onOpen(e) }
        }

        /**
         * Colour key for a caller: the last [MATCH_DIGITS] digits of the number,
         * so 0912345 6789, +91 98…, and the same number written two ways all land
         * on one swatch. Falls back to the name for a withheld number, which has
         * no digits to key on.
         */
        private fun avatarKey(e: CallRecord): String {
            val tail = e.number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
            return tail.ifEmpty { e.name.orEmpty() }
        }

        /**
         * Lays the saved contact's picture over the coloured initials, exactly as
         * the contacts list does, so a caller you have saved looks like the same
         * person in both places.
         *
         * Cleared explicitly when there is none: a recycled row would otherwise
         * keep the previous caller's face.
         */
        private fun loadPhoto(e: CallRecord) {
            val iv = binding.imageAvatar
            val tail = e.number.filter(Char::isDigit).takeLast(MATCH_DIGITS)
            val uri = photos[tail]

            if (uri.isNullOrBlank()) {
                Glide.with(iv).clear(iv)
                iv.setImageDrawable(null)
                iv.visibility = View.GONE
                return
            }
            iv.visibility = View.VISIBLE
            Glide.with(iv)
                .load(Uri.parse(uri))
                .circleCrop()
                .into(iv)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_CALL = 1

        /** Match numbers on their last N digits, as the rest of the app does. */
        private const val MATCH_DIGITS = 10
    }
}
