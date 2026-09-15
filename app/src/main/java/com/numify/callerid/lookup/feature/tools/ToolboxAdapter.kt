package com.numify.callerid.lookup.feature.tools

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.lookup.common.RowEntrance
import com.numify.callerid.lookup.databinding.ItemToolBinding
import com.numify.callerid.lookup.databinding.ItemToolCategoryBinding

/**
 * A single tool tile: a glyph on a softly tinted chip, plus somewhere to launch.
 *
 * [tileRes] tints the chip and [tintRes] colours the glyph. They are two
 * strengths of one hue, so they always travel as a pair.
 *
 * [hint] no longer appears on the card - see item_tool.xml - but it is still what
 * the search box matches against, so typing "north" finds "Compass".
 */
data class UtilityUi(
    val name: String,
    val hint: String,
    val iconRes: Int,
    val tileRes: Int,
    val tintRes: Int,
    val category: String,
    /**
     * The Activity this tile opens, or null for a tile that goes somewhere the
     * shell owns rather than to a screen of its own — Lookup is a tab, not an
     * Activity, so it has no class to name here.
     */
    val target: Class<*>?,
    /**
     * Optional extra for tools that share one Activity. The two assistant tools
     * are the same screen in two modes, so the grid carries the mode rather than
     * the app gaining a near-duplicate Activity for a one-word difference.
     */
    val mode: String? = null,
)

/**
 * One category and the tools under it.
 *
 * The screen is a vertical list of these, each drawing its own horizontal rail —
 * so the outer list is a list of CATEGORIES, not a flattened header/tool stream.
 * The flattened form only existed to feed a GridLayoutManager's span lookup, which
 * a rail does not need.
 */
data class UtilityCategory(
    val title: String,
    val tools: List<UtilityUi>,
)

/**
 * The tools screen: a vertical list of categories, each a horizontally scrolling
 * rail of cards.
 *
 * Every rail shares one [RecyclerView.RecycledViewPool] with the others. Without
 * it each rail keeps its own pool and the screen inflates the same card layout
 * four times over; with it, scrolling one rail hands its cards straight to the
 * next one that needs them.
 */
class ToolboxAdapter(
    private val onClick: (UtilityUi) -> Unit
) : RecyclerView.Adapter<ToolboxAdapter.CategoryVH>() {

    private var categories: List<UtilityCategory> = emptyList()
    private var lastAnimated = -1

    /** Shared across every rail — see the class comment. */
    private val railPool = RecyclerView.RecycledViewPool()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<UtilityCategory>) {
        categories = list
        lastAnimated = -1   // re-cascade after a search filter (fade-through)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryVH =
        CategoryVH(
            ItemToolCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: CategoryVH, position: Int) {
        holder.bind(categories[position])
        animateIn(holder.itemView, position)
    }

    override fun getItemCount(): Int = categories.size

    /** Sections cascade in with a 60ms stagger — the handoff's `.a3` entrance. */
    private fun animateIn(view: View, position: Int) {
        if (position <= lastAnimated) return
        lastAnimated = position
        RowEntrance.play(view, RowEntrance.STAGGER_MS * position)
    }

    inner class CategoryVH(private val binding: ItemToolCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val railAdapter = ToolRailAdapter(onClick)

        init {
            binding.listCategoryTools.setRecycledViewPool(railPool)
            binding.listCategoryTools.adapter = railAdapter
            // The rail scrolls sideways inside a vertically scrolling list. Turning
            // off nested scrolling keeps a horizontal fling from being handed up to
            // the parent and stealing the gesture mid-swipe.
            binding.listCategoryTools.isNestedScrollingEnabled = false
        }

        fun bind(category: UtilityCategory) {
            binding.textToolCategory.text = category.title
            railAdapter.submit(category.tools)
        }
    }
}

/** The cards inside one category's rail. */
class ToolRailAdapter(
    private val onClick: (UtilityUi) -> Unit
) : RecyclerView.Adapter<ToolRailAdapter.ToolVH>() {

    private var tools: List<UtilityUi> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<UtilityUi>) {
        tools = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolVH =
        ToolVH(ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ToolVH, position: Int) = holder.bind(tools[position])

    override fun getItemCount(): Int = tools.size

    inner class ToolVH(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(tool: UtilityUi) {
            binding.imageToolIcon.setBackgroundResource(tool.tileRes)
            binding.imageToolIcon.setImageResource(tool.iconRes)
            // The glyphs are authored white, so the hue has to come from a tint.
            binding.imageToolIcon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(binding.root.context, tool.tintRes)
            )
            binding.textToolTitle.text = tool.name
            binding.toolCard.setOnClickListener {
                springIcon(binding.imageToolIcon)
                onClick(tool)
            }
        }

        /** Tap feedback — icon tile springs 1 → 0.9 → 1. */
        private fun springIcon(view: View) {
            view.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90L)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(160L)
                        .setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
        }
    }
}
