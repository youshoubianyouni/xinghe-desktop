package app.xinghe.tv.monitor

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * The flow of traffic through the TV, watched: up and down, in bytes a second.
 *
 * Read the way TVBoxNetGuard reads its whole-machine rate — the system's own running totals
 * ([TrafficStats]) told apart once a second, the difference smoothed so the number does not
 * twitch. No VPN, no root: the totals are public, and the sampler only runs while Home is up.
 */
object TrafficMonitor {

    /** One sample: up and down, bytes a second. */
    data class Speed(val tx: Long, val rx: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _speed = MutableStateFlow(Speed(0, 0))
    /** The smoothed rate, fresh about once a second while [on] is true. */
    val speed: StateFlow<Speed> = _speed

    private val _on = MutableStateFlow(false)
    /** Whether the reading is wanted (the setting in Control Center). */
    val on: StateFlow<Boolean> = _on

    private var started = false

    /** Reads the switch back, and remembers it, in the app's own settings. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _on.value = prefs.getBoolean("traffic_monitor", false)
    }

    /** Sets the switch, and starts or stops the sampler to match. */
    fun setOn(context: Context, value: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("traffic_monitor", value).apply()
        _on.value = value
        if (value) start(context)
    }

    /**
     * Starts the sampler if the switch asks for it. Cheap to call more than once: the loop runs
     * only while the reading is on, and one loop serves every reader.
     */
    fun start(context: Context) {
        load(context)
        if (started || !_on.value) return
        started = true
        // The totals over every interface but loopback, as the system itself counts them.
        val nets = File("/sys/class/net").listFiles()
            ?.map { it.name }
            ?.filter { it != "lo" }
            ?.toSet()
            ?: emptySet()
        scope.launch {
            var lastTx = -1L
            var lastRx = -1L
            var lastAt = System.currentTimeMillis()
            while (_on.value) {
                delay(1_000)
                var tx = 0L
                var rx = 0L
                for (net in nets) {
                    tx += readTotal("/sys/class/net/$net/statistics/tx_bytes")
                    rx += readTotal("/sys/class/net/$net/statistics/rx_bytes")
                }
                // A counter that went backwards (a interface gone away) reads as nothing.
                if (tx == 0L && rx == 0L) {
                    tx = TrafficStats.getTotalTxBytes()
                    rx = TrafficStats.getTotalRxBytes()
                }
                val now = System.currentTimeMillis()
                if (lastTx >= 0) {
                    val factor = 1000.0 / (now - lastAt).coerceAtLeast(200)
                    val up = ((tx - lastTx) * factor).toLong().let { if (it < 0) 0 else it }
                    val down = ((rx - lastRx) * factor).toLong().let { if (it < 0) 0 else it }
                    val old = _speed.value
                    // The same mild smoothing TVBoxNetGuard gives its window: six tenths new.
                    _speed.value = Speed(
                        tx = (old.tx * 0.4 + up * 0.6).toLong(),
                        rx = (old.rx * 0.4 + down * 0.6).toLong(),
                    )
                }
                lastTx = tx
                lastRx = rx
                lastAt = now
            }
            started = false
            _speed.value = Speed(0, 0)
        }
    }

    private fun readTotal(path: String): Long =
        runCatching { File(path).readText().trim().toLong() }.getOrDefault(0L)

    /** A rate as people read it: 1.2 MB/s, 38 KB/s. */
    fun rate(bytesPerSec: Long): String {
        val v = abs(bytesPerSec)
        return when {
            v >= 1024L * 1024 -> "%.2f MB/s".format(bytesPerSec / 1048576.0)
            v >= 1024L -> "%.0f KB/s".format(bytesPerSec / 1024.0)
            else -> "$bytesPerSec B/s"
        }
    }
}
