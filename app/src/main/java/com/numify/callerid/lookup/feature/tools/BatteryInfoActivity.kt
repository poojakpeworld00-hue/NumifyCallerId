package com.numify.callerid.lookup.feature.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.databinding.ActivityBatteryBinding
import java.util.Locale
import kotlin.math.roundToInt

/** Live battery stats, read from sticky ACTION_BATTERY_CHANGED broadcasts. */
class BatteryInfoActivity : BaseActivity<ActivityBatteryBinding>() {

    override val layoutId: Int = R.layout.activity_battery

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { render(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.batteryRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }
        val openBatterySettings = View.OnClickListener {
            // Capacity, cycle count and time-to-full are not exposed to apps, so
            // the screen hands off to the one place that does know them.
            runCatching { startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        }
        binding.padSettings.setOnClickListener(openBatterySettings)
        binding.padSaver.setOnClickListener(openBatterySettings)

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(receiver) }
    }

    private fun render(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        binding.lblLevel.text = if (level >= 0 && scale > 0) (level * 100 / scale).toString() else "—"

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        binding.cellVw.setLevel(pct, charging)
        // The readout sits over the middle of the cell, so its colour has to
        // follow whether the fill has got that far — white on an empty cell is
        // invisible, and dark ink on the fill is barely better.
        val onFill = binding.cellVw.fillCoversCentre(pct)
        val ink = ContextCompat.getColor(this, if (onFill) R.color.white else R.color.on_surface)
        binding.lblLevel.setTextColor(ink)
        binding.lblPercentSign.setTextColor(ink)

        binding.lblStatus.text = statusText(status)
        binding.lblPlugged.text = pluggedText(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1))
        binding.lblCapacity.text = capacityText()
        binding.lblScreenOn.text = screenOnText()

        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        binding.lblTemp.text = getString(R.string.battery_temp_fmt, tempC.roundToInt())

        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        binding.lblVoltage.text = getString(R.string.battery_voltage_fmt, voltage)

        binding.lblTech.text = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "—"

        bindHealth(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1))
    }

    /** Design-capacity in mAh. Absent on plenty of devices, hence the dash. */
    private fun capacityText(): String {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return "—"
        val uAh = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrDefault(Int.MIN_VALUE)
        if (uAh <= 0) return "—"
        return getString(R.string.battery_mah_fmt, uAh / 1000)
    }

    /** Time the device has been awake — the closest public stand-in for the
     *  design's "screen on", which needs usage-stats access to measure properly. */
    private fun screenOnText(): String {
        val ms = SystemClock.elapsedRealtime()
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return getString(R.string.stopwatch_hm_fmt, h, m)
    }

    /** Health value + a status-appropriate colour. */
    private fun bindHealth(health: Int) {
        val (textRes, colorRes) = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_great to R.color.primary
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_hot to R.color.warn
            BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold to R.color.primary
            BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead to R.color.danger
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over to R.color.danger
            else -> R.string.common_unknown to R.color.on_surface
        }
        binding.lblHealth.setText(textRes)
        binding.lblHealth.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun statusText(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    private fun pluggedText(plugged: Int) = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
        0 -> "Battery"
        else -> "Unknown"
    }
}
