package com.numify.callerid.lookup.common

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R

/**
 * The hairline the redesign draws between rows inside a grouped card.
 *
 * The handoff puts `border-bottom: 1px solid #F0F2F7` on every row of a card, so
 * the line runs the full width rather than being inset past the leading avatar —
 * an inset rule would cut the card into two columns instead of separating rows.
 *
 * Drawn in `onDrawOver`, not `onDraw`: rows sit flush against each other with an
 * opaque background, so a line drawn underneath them is simply covered.
 *
 * [isHeader] lets a list with section headings skip the boundaries either side of
 * one — a hairline running into a section label reads as an underline on it.
 */
class ListDividerDecoration(
    recyclerView: RecyclerView,
    private val isHeader: (Int) -> Boolean = { false },
) : RecyclerView.ItemDecoration() {

    private val density = recyclerView.resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(recyclerView.context, R.color.ds_divider)
        strokeWidth = 1f * density
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // Child order is attach order, which recycling does not keep in sync with
        // visual order — sort by top so "the next row down" really is that.
        val rows = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .sortedBy { it.top }

        for (i in 0 until rows.size - 1) {
            val row = rows[i]
            val next = rows[i + 1]

            val rowPos = parent.getChildAdapterPosition(row)
            val nextPos = parent.getChildAdapterPosition(next)
            if (rowPos == RecyclerView.NO_POSITION || nextPos == RecyclerView.NO_POSITION) continue
            if (isHeader(rowPos) || isHeader(nextPos)) continue

            val y = (row.bottom + next.top) / 2f
            canvas.drawLine(row.left.toFloat(), y, row.right.toFloat(), y, paint)
        }
    }
}
