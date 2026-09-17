package com.callerid.numberlookup.home.feature.widgets

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.callerid.numberlookup.home.R

/**
 * What a list shows when it has nothing in it: a tinted disc holding an icon,
 * a line saying what is missing, and a line saying how it gets filled.
 *
 * The screens used to put a single line of grey text in the middle of the
 * viewport. That reads as a rendering failure rather than as a state — there is
 * nothing to tell you whether the list is empty, still loading, or broken, and
 * nothing to tell you what to do about it.
 *
 * The icon drifts up and down and its disc breathes with it. Slow on purpose:
 * this sits behind nothing and competes with nothing, so it is there to say the
 * screen is alive, not to be looked at. Both loops are the same shape the rest
 * of the app uses for idle motion — a half cycle on REVERSE with an ease-in-out
 * curve — and both stop when the view is detached or hidden, so an empty list
 * scrolled out of sight costs nothing.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val disc: ImageView
    private val title: TextView
    private val subtitle: TextView
    private val loops = mutableListOf<ValueAnimator>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(32), dp(24), dp(32), dp(24))

        disc = ImageView(context).apply {
            layoutParams = LayoutParams(dp(88), dp(88))
            background = ContextCompat.getDrawable(context, R.drawable.bg_ds_hero_avatar)
            backgroundTintList = ContextCompat.getColorStateList(context, R.color.ds_hero_blue_wash)
            setPadding(dp(26), dp(26), dp(26), dp(26))
            imageTintList = ContextCompat.getColorStateList(context, R.color.ds_accent)
            contentDescription = null
        }
        addView(disc)

        title = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(18) }
            gravity = Gravity.CENTER
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.mulish_bold)
            includeFontPadding = false
            setTextColor(ContextCompat.getColor(context, R.color.ds_ink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        addView(title)

        subtitle = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
            gravity = Gravity.CENTER
            typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.mulish_semibold)
            includeFontPadding = false
            setLineSpacing(0f, 1.35f)
            setTextColor(ContextCompat.getColor(context, R.color.ds_ink_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        addView(subtitle)
    }

    /** Sets what this empty list is, and how it stops being empty. */
    fun show(@DrawableRes icon: Int, @StringRes titleRes: Int, @StringRes subtitleRes: Int) {
        disc.setImageResource(icon)
        title.setText(titleRes)
        subtitle.setText(subtitleRes)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    private fun start() {
        if (loops.isNotEmpty() || visibility != VISIBLE) return
        val curve = AnimationUtils.loadInterpolator(context, R.interpolator.premium_ease_in_out)

        loops += ObjectAnimator.ofFloat(disc, TRANSLATION_Y, 0f, -dp(6).toFloat()).apply {
            duration = FLOAT_CYCLE_MS / 2
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = curve
            start()
        }
        // The disc breathes with the icon rather than against it: same cycle,
        // same curve, so the two read as one object and not as two animations.
        for (property in listOf(SCALE_X, SCALE_Y)) {
            loops += ObjectAnimator.ofFloat(disc, property, 1f, BREATHE_SCALE).apply {
                duration = FLOAT_CYCLE_MS / 2
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = curve
                start()
            }
        }
    }

    private fun stop() {
        loops.forEach { it.cancel() }
        loops.clear()
        disc.translationY = 0f
        disc.scaleX = 1f
        disc.scaleY = 1f
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    private companion object {
        const val FLOAT_CYCLE_MS = 2_800L
        const val BREATHE_SCALE = 1.04f
    }
}
