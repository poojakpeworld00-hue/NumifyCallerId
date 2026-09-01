package com.numify.callerid.numberlookup.screen.utility

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.numberlookup.R
import com.numify.callerid.numberlookup.core.BaseActivity
import com.numify.callerid.adkit.runtime.NativeAdPresenter
import com.numify.callerid.numberlookup.databinding.ScreenCompassBinding
import java.util.Locale
import kotlin.math.roundToInt

/** A magnetic compass driven by the device's rotation-vector sensor. */
class CompassActivity : BaseActivity<ScreenCompassBinding>(), SensorEventListener {

    override val layoutId: Int = R.layout.screen_compass

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var azimuth = 0f

    // 16-wind compass abbreviations (N, NNE, NE, …).
    private val directions by lazy {
        val n = getString(R.string.cardinal_n)
        val e = getString(R.string.cardinal_e)
        val s = getString(R.string.cardinal_s)
        val w = getString(R.string.cardinal_w)
        arrayOf(
            n, "$n$n$e", "$n$e", "$e$n$e", e, "$e$s$e", "$s$e", "$s$s$e",
            s, "$s$s$w", "$s$w", "$w$s$w", w, "$w$n$w", "$n$w", "$n$n$w"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.compassRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            binding.lblNoSensor.visibility = View.VISIBLE
            binding.contentVw.visibility = View.GONE
        } else {
            updateSignal(SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val target = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360f) % 360f

        // Smooth, taking the shortest path around the 360°/0° seam.
        val diff = ((target - azimuth + 540f) % 360f) - 180f
        azimuth = (azimuth + diff * 0.15f + 360f) % 360f

        // The dial turns its own rose and smooths the sensor; rotating the whole
        // view instead would spin the readout and the shadow with it.
        binding.picNeedle.setHeading(azimuth)
        val deg = azimuth.roundToInt() % 360
        binding.lblHeading.text = String.format(Locale.getDefault(), "%03d°", deg)
        binding.lblDirection.text = directions[((deg / 22.5f).roundToInt()) % 16]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = updateSignal(accuracy)

    /** Maps the magnetometer accuracy to a Strong / Medium / Weak signal label. */
    private fun updateSignal(accuracy: Int) {
        val labelRes = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> R.string.signal_strong
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> R.string.signal_medium
            else -> R.string.signal_weak
        }
        binding.lblSignal.text = getString(R.string.compass_signal, getString(labelRes))
    }
}
