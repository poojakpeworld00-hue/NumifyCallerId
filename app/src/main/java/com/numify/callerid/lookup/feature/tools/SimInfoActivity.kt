package com.numify.callerid.lookup.feature.tools

import android.Manifest
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.numify.callerid.lookup.R
import com.numify.callerid.lookup.foundation.BaseActivity
import com.numify.callerid.monetize.delivery.NativeAdPresenter
import com.numify.callerid.lookup.databinding.ScreenSimInfoBinding
import java.util.Locale
import java.net.Inet4Address
import java.net.NetworkInterface

/** Carrier / SIM / network details from [TelephonyManager]. */
class SimInfoActivity : BaseActivity<ScreenSimInfoBinding>() {

    override val layoutId: Int = R.layout.screen_sim_info

    private val tm by lazy { getSystemService(TELEPHONY_SERVICE) as TelephonyManager }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.simRootVw) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.padBack.setOnClickListener { goBack() }
        binding.padRefresh.setOnClickListener { render() }
        binding.padSpeedTest.setOnClickListener {
            startActivity(Intent(this, SpeedometerActivity::class.java))
        }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().renderMidNative(this, binding.adNativeFrameVw, binding.adShimmerVw)
    }

    override fun onResume() {
        super.onResume()
        if (hasPhonePermission()) render()
        else permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun hasPhonePermission(): Boolean = ContextCompat.checkSelfPermission(
        this, Manifest.permission.READ_PHONE_STATE
    ) == PackageManager.PERMISSION_GRANTED

    private fun render() {
        val carrier = tm.networkOperatorName.ifBlank { dash() }
        val type = networkTypeText()

        // Hero
        binding.lblCarrier.text = carrier
        binding.lblStatus.text = if (hasPhonePermission()) {
            getString(R.string.sim_status_fmt, type, getString(R.string.sim_connected))
        } else {
            getString(R.string.sim_permission)
        }
        paintSignalBars(signalLevel())

        // SIM & carrier
        binding.lblCarrierRow.text = carrier
        binding.lblNetworkType.text = type
        binding.lblCountry.text =
            tm.networkCountryIso.uppercase(Locale.getDefault()).ifBlank { dash() }
        binding.lblSignal.text = signalDbm()?.let { getString(R.string.sim_dbm, it) } ?: dash()

        // Connection
        binding.lblIp.text = localIpAddress() ?: dash()
        binding.lblSimState.text = simStateText()
        binding.lblPhoneType.text = phoneTypeText()
        binding.lblRoaming.text =
            if (tm.isNetworkRoaming) getString(R.string.common_yes) else getString(R.string.common_no)
    }

    /** 0..4. Below API 28 there is no public accessor, so the bars stay unlit. */
    private fun signalLevel(): Int {
        if (!hasPhonePermission()) return 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return 0
        return runCatching { tm.signalStrength?.level ?: 0 }.getOrDefault(0)
    }

    private fun signalDbm(): Int? {
        if (!hasPhonePermission()) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching { tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm }
            .getOrNull()
    }

    private fun paintSignalBars(level: Int) {
        listOf(binding.bar1Vw, binding.bar2Vw, binding.bar3Vw, binding.bar4Vw)
            .forEachIndexed { i, bar ->
                bar.setBackgroundResource(
                    if (i < level) R.drawable.shape_cid_bar_on else R.drawable.shape_cid_bar_off
                )
            }
    }

    /**
     * First non-loopback IPv4 on any up interface. IPv6 is skipped because the
     * row is a single line and a full v6 address ellipsises into nothing useful.
     */
    private fun localIpAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun networkTypeText(): String {
        if (!hasPhonePermission()) return dash()
        return runCatching {
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA -> "3G"
                TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
                TelephonyManager.NETWORK_TYPE_UNKNOWN -> getString(R.string.common_unknown)
                else -> getString(R.string.common_unknown)
            }
        }.getOrElse { dash() }
    }

    private fun phoneTypeText() = when (tm.phoneType) {
        TelephonyManager.PHONE_TYPE_GSM -> "GSM"
        TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
        TelephonyManager.PHONE_TYPE_SIP -> "SIP"
        else -> getString(R.string.common_unknown)
    }

    private fun simStateText() = when (tm.simState) {
        TelephonyManager.SIM_STATE_READY -> "Ready"
        TelephonyManager.SIM_STATE_ABSENT -> "No SIM"
        TelephonyManager.SIM_STATE_PIN_REQUIRED,
        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Locked"
        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
        else -> getString(R.string.common_unknown)
    }

    private fun dash() = "—"
}
