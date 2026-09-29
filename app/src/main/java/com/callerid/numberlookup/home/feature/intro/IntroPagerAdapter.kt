package com.callerid.numberlookup.home.feature.intro

import android.animation.Animator
import android.content.Context
import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ItemOnboardingBinding
import kotlin.math.roundToInt

class IntroPagerAdapter(
    private val pages: List<IntroSlide>
) : RecyclerView.Adapter<IntroPagerAdapter.VH>() {

    /**
     * Whether a page may play its entrance as soon as it is bound.
     *
     * The first page is bound during the Activity's own layout pass, before there
     * is anything on screen - so its cascade would run out under the launch
     * transition and be over by the time the user is looking. The Activity opens
     * this gate once it has drawn a frame, and [pendingHolder] then plays the page
     * that was waiting. Every later page binds as the user starts dragging towards
     * it, by which point the gate is already open and the entrance runs as the
     * page slides in.
     */
    private var entrancesEnabled = false
    private var pendingHolder: VH? = null

    private var recycler: RecyclerView? = null
    private val attached = mutableSetOf<VH>()
    private var selected = 0

    /** Called by the Activity once the first frame is up. */
    fun enableEntrances() {
        if (entrancesEnabled) return
        entrancesEnabled = true
        pendingHolder?.playEntrance()
        pendingHolder = null
    }

    fun onPageSelected(position: Int) {
        selected = position
    }

    /**
     * At rest only the page in front keeps its animators running: ViewPager2 holds
     * its neighbours bound and attached, and a neighbour's infinite loops ticking
     * behind the page being read is wasted frames. While a swipe is under way
     * every page runs, so the one sliding in is already moving as it arrives -
     * ViewPager2 only names the new page once the swipe has settled, which is too
     * late to start it then.
     */
    fun onScrollStateChanged(idle: Boolean) {
        attached.forEach { it.setLoopsRunning(!idle || it.bindingAdapterPosition == selected) }
    }

    inner class VH(val binding: ItemOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** The page's animators; cancelled on recycle so a half-played page
         *  cannot leave a view stuck at 0 alpha when it is bound again. */
        private val anims = mutableListOf<Animator>()

        /** Set at bind, cleared once the entrance has actually run. */
        private var awaitingEntrance = false

        fun bind(page: IntroSlide) {
            cancelAnims()
            val context = binding.root.context
            val container = binding.artContainer
            container.removeAllViews()
            LayoutInflater.from(context).inflate(page.heroRes, container, true)

            // Short screens drop each card's rows past the second (bool/intro_result_full).
            container.findViewWithTag<View>(TAG_EXTRA_ROW)?.isVisible =
                context.resources.getBoolean(R.bool.intro_result_full)
            sizeHero()

            buildPath(page)
            binding.textTitle.text = buildHeadline(page)
            binding.textDesc.setText(page.descRes)
            binding.root.scrollTo(0, 0)

            IntroAnimations.prepare(container, binding.pathBlock, binding.textTitle, binding.textDesc)
            awaitingEntrance = true

            if (entrancesEnabled) playEntrance() else pendingHolder = this
        }

        /** Runs the page's entrance, then its loops. */
        fun playEntrance() {
            if (!awaitingEntrance) return
            awaitingEntrance = false
            anims += IntroAnimations.play(
                binding.artContainer, binding.pathBlock, binding.textTitle, binding.textDesc
            )
        }

        fun setLoopsRunning(running: Boolean) {
            anims.forEach { if (running) it.resume() else it.pause() }
        }

        /**
         * The hero is 34% of the page (30% on a short screen), held between 220
         * and 300dp. The page is the pager, so that is the RecyclerView's height;
         * a constraint percentage cannot express it, because the page content is
         * inside a scroll view and would be a percentage of itself.
         */
        fun sizeHero() {
            val res = binding.root.resources
            val pageHeight = recycler?.height ?: 0
            val height = if (pageHeight > 0) {
                val percent = ResourcesCompat.getFloat(res, R.dimen.intro_hero_percent)
                (pageHeight * percent).roundToInt().coerceIn(
                    res.getDimensionPixelSize(R.dimen.intro_hero_min),
                    res.getDimensionPixelSize(R.dimen.intro_hero_max),
                )
            } else {
                res.getDimensionPixelSize(R.dimen.intro_hero_min)
            }
            val params = binding.artContainer.layoutParams
            if (params.height != height) {
                params.height = height
                binding.artContainer.layoutParams = params
            }
        }

        /**
         * The "Find it in" row: each step a 26dp chip - white with a hairline, or
         * filled in the page's tint for the step the page is about - with a small
         * chevron between steps and the grey note trailing the last.
         */
        private fun buildPath(page: IntroSlide) {
            val context = binding.root.context
            val row = binding.pathRow
            row.removeAllViews()
            page.path.forEachIndexed { index, step ->
                if (index > 0) row.addView(chevron(context))
                row.addView(chip(context, step, page))
            }
            if (page.pathNoteRes != 0) row.addView(note(context, page.pathNoteRes))
        }

        private fun chip(context: Context, step: PathStep, page: IntroSlide): TextView =
            TextView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                minHeight = dp(context, 26)
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(context, 10), 0, dp(context, 10), 0)
                setText(step.label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = ResourcesCompat.getFont(context, R.font.mulish_bold)
                includeFontPadding = false
                maxLines = 1
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                if (step.highlighted) {
                    setBackgroundResource(R.drawable.bg_intro_r13)
                    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, page.chipTintRes))
                    setTextColor(ContextCompat.getColor(context, page.chipInkRes))
                } else {
                    setBackgroundResource(R.drawable.bg_intro_path_chip)
                    setTextColor(ContextCompat.getColor(context, R.color.ds_ink))
                }
                // A 12dp glyph in the page accent, 5dp before the label.
                val icon = ContextCompat.getDrawable(context, step.icon)?.mutate()?.apply {
                    setBounds(0, 0, dp(context, 12), dp(context, 12))
                }
                setCompoundDrawablesRelative(icon, null, null, null)
                compoundDrawablePadding = dp(context, 5)
                TextViewCompat.setCompoundDrawableTintList(
                    this, ColorStateList.valueOf(ContextCompat.getColor(context, page.accentColorRes))
                )
            }

        /** A 10dp chevron, centred in a 26dp-tall cell so it lines up with the chips. */
        private fun chevron(context: Context): ImageView = ImageView(context).apply {
            layoutParams = ViewGroup.LayoutParams(dp(context, 10), dp(context, 26))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_ds_chevron_right)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.ds_chevron))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            // The glyph points the way the path reads.
            if (layoutDirection == View.LAYOUT_DIRECTION_RTL ||
                context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            ) {
                scaleX = -1f
            }
        }

        private fun note(context: Context, text: Int): TextView = TextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            minHeight = dp(context, 26)
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(context, 4), 0, 0, 0)
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = ResourcesCompat.getFont(context, R.font.mulish_bold)
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(context, R.color.ds_nav_idle))
        }

        /**
         * Joins the headline's two halves with a hard break and colours the second.
         *
         * A span rather than two TextViews: the design's two lines are one text
         * block, so they share a line-height and stay together if the copy grows.
         */
        private fun buildHeadline(page: IntroSlide): CharSequence {
            val ctx = binding.root.context
            val lead = ctx.getString(page.titleLeadRes)
            val accent = ctx.getString(page.titleAccentRes)
            return SpannableStringBuilder(lead).apply {
                append('\n')
                val start = length
                append(accent)
                setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(ctx, page.accentColorRes)),
                    start,
                    length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        private fun cancelAnims() {
            anims.forEach { it.cancel() }
            anims.clear()
            awaitingEntrance = false
            if (pendingHolder === this) pendingHolder = null
            IntroAnimations.reset(binding.pathBlock, binding.textTitle, binding.textDesc)
        }

        fun recycle() = cancelAnims()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        recycler = recyclerView
        // The hero is sized from the page height, which is not known until the
        // pager has been laid out - and changes if the window does.
        recyclerView.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) attached.forEach { it.sizeHero() }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOnboardingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        attached += holder
        holder.sizeHero()
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        attached -= holder
        super.onViewDetachedFromWindow(holder)
    }

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size

    private companion object {
        const val TAG_EXTRA_ROW = "intro_extra"

        fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).roundToInt()
    }
}
