package com.numify.callerid.lookup.feature.tools

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
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.databinding.ActivityStopwatchBinding
import com.numify.callerid.lookup.databinding.ItemLapBinding
import java.util.Locale

/** Stopwatch with lap recording. */
class StopwatchActivity : BaseActivity<ActivityStopwatchBinding>() {

    override val layoutId: Int = R.layout.activity_stopwatch

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
        ViewCompat.setOnApplyWindowInsetsListener(binding.stopwatchRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }

        // Sibling tool — the segmented control swaps activities rather than views.
        binding.buttonSegTimer.setOnClickListener {
            startActivity(Intent(this, TimerActivity::class.java))
            finish()
        }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().displayMediumNative(this, binding.adNativeFrame, binding.adShimmer)
        binding.buttonStartPause.setOnClickListener { if (running) pause() else start() }
        binding.buttonReset.setOnClickListener { reset() }
        binding.buttonLap.setOnClickListener { lap() }

        reset()
    }

    override fun onPause() {
        super.onPause()
        if (running) pause()
    }

    private fun start() {
        running = true
        startRealtime = SystemClock.elapsedRealtime()
        binding.buttonStartPause.setImageResource(R.drawable.ic_pause)
        binding.buttonStartPause.contentDescription = getString(R.string.action_pause)
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
        binding.columnLaps.removeAllViews()
        binding.columnLaps.visibility = View.GONE
        binding.emptyState.visibility = View.VISIBLE
        binding.textLapCount.text = getString(R.string.stopwatch_laps, 0)
        renderTime()
    }

    private fun lap() {
        if (!running) return
        val total = elapsed()
        val split = total - lastLapTotal
        lastLapTotal = total
        lapCount++

        val row = ItemLapBinding.inflate(LayoutInflater.from(this), binding.columnLaps, false)
        row.textLapName.text = getString(R.string.stopwatch_lap_n, lapCount)
        row.textLapSplit.text = format(split)
        row.textLapTotal.text = format(total)
        binding.columnLaps.addView(row.root, 0) // newest on top

        binding.columnLaps.visibility = View.VISIBLE
        binding.emptyState.visibility = View.GONE
        binding.textLapCount.text = getString(R.string.stopwatch_laps, lapCount)
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
        binding.buttonStartPause.setImageResource(R.drawable.ic_play)
        binding.buttonStartPause.contentDescription = getString(R.string.action_start)
        setLapEnabled(false)
    }

    /** Lap is meaningless while stopped, so it dims rather than silently no-op. */
    private fun setLapEnabled(enabled: Boolean) {
        binding.buttonLap.isEnabled = enabled
        binding.buttonLap.alpha = if (enabled) 1f else 0.4f
    }

    private fun renderTime() {
        val ms = elapsed()
        // Split so the hundredths can sit smaller and in the brand colour, as the
        // design has them — one string could not carry two type styles.
        val totalSec = ms / 1000
        binding.textTime.text =
            String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
        binding.textFraction.text =
            String.format(Locale.getDefault(), ".%02d", (ms % 1000) / 10)
        binding.textStatus.setText(
            when {
                running -> R.string.stopwatch_running
                ms > 0L -> R.string.stopwatch_paused
                else -> R.string.stopwatch_ready
            }
        )
        // The hand turns once a minute, fractional so it sweeps rather than steps.
        binding.face.setSeconds(ms / 1000f)
    }

    /** mm:ss.cc (centiseconds). */
    private fun format(ms: Long): String {
        val totalSec = ms / 1000
        val cs = (ms % 1000) / 10
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", totalSec / 60, totalSec % 60, cs)
    }
}
