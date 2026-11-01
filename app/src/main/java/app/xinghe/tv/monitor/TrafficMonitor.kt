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
import kotlin.math.abs

/**
 * The flow of traffic through the TV, watched: up and down, in bytes a second.
 *
 * The way 星河守卫 reads its whole-machine rate: [TrafficStats]'s system-wide running totals,
 * told apart once a second, the difference smoothed so the number does not twitch. These are the
 * totals the system itself keeps for every network, no root asked and no SELinux rule in the way.
 * The per-device counters in /proc/net/dev and sysfs were tried and let go — they count the link
 * layer (frame headers, ARP, broadcasts) and on some chipsets double-count through offload
 * interfaces, which read as an up rate too high. The sampler only runs while the reading is on.
 */
object TrafficMonitor {

    /** One sample: up and down, bytes a second. */
    data class Speed(val tx: Long, val rx: Long)

    /** Which corner of the screen the reading sits in. */
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _speed = MutableStateFlow(Speed(0, 0))
    /** The smoothed rate, fresh about once a second while [on] is true. */
    val speed: StateFlow<Speed> = _speed

    private val _on = MutableStateFlow(false)
    /** Whether the reading is wanted (the setting in Control Center). */
    val on: StateFlow<Boolean> = _on

    private val _corner = MutableStateFlow(Corner.TopEnd)
    /** The corner the reading is pinned to (the setting in Control Center). */
    val corner: StateFlow<Corner> = _corner

    private var started = false

    /** The corners of [Corner], in reading order for the picker. */
    val corners = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart, Corner.BottomEnd)

    /** Reads the switches back, and remembers them, in the app's own settings. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _on.value = prefs.getBoolean("traffic_monitor", false)
        _corner.value = runCatching {
            Corner.valueOf(prefs.getString("traffic_corner", Corner.TopEnd.name)!!)
        }.getOrDefault(Corner.TopEnd)
    }

    /** Sets the switch, and starts or stops the sampler and the floating window to match. */
    fun setOn(context: Context, value: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("traffic_monitor", value).apply()
        _on.value = value
        if (value) {
            start(context)
            TrafficFloatService.start(context)
        } else {
            TrafficFloatService.stop(context)
        }
    }

    /** Sets the corner the reading sits in, and remembers it. */
    fun setCorner(context: Context, value: Corner) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putString("traffic_corner", value.name).apply()
        _corner.value = value
    }

    /**
     * Starts the sampler if the switch asks for it. Cheap to call more than once: the loop runs
     * only while the reading is on, and one loop serves every reader.
     */
    fun start(context: Context) {
        load(context)
        if (started || !_on.value) return
        started = true
        scope.launch {
            var lastTx = -1L
            var lastRx = -1L
            var lastAt = System.currentTimeMillis()
            while (_on.value) {
                delay(1_000)
                // The system's own running totals; UNSUPPORTED reads as nothing.
                val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0)
                val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
                val now = System.currentTimeMillis()
                if (lastTx >= 0) {
                    val factor = 1000.0 / (now - lastAt).coerceAtLeast(200)
                    val up = ((tx - lastTx) * factor).toLong().let { if (it < 0) 0 else it }
                    val down = ((rx - lastRx) * factor).toLong().let { if (it < 0) 0 else it }
                    val old = _speed.value
                    // The same mild smoothing 星河守卫 gives its window: six tenths new.
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
