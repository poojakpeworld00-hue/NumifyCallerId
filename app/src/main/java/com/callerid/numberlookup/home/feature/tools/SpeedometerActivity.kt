package com.callerid.numberlookup.home.feature.tools

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.callerid.numberlookup.home.R
import com.callerid.numberlookup.home.foundation.BaseActivity
import com.callerid.numberlookup.home.monetize.delivery.NativeAdPresenter
import com.callerid.numberlookup.home.databinding.ActivitySpeedometerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Internet speed test: download / upload throughput, latency and jitter over HTTP. */
class SpeedometerActivity : BaseActivity<ActivitySpeedometerBinding>() {

    /** @see BaseActivity.screenKey - the name Remote Config and analytics use. */
    override val screenKey: String get() = "SpeedometerActivity"

    override val layoutId: Int = R.layout.activity_speedometer

    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.speedRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.buttonBack.setOnClickListener { goBack() }

        // Mid native, scrolls with the tool content.
        NativeAdPresenter().displayMediumNativeAlt(this, binding.adNativeFrame, binding.adShimmer)
        binding.buttonRetest.setOnClickListener { runTest() }
        showCarrier()
    }

    override fun onResume() {
        super.onResume()
        if (job?.isActive != true) runTest()
    }

    override fun onPause() {
        super.onPause()
        job?.cancel()
    }

    private fun runTest() {
        job?.cancel()
        resetUi()
        binding.textStatus.setText(R.string.speedometer_waiting)

        job = lifecycleScope.launch {
            // 1) Latency + jitter
            val (latency, jitter) = withContext(Dispatchers.IO) { measureLatency() }
            if (latency < 0) {
                binding.textStatus.setText(R.string.speedtest_error)
                return@launch
            }
            binding.textLatency.text = getString(R.string.speedtest_ms, latency)
            binding.textJitter.text = getString(R.string.speedtest_ms, jitter)

            // 2) Download (with live gauge)
            val download = measureDownload { live -> showSpeed(live) }
            showSpeed(download)

            // 3) Upload (best-effort)
            val upload = withContext(Dispatchers.IO) { measureUpload() }
            binding.textUpload.text = getString(R.string.speedtest_mbps, oneDp(upload))

            binding.textStatus.setText(R.string.speedtest_stable)
        }
    }

    private fun showSpeed(mbps: Float) {
        binding.textSpeed.text = mbps.roundToInt().toString()
        binding.gauge.setSpeed(mbps)
    }

    /** Names the connection the reading was taken over, so a slow result can be
     *  read against what it was measured on. */
    private fun showCarrier() {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val carrier = tm?.networkOperatorName?.takeIf { it.isNotBlank() }
        binding.textCarrier.text = carrier ?: getString(R.string.speedtest_download)
    }

    private fun resetUi() {
        binding.textSpeed.text = "0"
        binding.gauge.setSpeed(0f, animate = false)
        binding.textUpload.text = getString(R.string.speedtest_mbps, "0")
        binding.textLatency.text = getString(R.string.speedtest_ms, 0)
        binding.textJitter.text = getString(R.string.speedtest_ms, 0)
    }

    /** Returns avg latency (ms) and jitter (ms), or (-1, 0) if unreachable. */
    private fun measureLatency(): Pair<Int, Int> {
        val samples = mutableListOf<Long>()
        repeat(5) {
            runCatching {
                val start = SystemClock.elapsedRealtime()
                (URL(LATENCY_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                    connect()
                    responseCode
                    disconnect()
                }
                samples.add(SystemClock.elapsedRealtime() - start)
            }
        }
        if (samples.isEmpty()) return -1 to 0
        val avg = samples.average()
        val jitter = if (samples.size > 1) {
            (1 until samples.size).map { abs(samples[it] - samples[it - 1]).toDouble() }.average()
        } else 0.0
        return avg.roundToInt() to jitter.roundToInt()
    }

    /** Streams a fixed payload and reports live + final Mbps. */
    private suspend fun measureDownload(onLive: (Float) -> Unit): Float {
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                }
                conn.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0L
                    val start = SystemClock.elapsedRealtime()
                    var lastPost = start
                    while (isActive) {
                        val read = input.read(buf)
                        if (read < 0) break
                        total += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPost >= 150) {
                            lastPost = now
                            val live = mbps(total, now - start)
                            withContext(Dispatchers.Main) { onLive(live) }
                        }
                        if (now - start > 12_000) break
                    }
                    conn.disconnect()
                    mbps(total, SystemClock.elapsedRealtime() - start)
                }
            }.getOrDefault(0f)
        }
    }

    private fun measureUpload(): Float {
        return runCatching {
            val size = 5 * 1024 * 1024
            val conn = (URL(UPLOAD_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 8000
                doOutput = true
                requestMethod = "POST"
                setFixedLengthStreamingMode(size)
            }
            val start = SystemClock.elapsedRealtime()
            conn.outputStream.use { out ->
                val buf = ByteArray(16 * 1024)
                var sent = 0
                while (sent < size) {
                    val chunk = minOf(buf.size, size - sent)
                    out.write(buf, 0, chunk)
                    sent += chunk
                }
                out.flush()
            }
            conn.responseCode
            val elapsed = SystemClock.elapsedRealtime() - start
            conn.disconnect()
            mbps(size.toLong(), elapsed)
        }.getOrDefault(0f)
    }

    private fun mbps(bytes: Long, ms: Long): Float =
        if (ms <= 0) 0f else (bytes * 8f / (ms / 1000f) / 1_000_000f)

    private fun pct(value: Float, max: Float): Int =
        (value / max * 100f).roundToInt().coerceIn(0, 100)

    private fun oneDp(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

    private companion object {
        const val MAX_MBPS = 100f
        const val LATENCY_URL = "https://www.google.com/generate_204"
        const val DOWNLOAD_URL = "https://speed.cloudflare.com/__down?bytes=20000000"
        const val UPLOAD_URL = "https://speed.cloudflare.com/__up"
    }
}
