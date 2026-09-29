package com.callerid.numberlookup.home.feature.intro

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.content.withStyledAttributes
import com.callerid.numberlookup.home.R

/**
 * The app's bottom bar, drawn at the intro heroes' miniature scale.
 *
 * Two of the three mini screens end in it - Dialer lit on the lookup page,
 * Contacts on the contacts page - so it is one view with an [active] tab rather
 * than the same four cells written out twice. Sizes are the design's: a 40dp bar
 * with a hairline on top, each cell a 12x2 indicator, a 12dp icon and a 7dp label,
 * 2dp apart. Text is in dp, not sp, like the rest of the miniature: it is a picture
 * of a screen, and letting it follow the user's font scale would break the picture.
 */
class IntroMiniNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density

    private val tabs = listOf(
        R.drawable.ic_ds_nav_dialer to R.string.nav_dialer,
        R.drawable.ic_ds_nav_recents to R.string.nav_recents,
        R.drawable.ic_ds_nav_contacts to R.string.nav_contacts,
        R.drawable.ic_ds_nav_tools to R.string.nav_tools,
    )

    init {
        orientation = HORIZONTAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.ds_surface))
        var active = 0
        context.withStyledAttributes(attrs, R.styleable.IntroMiniNavView) {
            active = getInt(R.styleable.IntroMiniNavView_introNavActive, 0)
        }
        tabs.forEachIndexed { index, (icon, label) -> addView(cell(icon, label, index == active)) }
    }

    /** The design's `border-top:1px solid ds_rule`, drawn over the white fill. */
    private val rule = android.graphics.Paint().apply {
        color = ContextCompat.getColor(context, R.color.ds_rule)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), density, rule)
    }

    private fun cell(icon: Int, label: Int, active: Boolean): View {
        val tint = ContextCompat.getColor(
            context, if (active) R.color.ds_accent else R.color.ds_nav_idle
        )
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)

            addView(View(context).apply {
                layoutParams = LayoutParams(dp(12), dp(2))
                if (active) {
                    background = GradientDrawable().apply {
                        cornerRadius = density
                        setColor(tint)
                    }
                }
            })
            addView(ImageView(context).apply {
                layoutParams = LayoutParams(dp(12), dp(12)).apply { topMargin = dp(2) }
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(tint)
            })
            addView(TextView(context).apply {
                layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(2) }
                setText(label)
                setTextColor(tint)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 7f)
                includeFontPadding = false
                maxLines = 1
                typeface = ResourcesCompat.getFont(
                    context, if (active) R.font.mulish_bold else R.font.mulish_semibold
                )
            })
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
