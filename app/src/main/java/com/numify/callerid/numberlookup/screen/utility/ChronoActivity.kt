package com.numify.callerid.numberlookup.screen.utility

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.CoreActivity
import com.numify.callerid.adkit.runtime.NativeAdLoader
import com.numify.callerid.numberlookup.databinding.ScreenStopwatchBinding
import com.numify.callerid.numberlookup.databinding.CellLapBinding
import java.util.Locale

/** Stopwatch with lap recording. */
class ChronoActivity : CoreActivity<ScreenStopwatchBinding>() {

    override val layoutId: Int = R.layout.screen_stopwatch

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var accumulatedMs = 0L
    private var startRealtime = 0L
    private var lastLapTotal = 0L
    private var lapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.stopwatchRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Sibling tool — the segmented control swaps activities rather than views.
        binding.padSegTimer.setOnClickListener {
            startActivity(Intent(this, CountdownActivity::class.java))
            finish()
        }

        // Mid native, scrolls with the tool content.
        NativeAdLoader().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)
        binding.padStartPause.setOnClickListener { if (running) pause() else start() }
        binding.padReset.setOnClickListener { reset() }
        binding.padLap.setOnClickListener { lap() }

        reset()
    }

    override fun onPause() {
        super.onPause()
        if (running) pause()
    }

    private fun start() {
        running = true
        startRealtime = SystemClock.elapsedRealtime()
        binding.padStartPause.setImageResource(R.drawable.glyph_pause)
        binding.padStartPause.contentDescription = getString(R.string.action_pause)
        setLapEnabled(true)
        handler.post(tick)
    }

    private fun pause() {
        running = false
        handler.removeCallbacks(tick)
        accumulatedMs += SystemClock.elapsedRealtime() - startRealtime
        showStartAffordance()
        renderTime()
    }

    private fun reset() {
        running = false
        handler.removeCallbacks(tick)
        accumulatedMs = 0L
        lastLapTotal = 0L
        lapCount = 0
        showStartAffordance()
        binding.rowLaps.removeAllViews()
        binding.rowLaps.visibility = View.GONE
        binding.emptyStateVw.visibility = View.VISIBLE
        binding.lblLapCount.text = getString(R.string.stopwatch_laps, 0)
        renderTime()
    }

    private fun lap() {
        if (!running) return
        val total = elapsed()
        val split = total - lastLapTotal
        lastLapTotal = total
        lapCount++

        val row = CellLapBinding.inflate(LayoutInflater.from(this), binding.rowLaps, false)
        row.lblLapName.text = getString(R.string.stopwatch_lap_n, lapCount)
        row.lblLapSplit.text = format(split)
        row.lblLapTotal.text = format(total)
        binding.rowLaps.addView(row.root, 0) // newest on top

        binding.rowLaps.visibility = View.VISIBLE
        binding.emptyStateVw.visibility = View.GONE
        binding.lblLapCount.text = getString(R.string.stopwatch_laps, lapCount)
    }

    private fun elapsed(): Long =
        accumulatedMs + if (running) SystemClock.elapsedRealtime() - startRealtime else 0L

    private val tick = object : Runnable {
        override fun run() {
            renderTime()
            handler.postDelayed(this, 30)
        }
    }

    private fun showStartAffordance() {
        binding.padStartPause.setImageResource(R.drawable.glyph_play)
        binding.padStartPause.contentDescription = getString(R.string.action_start)
        setLapEnabled(false)
    }

    /** Lap is meaningless while stopped, so it dims rather than silently no-op. */
    private fun setLapEnabled(enabled: Boolean) {
        binding.padLap.isEnabled = enabled
        binding.padLap.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderTime() {
        val ms = elapsed()
        // Split so the hundredths can sit smaller and in the brand colour, as the
        // design has them — one string could not carry two type styles.
        val totalSec = ms / 1000
        binding.lblTime.text =
            String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        binding.lblFraction.text =
            String.format(Locale.getDefault(), ".%02d", (ms % 1000) / 10)
        binding.lblStatus.setText(
            when {
                running -> R.string.stopwatch_running
                ms > 0L -> R.string.stopwatch_paused
                else -> R.string.stopwatch_ready
            }
        )
        // The hand turns once a minute, fractional so it sweeps rather than steps.
        binding.faceVw.setSeconds(ms / 1000f)
    }

    /** mm:ss.cc (centiseconds). */
    private fun format(ms: Long): String {
        val totalSec = ms / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", totalSec / 60, totalSec % 60, cs)
    }
}
