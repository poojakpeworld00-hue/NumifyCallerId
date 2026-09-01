package com.numify.callerid.lookup.feature.language

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.R

/**
 * Hairline between language rows.
 *
 * Drawn as a decoration rather than a view in [R.layout.cell_language] so the
 * last row does not carry a trailing line — a row-level divider would need the
 * adapter to know its own position just to hide one edge.
 *
 * The line is inset to where the row's text starts, not the card edge, so it
 * separates the rows without cutting across the flag column. It is also skipped
 * around the selected row: that row draws a filled rounded pill, and a hairline
 * running into its rounded corner reads as a rendering artefact.
 */
class LanguageDividerDecoration(
    recyclerView: RecyclerView,
) : RecyclerView.ItemDecoration() {

    private val density = recyclerView.resources.displayMetrics.density

    /** Row paddingStart (12) + flag chip (30) + text marginStart (10). */
    private val startInsetPx = 52f * density
    private val endInsetPx = 12f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(recyclerView.context, R.color.outline)
        strokeWidth = 1f * density
    }

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        // Child order is attach order, which recycling does not keep in sync with
        // visual order — sort by top so "the next row down" really is that.
        val rows = (0 until parent.childCount)
            .map { parent.getChildAt(it) }
            .sortedBy { it.top }

        for (i in 0 until rows.size - 1) {
            val row = rows[i]
            val next = rows[i + 1]
            if (row.isSelectionPill() || next.isSelectionPill()) continue

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

    /** The row currently drawing the filled "selected" background. */
    private fun View.isSelectionPill() = isActivated
}
