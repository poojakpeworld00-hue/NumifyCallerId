package com.numify.callerid.numberlookup.screen.utility

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.numberlookup.databinding.ScreenTimerBinding
import java.util.Locale

/**
 * Countdown timer.
 *
 * A duration is chosen from the presets, then run; the dial shows how much of
 * that duration is left. [totalMs] is kept separately from [remainingMs] because
 * the dial needs the original length to know what fraction has elapsed — with
 * only the remainder there is nothing to draw the arc against.
 */
class CountdownActivity : CoreActivity<ScreenTimerBinding>() {

    override val layoutId: Int = R.layout.screen_timer

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var remainingMs = 0L
    private var totalMs = 0L
    private var endRealtime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.timerRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Sibling tool — the segmented control swaps activities rather than views.
        binding.padSegStopwatch.setOnClickListener {
            startActivity(Intent(this, ChronoActivity::class.java))
            finish()
        }

        // Mid native, scrolls with the tool content.
        NativeAdLoader().renderMidNativeAlt(this, binding.adNativeFrameVw, binding.adShimmerVw)

        buildPresets()
        binding.padStartPause.setOnClickListener { if (running) pause() else start() }
        binding.padReset.setOnClickListener { reset() }
        binding.padAddMin.setOnClickListener { add(60_000) }

        selectPreset(DEFAULT_PRESET_MIN)
    }

    override fun onPause() {
        super.onPause()
        if (running) pause()
    }

    // ─────────────────────────────── presets ───────────────────────────────

    /** Built in code so the chip row stays in step with [PRESET_MINUTES]. */
    private fun buildPresets() {
        val row = binding.presetsVw
        row.removeAllViews()
        PRESET_MINUTES.forEach { minutes ->
            val chip = TextView(this).apply {
                text = getString(R.string.timer_preset_min, minutes)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, chipTextPx())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(chipPadH(), 0, chipPadH(), 0)
                minHeight = chipHeight()
                isClickable = true
                isFocusable = true
                tag = minutes
                setOnClickListener { selectPreset(minutes) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight()
            ).apply { marginEnd = chipGap() }
            row.addView(chip, lp)
        }
    }

    /** Applies [minutes] as the timer's length and repaints the chip row. */
    private fun selectPreset(minutes: Int) {
        if (running) return
        totalMs = minutes * 60_000L
        remainingMs = totalMs
        paintPresets(minutes)
        render()
    }

    private fun paintPresets(selected: Int) {
        val row = binding.presetsVw
        for (i in 0 until row.childCount) {
            val chip = row.getChildAt(i) as TextView
            val isOn = chip.tag == selected
            chip.setBackgroundResource(
                if (isOn) R.drawable.shape_cid_chip_active else R.drawable.shape_cid_chip_outline
            )
            chip.setTextColor(
                ContextCompat.getColor(this, if (isOn) R.color.on_primary else R.color.on_surface)
            )
            // setBackgroundResource wipes padding, so it has to be reapplied.
            chip.setPadding(chipPadH(), 0, chipPadH(), 0)
        }
    }

    // ─────────────────────────────── transport ───────────────────────────────

    private fun add(deltaMs: Long) {
        remainingMs += deltaMs
        totalMs += deltaMs
        if (running) endRealtime += deltaMs
        render()
    }

    private fun start() {
        if (remainingMs <= 0L) return
        running = true
        endRealtime = SystemClock.elapsedRealtime() + remainingMs
        binding.padStartPause.setImageResource(R.drawable.glyph_pause)
        binding.padStartPause.contentDescription = getString(R.string.action_pause)
        setPresetsEnabled(false)
        handler.post(tick)
    }

    private fun pause() {
        running = false
        handler.removeCallbacks(tick)
        remainingMs = (endRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        showStartAffordance()
        render()
    }

    private fun reset() {
        running = false
        handler.removeCallbacks(tick)
        remainingMs = totalMs
        showStartAffordance()
        render()
    }

    private fun showStartAffordance() {
        binding.padStartPause.setImageResource(R.drawable.glyph_play)
        binding.padStartPause.contentDescription = getString(R.string.action_start)
        setPresetsEnabled(true)
    }

    private val tick = object : Runnable {
        override fun run() {
            val remaining = endRealtime - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                remainingMs = 0L
                render()
                onFinished()
            } else {
                remainingMs = remaining
                render()
                handler.postDelayed(this, TICK_MS)
            }
        }
    }

    private fun onFinished() {
        running = false
        showStartAffordance()
        if (binding.swAlert.isChecked) vibrate()
    }

    // ─────────────────────────────── rendering ───────────────────────────────

    private fun render() {
        binding.lblTime.text = format(remainingMs)
        binding.lblTotal.text = getString(R.string.timer_of, format(totalMs))
        // Elapsed, not remaining: the design's arc grows as the timer runs down.
        val elapsed = if (totalMs <= 0L) 0f else (totalMs - remainingMs).toFloat() / totalMs
        binding.ringVw.setProgress(elapsed, animate = running)
    }

    /** Rounds up while counting down, so "1s left" never shows as 00:00. */
    private fun format(ms: Long): String {
        val totalSec = (ms + 999) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    private fun setPresetsEnabled(enabled: Boolean) {
        val row = binding.presetsVw
        for (i in 0 until row.childCount) {
            row.getChildAt(i).isEnabled = enabled
            row.getChildAt(i).alpha = if (enabled) 1f else 0.5f
        }
    }

    private fun vibrate() {
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // ─────────────────────────────── metrics ───────────────────────────────

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun chipHeight() = dp(36f)
    private fun chipPadH() = dp(14f)
    private fun chipGap() = dp(7f)
    private fun chipTextPx() = 13f * resources.displayMetrics.density

    private companion object {
        val PRESET_MINUTES = listOf(1, 3, 5, 10, 30)
        const val DEFAULT_PRESET_MIN = 10

        /** Matches TimerRingView's glide, so the arc never lags the digits. */
        const val TICK_MS = 100L
    }
}
