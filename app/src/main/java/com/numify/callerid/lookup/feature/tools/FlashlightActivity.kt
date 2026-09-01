package com.numify.callerid.lookup.feature.tools

import android.content.res.ColorStateList
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.databinding.ScreenFlashlightBinding
import kotlin.math.max
import kotlin.math.roundToInt

/** Torch with Steady / Strobe / SOS modes and (where supported) brightness control. */
class FlashlightActivity : BaseActivity<ScreenFlashlightBinding>() {

    override val layoutId: Int = R.layout.screen_flashlight

    private enum class Mode { STEADY, STROBE, SOS }

    private lateinit var cameraManager: CameraManager
    private var cameraId: String? = null
    private var maxStrength = 1

    private var active = false
    private var mode = Mode.STEADY
    private var brightnessPct = 100

    private val handler = Handler(Looper.getMainLooper())
    private var strobeOn = false
    // SOS = ...---... : on/off durations in ms, looping.
    private val sosPattern = longArrayOf(
        200, 200, 200, 200, 200, 400,
        500, 200, 500, 200, 500, 400,
        200, 200, 200, 200, 200, 1200
    )
    private var sosIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.flashlightRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraId = findFlashCamera()
        if (cameraId == null) {
            binding.lblNoFlash.visibility = View.VISIBLE
            binding.contentVw.visibility = View.GONE
            return
        }

        binding.padToggle.setOnClickListener { toggleActive() }
        binding.modeSteadyVw.setOnClickListener { selectMode(Mode.STEADY) }
        binding.modeStrobeVw.setOnClickListener { selectMode(Mode.STROBE) }
        binding.modeSosVw.setOnClickListener { selectMode(Mode.SOS) }

        binding.seekBrightnessVw.progress = brightnessPct
        binding.seekBrightnessVw.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                brightnessPct = value
                if (active && mode == Mode.STEADY) applyTorch(true)
                updateReadout()
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        selectMode(Mode.STEADY)
        updatePowerUi()
        updateReadout()
    }

    private fun findFlashCamera(): String? = runCatching {
        cameraManager.cameraIdList.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            val hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (hasFlash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                maxStrength = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            }
            hasFlash
        }
    }.getOrNull()

    private fun toggleActive() {
        active = !active
        if (active) startMode() else stopAll()
        updatePowerUi()
        updateReadout()
    }

    private fun selectMode(target: Mode) {
        mode = target
        highlightModes()
        if (active) startMode() // restart with the new mode
        updateReadout()
    }

    private fun startMode() {
        handler.removeCallbacksAndMessages(null)
        when (mode) {
            Mode.STEADY -> applyTorch(true)
            Mode.STROBE -> handler.post(strobeTick)
            Mode.SOS -> { sosIndex = 0; handler.post(sosTick) }
        }
    }

    private fun stopAll() {
        handler.removeCallbacksAndMessages(null)
        applyTorch(false)
    }

    /** Turns the torch on/off, using the brightness level where the device supports it. */
    private fun applyTorch(on: Boolean) {
        val id = cameraId ?: return
        runCatching {
            if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && maxStrength > 1) {
                val level = max(1, (brightnessPct / 100f * maxStrength).roundToInt())
                cameraManager.turnOnTorchWithStrengthLevel(id, level)
            } else {
                cameraManager.setTorchMode(id, on)
            }
        }
    }

    private val strobeTick = object : Runnable {
        override fun run() {
            strobeOn = !strobeOn
            applyTorch(strobeOn)
            handler.postDelayed(this, 90)
        }
    }

    private val sosTick = object : Runnable {
        override fun run() {
            applyTorch(sosIndex % 2 == 0)
            val duration = sosPattern[sosIndex]
            sosIndex = (sosIndex + 1) % sosPattern.size
            handler.postDelayed(this, duration)
        }
    }

    private fun updatePowerUi() {
        binding.padToggle.setBackgroundResource(
            if (active) R.drawable.shape_cid_torch_on else R.drawable.shape_cid_torch_off
        )
        binding.padToggle.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, if (active) R.color.white else R.color.on_surface_variant)
        )
        binding.lblState.setText(
            if (active) R.string.flashlight_state_on else R.string.flashlight_state_off
        )
        binding.lblState.setTextColor(
            ContextCompat.getColor(this, if (active) R.color.cid_amber else R.color.on_surface_variant)
        )
        // The halo is the only thing that says "this is throwing light", so it
        // fades rather than snaps — a hard cut reads as a rendering glitch.
        val target = if (active) 1f else 0f
        binding.beamOuterVw.animate().alpha(target).setDuration(220L).start()
        binding.beamInnerVw.animate().alpha(target).setDuration(220L).start()
    }

    private fun updateReadout() {
        binding.lblBrightnessPct.text = getString(R.string.flashlight_pct, brightnessPct)
    }

    private fun highlightModes() {
        setMode(binding.modeSteadyVw, binding.picSteady, binding.lblSteady, mode == Mode.STEADY)
        setMode(binding.modeStrobeVw, binding.picStrobe, binding.lblStrobe, mode == Mode.STROBE)
        setMode(binding.modeSosVw, binding.picSos, binding.lblSos, mode == Mode.SOS)
    }

    private fun setMode(container: View, icon: ImageView, label: TextView, selected: Boolean) {
        container.setBackgroundResource(
            if (selected) R.drawable.shape_cid_mode_on else R.drawable.shape_cid_mode_off
        )
        val ink = ContextCompat.getColor(this, if (selected) R.color.white else R.color.on_surface)
        icon.imageTintList = ColorStateList.valueOf(ink)
        label.setTextColor(ink)
    }

    override fun onPause() {
        super.onPause()
        active = false
        stopAll()
        updatePowerUi()
        updateReadout()
    }

    private companion object {
    }
}
