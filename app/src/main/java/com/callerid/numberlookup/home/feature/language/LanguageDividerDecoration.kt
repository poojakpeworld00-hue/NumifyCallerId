package com.callerid.numberlookup.home.feature.language

import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.R

/**
 * Hairline separating language rows.
 *
 * It is drawn as a decoration rather than a view inside
 * [R.layout.item_language] so the last row carries no trailing line; a row-level
 * divider would force the adapter to know its own position purely to hide one
 * edge.
 *
 * The redesign runs the line the full width of the card and keeps it around the
 * selected row too: that row is a flat #EAF0FE wash with square edges rather than
 * the rounded pill the previous design used, so a hairline meeting it reads as
 * the group continuing rather than as an artefact.
 */
class LanguageDividerDecoration(
    recyclerView: RecyclerView,
) : RecyclerView.ItemDecoration() {

    private val density = recyclerView.resources.displayMetrics.density

    /**
     * The redesign runs its `border-bottom` the full width of the row, edge to
     * edge inside the card, rather than insetting it past the leading chip — so
     * there is nothing to inset by.
     */
    private val startInsetPx = 0f
    private val endInsetPx = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(recyclerView.context, R.color.ds_divider)
        strokeWidth = 1f * density
    }

    // onDrawOver, not onDraw: the rows now sit flush against each other and paint
    // an opaque background, so a line drawn underneath them is simply covered. The
    // design's border-bottom sits on top of the row, and so does this.
    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // Child order is attach order, which recycling does not keep in sync with
        // visual order — sort by top so "the next row down" really is that.
        val rows = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .sortedBy { it.top }

        for (i in 0 until rows.size - 1) {
            val row = rows[i]
            val next = rows[i + 1]

            // Centre the line in the gap left by the rows' vertical margins.
            val y = (row.bottom + next.top) / 2f
            canvas.drawLine(
                row.left + startInsetPx,
                y,
                row.right - endInsetPx,
                y,
                paint,
            )
        }
    }

}
