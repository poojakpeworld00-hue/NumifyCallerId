package com.numify.callerid.lookup.feature.blocklist

import android.annotation.SuppressLint
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.repository.BlockedNumber
import com.numify.callerid.lookup.databinding.ItemBlockAttemptBinding
import com.numify.callerid.lookup.databinding.ItemBlocklistBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the blocklist as a flat list of blocked numbers. Every row carries
 * either a red-tinted "spam" treatment or a neutral one, plus an avatar, the
 * number and a direct Unblock pill. Tapping the row opens details, while the pill
 * unblocks in a single step.
 *
 * Each row also reports what the number has tried since it was blocked: a count
 * badge, when it last tried, and — on tap — the individual attempts.
 */
class BlockedNumberAdapter(
    private val onUnblock: (BlockedNumber) -> Unit,
    private val onRowClick: (BlockedNumber) -> Unit,
) : RecyclerView.Adapter<BlockedNumberAdapter.VH>() {

    private var rows: List<BlockedNumberState> = emptyList()
    private var lastAnimated = -1

    /**
     * Numbers whose attempt history is open, keyed by the number rather than by
     * position: a row that is unblocked or re-sorted must not hand its open
     * state to whichever row lands in its place.
     */
    private val expanded = mutableSetOf<String>()

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<BlockedNumberState>) {
        rows = list
        expanded.retainAll(list.map { it.entry.number }.toSet())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBlocklistBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(rows[position])
        animateIn(holder.itemView, position)
    }

    override fun getItemCount(): Int = rows.size

    /** Staggered spring-in the first time each row scrolls into view (40ms cascade). */
    private fun animateIn(view: View, position: Int) {
        if (position <= lastAnimated) return
        lastAnimated = position
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = view.resources.displayMetrics.density * 8f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setStartDelay(position * 40L)
            .setDuration(340L)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    inner class VH(val binding: ItemBlocklistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: BlockedNumberState) {
            val ctx = binding.root.context
            // Number on top, "Blocked · when" under it, as the handoff reads. A
            // contact name takes the title when one resolves; most entries here
            // are numbers nobody has saved, which is why they were blocked.
            binding.textLabel.text = row.label
            binding.textNumber.text =
                ctx.getString(R.string.blocklist_blocked_when, relative(row.entry.addedAt))

            // The row itself no longer changes shape for a spam-flagged entry — the
            // card, the danger disc and the quiet Unblock are the same on every row,
            // because every row here is blocked. Only the title carries the extra
            // signal, which keeps the list scannable instead of striped.
            binding.textLabel.setTextColor(
                ContextCompat.getColor(ctx, if (row.isSpam) R.color.ds_danger else R.color.ds_ink)
            )

            bindAttempts(row)

            binding.buttonUnblock.setOnClickListener { onUnblock(row.entry) }
            binding.blockRow.setOnClickListener { onRowClick(row.entry) }
        }

        /**
         * The count badge, the last-attempt line and the expandable history.
         *
         * A number that has not tried since being blocked shows none of it: an
         * empty history behind a disclosure is a control that does nothing.
         */
        private fun bindAttempts(row: BlockedNumberState) {
            val ctx = binding.root.context
            val count = row.attemptCount

            if (count == 0) {
                binding.badgeAttempts.visibility = View.GONE
                binding.rowLastAttempt.visibility = View.GONE
                binding.columnAttempts.visibility = View.GONE
                return
            }

            binding.badgeAttempts.visibility = View.VISIBLE
            binding.badgeAttempts.text =
                ctx.resources.getQuantityString(R.plurals.blocklist_attempts, count, count)

            binding.rowLastAttempt.visibility = View.VISIBLE
            binding.textLastAttempt.text = ctx.getString(
                R.string.blocklist_last_attempt,
                relative(row.lastAttemptMs ?: 0L)
            )

            val isOpen = row.entry.number in expanded
            binding.columnAttempts.visibility = if (isOpen) View.VISIBLE else View.GONE
            binding.chevronAttempts.rotation = if (isOpen) 180f else 0f

            if (isOpen) fillAttempts(row)

            binding.rowLastAttempt.setOnClickListener {
                val open = row.entry.number in expanded
                if (open) expanded.remove(row.entry.number) else expanded.add(row.entry.number)

                binding.columnAttempts.visibility = if (open) View.GONE else View.VISIBLE
                if (!open) fillAttempts(row)
                binding.chevronAttempts.animate()
                    .rotation(if (open) 0f else 180f)
                    .setDuration(CHEVRON_MS)
                    .start()
            }
        }

        /** Rebuilds the attempt rows. Cheap: this is a handful of views, not a list. */
        private fun fillAttempts(row: BlockedNumberState) {
            val container = binding.columnAttemptRows
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)

            row.attempts.take(MAX_ATTEMPTS_SHOWN).forEach { at ->
                val item = ItemBlockAttemptBinding.inflate(inflater, container, false)
                item.textAttemptDate.text = dayLabel(at)
                item.textAttemptTime.text = timeFormat.format(Date(at))
                container.addView(item.root)
            }
        }

        /** "Today" / "Yesterday" / a short date, as the handoff groups attempts. */
        private fun dayLabel(ms: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
            ms, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_SHOW_DATE
        )

        /** "2h ago" — the coarse form, for the collapsed line. */
        private fun relative(ms: Long): CharSequence = DateUtils.getRelativeTimeSpanString(
            ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private companion object {
        const val CHEVRON_MS = 280L

        /**
         * A persistent nuisance can rack up hundreds of attempts; the point of the
         * list is the pattern, not the archive, and an unbounded one inside a row
         * would stretch the card past the screen.
         */
        const val MAX_ATTEMPTS_SHOWN = 20
    }
}
