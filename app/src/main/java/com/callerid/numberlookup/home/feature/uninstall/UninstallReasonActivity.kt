package com.callerid.numberlookup.home.feature.uninstall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.addCallback
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.databinding.ActivityUninstallReasonBinding
import com.callerid.numberlookup.home.databinding.ItemUninstallReasonBinding
import com.callerid.numberlookup.home.feature.overlay.OverlayAskPolicy
import com.callerid.numberlookup.home.feature.overlay.OverlayPermissionUtils
import com.callerid.numberlookup.home.feature.premium.PremiumActivity
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.feature.premium.PaywallConfig
import com.callerid.numberlookup.home.monetize.delivery.AppOpenAdManager
import com.callerid.numberlookup.home.monetize.strategy.ScreenPlacementPlan
import com.callerid.numberlookup.home.monetize.strategy.recordEvent

/**
 * The exit survey. The first reason is preselected so Uninstall always has
 * something to log. When something in the app would solve the picked reason, a
 * tip under the list offers it in one tap:
 *
 *  - Caller ID doesn't show names → grant "Display over apps"
 *  - Too many pop-ups or notifications → the system notification settings
 *  - Too many ads → Premium
 *
 * "Keep app" leads; Uninstall and back both continue to the thank-you screen.
 */
class UninstallReasonActivity : BaseActivity<ActivityUninstallReasonBinding>() {

    override val screenKey: String get() = "UninstallReasonActivity"

    override val layoutId: Int = R.layout.activity_uninstall_reason

    private class Tip(
        @param:StringRes val title: Int,
        @param:StringRes val text: Int,
        @param:StringRes val button: Int,
        val event: String,
        /** Offered only while it would actually change something. */
        val applies: (Context) -> Boolean,
        val action: (UninstallReasonActivity) -> Unit,
    )

    private class Reason(
        @param:StringRes val label: Int,
        @param:DrawableRes val icon: Int,
        @param:ColorRes val tileBg: Int,
        @param:ColorRes val tileFg: Int,
        val tip: Tip? = null,
    )

    private val reasons = listOf(
        Reason(
            R.string.un_reason_1, R.drawable.ic_un_g_person_off, R.color.un_tile_red_bg, R.color.un_tile_red_fg,
            Tip(
                R.string.un_tip_overlay_title, R.string.un_tip_overlay_text, R.string.un_tip_overlay_btn, "overlay",
                applies = { !OverlayPermissionUtils.isGranted(it) && OverlayAskPolicy.isAskingAllowed(it) },
                action = { it.openOverlaySettings() },
            ),
        ),
        Reason(
            R.string.un_reason_2, R.drawable.ic_un_g_notifications_off, R.color.un_tile_amber_bg, R.color.un_tile_amber_fg,
            Tip(
                R.string.un_tip_notif_title, R.string.un_tip_notif_text, R.string.un_tip_notif_btn, "notifications",
                applies = { true },
                action = { it.openNotificationSettings() },
            ),
        ),
        Reason(R.string.un_reason_3, R.drawable.ic_un_g_hourglass, R.color.un_tile_grey_bg, R.color.un_tile_grey_fg),
        Reason(
            R.string.un_reason_4, R.drawable.ic_un_g_block, R.color.un_tile_purple_bg, R.color.un_tile_purple_fg,
            Tip(
                R.string.un_tip_premium_title, R.string.un_tip_premium_text, R.string.un_tip_premium_btn, "premium",
                applies = { PaywallConfig.isOffered(it) },
                action = { it.startActivity(PremiumActivity.newIntent(it)) },
            ),
        ),
        Reason(R.string.un_reason_5, R.drawable.ic_un_g_battery_alert, R.color.un_tile_green_bg, R.color.un_tile_green_fg),
        Reason(R.string.un_reason_6, R.drawable.ic_un_g_swap, R.color.un_tile_blue_bg, R.color.un_tile_blue_fg),
    )

    private val advanced get() = intent.getBooleanExtra(UninstallFlow.EXTRA_ADVANCED, false)
    private val rows = mutableListOf<ItemUninstallReasonBinding>()
    private var selected = 0
    private var shownTip: Tip? = null

    override fun initView() {
        UninstallFlow.applyInsets(binding.root)
        reasons.forEachIndexed { i, reason ->
            val row = ItemUninstallReasonBinding.inflate(layoutInflater, binding.reasonList, false)
            row.ivIcon.setImageResource(reason.icon)
            row.ivIcon.backgroundTintList = ContextCompat.getColorStateList(this, reason.tileBg)
            row.ivIcon.imageTintList = ContextCompat.getColorStateList(this, reason.tileFg)
            row.tvLabel.setText(reason.label)
            row.root.setOnClickListener { select(i) }
            binding.reasonList.addView(row.root)
            rows += row
        }
        paintSelection(animate = false)
        ScreenPlacementPlan.showAd(screenKey, this, binding.adNativeFrame, binding.adShimmer)

        binding.ivBack.setOnClickListener { finish() }
        binding.btnKeepApp.setOnClickListener {
            recordEvent("uninstall_reason_keep")
            UninstallFlow.keepApp(this)
        }
        binding.btnUninstall.setOnClickListener { next() }
        binding.btnTip.setOnClickListener {
            val tip = shownTip ?: return@setOnClickListener
            recordEvent("uninstall_tip_${tip.event}")
            tip.action(this)
        }
        onBackPressedDispatcher.addCallback(this) { next() }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings or the paywall, the tip may be moot (overlay granted,
        // Premium bought).
        if (rows.isNotEmpty()) updateTip()
    }

    private fun select(index: Int) {
        if (index == selected) return
        selected = index
        paintSelection(animate = true)
    }

    private fun paintSelection(animate: Boolean) {
        rows.forEachIndexed { i, row ->
            val on = i == selected
            row.root.isSelected = on
            row.tvLabel.typeface =
                ResourcesCompat.getFont(this, if (on) R.font.mulish_bold else R.font.mulish_semibold)
            val dot = row.radioDot
            if (on) {
                dot.visibility = View.VISIBLE
                if (animate) {
                    dot.scaleX = 0f
                    dot.scaleY = 0f
                    dot.animate().scaleX(1f).scaleY(1f).setDuration(250)
                        .setInterpolator(OvershootInterpolator(2f)).start()
                }
            } else {
                dot.visibility = View.INVISIBLE
            }
        }
        updateTip(animate)
    }

    private fun updateTip(animate: Boolean = false) {
        val tip = reasons[selected].tip?.takeIf { it.applies(this) }
        val card = binding.tipCard
        if (tip == null) {
            shownTip = null
            card.visibility = View.GONE
            return
        }
        val changed = tip !== shownTip
        shownTip = tip
        binding.tvTip.text = SpannableStringBuilder().apply {
            append(getString(tip.title), StyleSpan(android.graphics.Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(" ")
            append(getString(tip.text))
        }
        binding.btnTip.setText(tip.button)
        card.visibility = View.VISIBLE
        if (animate && changed) {
            card.alpha = 0f
            card.translationY = 10f * resources.displayMetrics.density
            card.animate().alpha(1f).translationY(0f).setDuration(300)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun openOverlaySettings() {
        AppOpenAdManager.skipNextAppOpenAd = true
        runCatching { startActivity(OverlayPermissionUtils.buildOverlayIntent(packageName)) }
            .onFailure { AppOpenAdManager.skipNextAppOpenAd = false }
    }

    private fun openNotificationSettings() {
        AppOpenAdManager.skipNextAppOpenAd = true
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
        }
        runCatching { startActivity(intent) }
            .onFailure { AppOpenAdManager.skipNextAppOpenAd = false }
    }

    private fun next() {
        recordEvent("uninstall_reason_${selected + 1}")
        startActivity(
            UninstallThanksActivity.newIntent(this, advanced, getString(reasons[selected].label)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    companion object {
        fun newIntent(ctx: Context, advanced: Boolean): Intent =
            Intent(ctx, UninstallReasonActivity::class.java)
                .putExtra(UninstallFlow.EXTRA_ADVANCED, advanced)
    }
}
