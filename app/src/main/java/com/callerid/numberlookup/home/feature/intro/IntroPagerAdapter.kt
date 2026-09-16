package com.callerid.numberlookup.home.feature.intro

import android.animation.Animator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.callerid.numberlookup.home.databinding.ItemOnboardingBinding

class IntroPagerAdapter(
    private val pages: List<IntroSlide>
) : RecyclerView.Adapter<IntroPagerAdapter.VH>() {

    /**
     * Whether a page may play its entrance as soon as it is bound.
     *
     * The first page is bound during the Activity's own layout pass, before there
     * is anything on screen — so its cascade would run out under the launch
     * transition and be over by the time the user is looking. The Activity opens
     * this gate once it has drawn a frame, and [pendingHolder] then plays the page
     * that was waiting. Every later page binds as the user starts dragging towards
     * it, by which point the gate is already open and the entrance runs as the
     * page slides in — which is what the design shows.
     */
    private var entrancesEnabled = false
    private var pendingHolder: VH? = null

    /** Called by the Activity once the first frame is up. */
    fun enableEntrances() {
        if (entrancesEnabled) return
        entrancesEnabled = true
        pendingHolder?.playEntrance()
        pendingHolder = null
    }

    inner class VH(val binding: ItemOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** The page's entrance animators; cancelled on recycle so a half-played
         *  page cannot leave a view stuck at 0 alpha when it is bound again. */
        private val anims = mutableListOf<Animator>()

        /** Set at bind, cleared once the entrance has actually run. */
        private var awaitingEntrance = false

        fun bind(page: IntroSlide) {
            cancelAnims()
            val container = binding.artContainer
            container.removeAllViews()

            if (page.customArtRes != 0) {
                LayoutInflater.from(container.context).inflate(page.customArtRes, container, true)
            } else {
                val image = ImageView(container.context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(page.artRes)
                }
                container.addView(image)
            }

            binding.textTitle.text = buildHeadline(page)
            binding.textDesc.setText(page.descRes)

            // Park everything at the start of its keyframe straight away, so a page
            // that is bound but not yet playing never flashes in fully formed.
            IntroAnimations.prepare(container, binding.textTitle, binding.textDesc)
            awaitingEntrance = true

            if (entrancesEnabled) playEntrance() else pendingHolder = this
        }

        /** Runs the page's cascade: the hero's own sequence, then the headline. */
        fun playEntrance() {
            if (!awaitingEntrance) return
            awaitingEntrance = false
            anims += IntroAnimations.attachHero(binding.artContainer)
            anims += IntroAnimations.headlineIn(binding.textTitle, binding.textDesc)
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
            IntroAnimations.reset(binding.textTitle, binding.textDesc)
        }

        fun recycle() = cancelAnims()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemOnboardingBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])

    override fun onViewRecycled(holder: VH) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
}
