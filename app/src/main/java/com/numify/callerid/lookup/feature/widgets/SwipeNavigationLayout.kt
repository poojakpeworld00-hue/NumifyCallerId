package com.numify.callerid.lookup.feature.widgets

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Container for the Home fragment, adding left/right swipe to change tab.
 *
 * Home holds its five panes with `show`/`hide` instead of a ViewPager2, and that
 * is precisely what preserves each tab's scroll position, ad slot and permission
 * state across switches. Dropping in a pager just to gain swiping would sacrifice
 * all of that and re-run every fragment lifecycle, so the gesture lives here and
 * the hosting model is untouched.
 *
 * The entire difficulty is in *not* stealing horizontal drags a child still
 * wants: the Recents and Contacts panes each carry a `HorizontalScrollView` of
 * filter chips, and Contacts adds a draggable alpha index. Before claiming a
 * gesture this hit-tests whatever sits under the finger and defers to anything
 * that can still scroll that way, so the chips keep behaving and only a drag
 * across inert content switches tab.
 */
class SwipeNavigationLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Fires with `+1` for a swipe to the next tab, `-1` for the previous one. */
    var onSwipe: ((direction: Int) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** A tab change is a deliberate drag, not a stray finger wobble. */
    private val minSwipeDistance = touchSlop * 6

    private var downX = 0f
    private var downY = 0f
    private var claimed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                claimed = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Horizontal must clearly dominate — a 1.5x bias so a diagonal drag
                // on a vertical list stays with the list.
                val horizontal = abs(dx) > touchSlop && abs(dx) > abs(dy) * 1.5f
                if (horizontal) {
                    // Finger dragging left reveals content to the right, hence +1.
                    val scrollDir = if (dx < 0) 1 else -1
                    if (!childCanScroll(this, scrollDir, ev.rawX.toInt(), ev.rawY.toInt())) {
                        claimed = true
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                if (claimed && abs(dx) > minSwipeDistance) {
                    onSwipe?.invoke(if (dx < 0) 1 else -1)
                }
                claimed = false
            }

            MotionEvent.ACTION_CANCEL -> claimed = false
        }
        return true
    }

    /**
     * True when any visible descendant under ([x], [y]) — screen coordinates — can
     * still scroll horizontally in [direction]. Deepest child wins, so a chip strip
     * already at its end releases the gesture and the tab change goes through.
     */
    private fun childCanScroll(group: ViewGroup, direction: Int, x: Int, y: Int): Boolean {
        val location = IntArray(2)
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            child.getLocationOnScreen(location)
            val inside = x >= location[0] && x <= location[0] + child.width &&
                y >= location[1] && y <= location[1] + child.height
            if (!inside) continue

            if (child is ViewGroup && childCanScroll(child, direction, x, y)) return true
            if (child.canScrollHorizontally(direction)) return true
        }
        return false
    }
}
