package com.numify.callerid.lookup.feature.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.databinding.ActivityAiSettingsBinding
import com.numify.callerid.lookup.databinding.ItemAiSettingBinding
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.lookup.repository.SettingsRepository

/**
 * Per-surface controls for Ask AI — screen 9.
 *
 * One switch for each place the assistant appears, rather than a single master
 * switch: someone who wants the verdict while the phone is ringing but no
 * summary afterwards should be able to have exactly that, and an all-or-nothing
 * control makes them turn the whole thing off instead.
 *
 * Reachable from the gear on the Ask AI screen and from the main Settings list.
 * Two entrances is deliberate — the Home switch can hide the first one, and a
 * setting that can hide its own way back is a trap.
 */
class AiSettingsActivity : BaseActivity<ActivityAiSettingsBinding>() {

    override val layoutId: Int = R.layout.activity_ai_settings

    private val settings by lazy { SettingsRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.aiSettingsRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonAiSettingsBack.setOnClickListener { performBack() }

        bindRow(
            binding.rowAiHome,
            R.string.ai_settings_home_title,
            R.string.ai_settings_home_sub,
            settings.aiHomeButtonEnabled
        ) { settings.aiHomeButtonEnabled = it }

        bindRow(
            binding.rowAiVerdict,
            R.string.ai_settings_verdict_title,
            R.string.ai_settings_verdict_sub,
            settings.aiCallerVerdictEnabled
        ) { settings.aiCallerVerdictEnabled = it }

        bindRow(
            binding.rowAiSummary,
            R.string.ai_settings_summary_title,
            R.string.ai_settings_summary_sub,
            settings.aiCallSummaryEnabled
        ) { settings.aiCallSummaryEnabled = it }

        bindRow(
            binding.rowAiSmartReply,
            R.string.ai_settings_reply_title,
            R.string.ai_settings_reply_sub,
            settings.aiSmartReplyEnabled
        ) { settings.aiSmartReplyEnabled = it }

        binding.textAiQueryCount.text = settings.aiQueryCount.toString()
    }

    /**
     * The whole row toggles, not just the switch — a 32dp target inside a 48dp
     * row is the difference between a setting people change and one they miss.
     * The switch itself is not clickable (see the layout) so the two cannot
     * fight over the same tap.
     */
    private fun bindRow(
        row: ItemAiSettingBinding,
        titleRes: Int,
        subtitleRes: Int,
        initial: Boolean,
        onChange: (Boolean) -> Unit
    ) {
        row.textAiSettingTitle.setText(titleRes)
        row.textAiSettingSubtitle.setText(subtitleRes)
        row.switchAiSetting.isChecked = initial
        row.rowAiSettingRoot.setOnClickListener {
            val next = !row.switchAiSetting.isChecked
            row.switchAiSetting.isChecked = next
            onChange(next)
        }
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, AiSettingsActivity::class.java)
    }
}
