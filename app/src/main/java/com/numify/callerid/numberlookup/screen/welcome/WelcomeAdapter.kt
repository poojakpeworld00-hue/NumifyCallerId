package com.numify.callerid.numberlookup.screen.welcome

import android.animation.Animator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.numify.callerid.numberlookup.databinding.CellOnboardingBinding

class WelcomeAdapter(
    private val pages: List<WelcomeSlide>
) : RecyclerView.Adapter<WelcomeAdapter.VH>() {

    inner class VH(val binding: CellOnboardingBinding) : RecyclerView.ViewHolder(binding.root) {

        /** Looping animators for the current page's illustration; cancelled on recycle. */
        private val anims = mutableListOf<Animator>()

        fun bind(page: WelcomeSlide) {
            cancelAnims()
            val container = binding.artContainerVw
            container.removeAllViews()

            if (page.customArtRes != 0) {
                LayoutInflater.from(container.context).inflate(page.customArtRes, container, true)
                // Attach the exact per-element loop animations (float, pop, slide,
                // pulse, shield, stamp, sweep, blip) by view id.
                anims += WelcomeAnimations.attach(container)
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

            binding.lblTitle.setText(page.titleRes)
            binding.lblDesc.setText(page.descRes)
        }

        private fun cancelAnims() {
            anims.forEach { it.cancel() }
            anims.clear()
        }

        fun recycle() = cancelAnims()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CellOnboardingBinding.inflate(
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
