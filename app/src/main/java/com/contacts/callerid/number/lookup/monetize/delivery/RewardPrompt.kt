package com.contacts.callerid.number.lookup.monetize.delivery

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.contacts.callerid.number.lookup.R
import com.contacts.callerid.number.lookup.databinding.DialogRewardUnlockBinding
import com.contacts.callerid.number.lookup.monetize.strategy.AdPreferenceStore

/**
 * The one rewarded-ad prompt in the app.
 *
 * Every flow that trades an ad for something — revealing a caller name,
 * revealing one "Also known as" name, blocking past the free slots — comes
 * through here. Before this there were two near-identical dialogs and one flow
 * that played the ad with no prompt at all, so the same bargain was offered
 * three different ways and, in one place, not offered.
 *
 * [show] is the whole contract: with ads off it grants immediately and shows
 * nothing; with ads on it runs the dialog, then [RewardedAdPresenter], then the
 * callback once the reward is earned. Cancelling grants nothing.
 */
object RewardPrompt {

    /**
     * What this particular ad buys.
     *
     * [maskedValue] is the thing being withheld — it is shown as its first letter
     * plus one dot per remaining character, never in full, so the user can see
     * there is a real name of a real length behind the mask. Pass null where
     * nothing is hidden (blocking a number reveals nothing) and the mask row is
     * replaced by [subject] alone.
     */
    data class Spec(
        @DrawableRes val glyph: Int,
        val maskedValue: String?,
        val subject: String,
        @StringRes val title: Int,
        @StringRes val message: Int,
        @StringRes val note: Int,
        /** Format arguments for [message], when it is a format string. */
        val messageArgs: List<Any> = emptyList(),
    )

    /** Revealing a caller's full name. */
    fun nameReveal(name: String, number: String) = Spec(
        glyph = R.drawable.ic_ds_eye,
        maskedValue = name,
        subject = number,
        title = R.string.watch_ad_title,
        message = R.string.watch_ad_message,
        note = R.string.reward_note_name,
    )

    /** Blocking a number once the free slots are gone. */
    fun blockUnlock(number: String, freeQuota: Int) = Spec(
        glyph = R.drawable.ic_ds_block_slash,
        maskedValue = null,
        subject = number,
        title = R.string.reward_title_block,
        message = R.string.block_unlock_message,
        note = R.string.reward_note_block,
        messageArgs = listOf(freeQuota),
    )

    /**
     * Runs the prompt for [spec], firing [onGranted] once the reward is earned.
     *
     * Ads off → [onGranted] runs straight away with no dialog, the same rule the
     * rest of the app uses. Cancelling does nothing at all.
     */
    fun show(activity: Activity, spec: Spec, onGranted: () -> Unit) {
        if (!AdPreferenceStore.getInstance(activity).getBoolean("IsAdsON")) {
            onGranted()
            return
        }

        val binding = DialogRewardUnlockBinding.inflate(activity.layoutInflater)
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(binding.root)
            // The dialog's own card draws the surface and the shadow, so the
            // window behind it must be transparent or a second white rectangle
            // shows through its rounded corners.
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        binding.imageRewardGlyph.setImageResource(spec.glyph)
        binding.textRewardTitle.setText(spec.title)
        binding.textRewardMessage.text = if (spec.messageArgs.isEmpty()) {
            activity.getString(spec.message)
        } else {
            activity.getString(spec.message, *spec.messageArgs.toTypedArray())
        }
        binding.textRewardNote.setText(spec.note)
        binding.textRewardSubject.text = spec.subject
        bindMask(binding, spec.maskedValue)

        val animators = startAnimations(binding)
        // Animators outlive the window unless they are stopped with it — an
        // infinite sweep on a detached view keeps a frame callback scheduled for
        // the life of the process.
        dialog.setOnDismissListener { animators.forEach(Animator::cancel) }

        binding.buttonWatchAd.setOnClickListener {
            dialog.dismiss()
            RewardedAdPresenter().show(activity) { onGranted() }
        }
        binding.buttonCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /**
     * Draws the masked value: its first character, then a dot per remaining one.
     *
     * A dot view per character rather than a string of bullets, because the dots
     * pulse in sequence — a run of "•" in a TextView can only fade as one block,
     * which reads as the whole line blinking rather than as something being
     * withheld character by character.
     */
    private fun bindMask(binding: DialogRewardUnlockBinding, value: String?) {
        binding.columnMaskDots.removeAllViews()

        val text = value?.trim().orEmpty()
        if (text.isEmpty()) {
            // Nothing is hidden — the subject moves up into the headline slot and
            // the mask row goes away entirely.
            binding.rowMask.visibility = View.GONE
            binding.textRewardSubject.setTextColor(
                binding.root.context.getColor(R.color.ds_on_hero)
            )
            binding.textRewardSubject.textSize = 19f
            return
        }

        binding.rowMask.visibility = View.VISIBLE
        binding.textMaskInitial.text = text.take(1)

        val context = binding.root.context
        val size = (DOT_SIZE_DP * context.resources.displayMetrics.density).toInt()
        val gap = (DOT_GAP_DP * context.resources.displayMetrics.density).toInt()

        repeat((text.length - 1).coerceAtMost(MAX_DOTS)) { index ->
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = gap
                }
                setBackgroundResource(R.drawable.bg_ds_mask_dot)
                alpha = DOT_ALPHA_MIN
            }
            binding.columnMaskDots.addView(dot)
        }
    }

    /**
     * The sweep across the masked strip and the staggered dot pulse.
     *
     * The sweep is driven off the strip's measured width rather than a fixed
     * distance: the dialog is as wide as the screen allows, so a hard-coded
     * travel either stops short on a tablet or overshoots on a small phone.
     */
    private fun startAnimations(binding: DialogRewardUnlockBinding): List<Animator> {
        val running = mutableListOf<Animator>()

        binding.rewardHero.post {
            val heroWidth = binding.rewardHero.width
            val heroHeight = binding.rewardHero.height
            if (heroWidth <= 0 || heroHeight <= 0) return@post

            val band = binding.viewHeroShimmer
            val params = band.layoutParams
            params.width = (heroWidth * BAND_FRACTION).toInt()
            params.height = heroHeight
            band.layoutParams = params

            val sweep = ObjectAnimator.ofFloat(
                band, View.TRANSLATION_X, -heroWidth * 0.5f, heroWidth.toFloat()
            ).apply {
                duration = SWEEP_MS
                repeatCount = ValueAnimator.INFINITE
            }
            sweep.start()
            running += sweep
        }

        // Each dot lags the one before it, so the pulse travels along the mask.
        val dots = binding.columnMaskDots
        for (index in 0 until dots.childCount) {
            val pulse = ObjectAnimator.ofFloat(
                dots.getChildAt(index), View.ALPHA, DOT_ALPHA_MIN, 1f, DOT_ALPHA_MIN
            ).apply {
                duration = DOT_PULSE_MS
                startDelay = index * DOT_STAGGER_MS
                repeatCount = ValueAnimator.INFINITE
            }
            pulse.start()
            running += pulse
        }

        return running
    }

    private const val DOT_SIZE_DP = 7f
    private const val DOT_GAP_DP = 3f
    private const val DOT_ALPHA_MIN = 0.35f

    /** A long name would otherwise push the number off the strip. */
    private const val MAX_DOTS = 11

    /** The band covers this much of the strip's width. */
    private const val BAND_FRACTION = 0.45f

    private const val SWEEP_MS = 2200L
    private const val DOT_PULSE_MS = 1600L
    private const val DOT_STAGGER_MS = 90L
}
