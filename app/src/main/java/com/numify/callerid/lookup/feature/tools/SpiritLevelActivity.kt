package com.numify.callerid.lookup.feature.tools

import android.content.res.ColorStateList
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.databinding.ActivityLevelBinding
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** A bubble (spirit) level driven by the accelerometer. */
class SpiritLevelActivity : BaseActivity<ActivityLevelBinding>(), SensorEventListener {

    override val layoutId: Int = R.layout.activity_level

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    private val gravity = FloatArray(3)

    // Calibration offsets captured by the Calibrate button.
    private var calRoll = 0f
    private var calPitch = 0f
    private var rawRoll = 0f
    private var rawPitch = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.levelRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNativeAlt(this, binding.adNativeFrameVw, binding.adShimmerVw)
        binding.padCalibrate.setOnClickListener {
            // Treat the current orientation as perfectly level.
            calRoll = rawRoll
            calPitch = rawPitch
        }

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // Low-pass filter to steady the reading.
        val a = 0.2f
        for (i in 0..2) gravity[i] = gravity[i] + a * (event.values[i] - gravity[i])
        val (x, y, z) = gravity

        rawRoll = Math.toDegrees(atan2(x.toDouble(), sqrt((y * y + z * z).toDouble()))).toFloat()
        rawPitch = Math.toDegrees(atan2(y.toDouble(), sqrt((x * x + z * z).toDouble()))).toFloat()

        val roll = rawRoll - calRoll
        val pitch = rawPitch - calPitch

        // The dial owns the bubble now, and clamps it to its own rim — a
        // translated child would slide out past the edge on a steep tilt.
        binding.bubbleVw.setTilt(roll, -pitch)

        val tilt = sqrt(roll * roll + pitch * pitch)
        binding.lblTilt.text = getString(R.string.level_deg_fmt, tilt)
        // Signed, because which way it leans is the point of these two.
        binding.lblX.text = getString(R.string.level_deg_signed_fmt, roll)
        binding.lblY.text = getString(R.string.level_deg_signed_fmt, pitch)
        bindStatus(tilt)
    }

    /** "Level" (green) when nearly flat, otherwise "Adjusting" (blue). */
    private fun bindStatus(tilt: Float) {
        val level = abs(tilt) < 1f
        binding.lblStatus.setText(if (level) R.string.level_level else R.string.level_adjusting)
        val fg = if (level) R.color.success else R.color.primary
        val bg = if (level) R.color.success_soft else R.color.primary_container
        binding.lblStatus.setTextColor(ContextCompat.getColor(this, fg))
        binding.lblStatus.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, bg))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
