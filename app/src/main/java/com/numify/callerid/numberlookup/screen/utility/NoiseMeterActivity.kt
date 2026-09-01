package com.numify.callerid.numberlookup.screen.utility

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.BaseActivity
import com.numify.callerid.adkit.runtime.NativeAdPresenter
import com.numify.callerid.numberlookup.databinding.ScreenSoundMeterBinding
import java.io.File
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

/** Approximate sound-level meter using [MediaRecorder.getMaxAmplitude]. */
class NoiseMeterActivity : BaseActivity<ScreenSoundMeterBinding>() {

    override val layoutId: Int = R.layout.screen_sound_meter

    private var recorder: MediaRecorder? = null
    private val handler = Handler(Looper.getMainLooper())

    private var held = false
    private var peak = 0f

    /** Session minimum. Starts high so the first sample always wins. */
    private var quietest = Float.MAX_VALUE
    private var sum = 0.0
    private var count = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMetering() else binding.lblStatus.setText(R.string.sound_permission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.soundRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNativeAlt(this, binding.adNativeFrameVw, binding.adShimmerVw)
        binding.padHold.setOnClickListener {
            held = !held
            binding.padHold.setText(if (held) R.string.meter_resume else R.string.meter_pause)
        }
        binding.padReset.setOnClickListener { resetData() }
    }

    override fun onResume() {
        super.onResume()
        if (hasMicPermission()) startMetering()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onPause() {
        super.onPause()
        stopMetering()
    }

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun startMetering() {
        if (recorder != null || !hasMicPermission()) return
        binding.lblStatus.text = ""
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this)
        else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            rec.setOutputFile(File(cacheDir, "sound_meter.tmp").absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            handler.post(tick)
        }.onFailure { rec.release() }
    }

    private fun stopMetering() {
        handler.removeCallbacks(tick)
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
    }

    private fun resetData() {
        peak = 0f
        sum = 0.0
        count = 0L
        binding.spectrumVw.reset()
        quietest = Float.MAX_VALUE
        binding.lblMin.text = formatDb(0f)
        binding.lblPeak.text = formatDb(0f)
        binding.lblAvg.text = formatDb(0f)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!held) {
                val amplitude = recorder?.maxAmplitude ?: 0
                val db = (if (amplitude > 0) 20 * log10(amplitude.toDouble()) else 0.0)
                    .toFloat().coerceIn(0f, 100f)
                render(db)
            }
            handler.postDelayed(this, 200)
        }
    }

    private fun render(db: Float) {
        binding.gaugeVw.setDb(db)
        binding.lblDb.text = db.roundToInt().toString()
        binding.lblCategory.setText(categoryRes(db))

        if (db > peak) {
            peak = db
            binding.lblPeak.text = formatDb(peak)
        }
        if (db < quietest) {
            quietest = db
            binding.lblMin.text = formatDb(quietest)
        }
        sum += db
        count++
        binding.lblAvg.text = formatDb((sum / count).toFloat())

        binding.spectrumVw.setLevel(db / 100f)
    }

    private fun formatDb(db: Float) = String.format(Locale.getDefault(), "%.1f dB", db)

    private fun categoryRes(db: Float) = when {
        db < 40f -> R.string.sound_cat_quiet
        db < 60f -> R.string.sound_cat_normal
        db < 75f -> R.string.sound_cat_loud
        db < 90f -> R.string.sound_cat_very_loud
        else -> R.string.sound_cat_dangerous
    }
}
