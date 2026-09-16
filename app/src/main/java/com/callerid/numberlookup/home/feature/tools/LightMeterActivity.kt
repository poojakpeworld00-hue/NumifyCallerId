package com.callerid.numberlookup.home.feature.tools

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.delivery.NativeAdPresenter
import com.callerid.numberlookup.home.databinding.ActivityLightMeterBinding
import kotlin.math.roundToInt

/** Ambient light meter (lux) using the device light sensor. */
class LightMeterActivity : BaseActivity<ActivityLightMeterBinding>(), SensorEventListener {

    override val layoutId: Int = R.layout.activity_light_meter

    private lateinit var sensorManager: SensorManager
    private var lightSensor: Sensor? = null

    private var held = false
    private var min = Float.MAX_VALUE
    private var max = 0f
    private var sum = 0.0
    private var count = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.lightRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().displayMediumNativeAlt(this, binding.adNativeFrame, binding.adShimmer)
        binding.buttonCapture.setOnClickListener {
            held = !held
            binding.buttonCapture.setText(if (held) R.string.meter_resume else R.string.meter_pause)
        }
        binding.buttonZero.setOnClickListener { resetStats() }
        resetStats()

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (lightSensor == null) {
            binding.textNoSensor.visibility = View.VISIBLE
            binding.content.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LIGHT || held) return
        val lux = event.values[0]
        binding.textLux.text = lux.roundToInt().toString()
        // The burst grows toward MAX_SCALE lux (bright indoor / overcast daylight).
        binding.burst.setIntensity((lux / MAX_SCALE).coerceIn(0f, 1f))
        showBand(lux)

        if (lux < min) min = lux
        if (lux > max) max = lux
        sum += lux
        count++

        binding.textMin.text = lx(min)
        binding.textMax.text = lx(max)
    }

    /**
     * Lights up the band the current reading falls into. The thresholds follow the
     * everyday lighting conditions the labels name rather than splitting the scale
     * evenly - "dark" and "sunlight" are orders of magnitude apart.
     */
    private fun showBand(lux: Float) {
        val active = when {
            lux < 10f -> 0
            lux < 100f -> 1
            lux < 1_000f -> 2
            lux < 10_000f -> 3
            else -> 4
        }
        val bars = listOf(
            binding.band1, binding.band2, binding.band3,
            binding.band4, binding.band5,
        )
        val labels = listOf(
            binding.band1Lbl, binding.band2Lbl, binding.band3Lbl,
            binding.band4Lbl, binding.band5Lbl,
        )
        bars.forEachIndexed { i, bar ->
            bar.setBackgroundResource(
                if (i == active) R.drawable.bg_cid_band_on else R.drawable.bg_cid_band_off
            )
        }
        labels.forEachIndexed { i, label ->
            label.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (i == active) R.color.ds_warning else R.color.ds_band_label
                )
            )
        }
        binding.textCategory.setText(
            when (active) {
                0 -> R.string.light_band_dark
                1 -> R.string.light_band_dim
                2 -> R.string.light_band_indoor
                3 -> R.string.light_band_bright
                else -> R.string.light_band_sun
            }
        )
    }

    private fun resetStats() {
        min = Float.MAX_VALUE
        max = 0f
        sum = 0.0
        count = 0L
        binding.textMin.text = lx(0f)
        binding.textMax.text = lx(0f)
    }

    private fun lx(value: Float): String {
        val v = if (value == Float.MAX_VALUE) 0 else value.roundToInt()
        return getString(R.string.light_lux_fmt, v)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object {
        const val MAX_SCALE = 1000f
    }
}
