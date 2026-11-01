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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The flow of traffic through the TV, watched: up and down, in bytes a second.
 *
 * The totals are told apart once a second and the difference smoothed so the number does not
 * twitch. Where the totals come from depends on the TV: with root at hand they are read out of
 * `/proc/net/dev` through `su` — the one reading the system itself keeps, over every interface,
 * however strict its SELinux rules are about who may ask; without root, the same totals are read
 * the public way, from the per-interface counters in sysfs, falling back to [TrafficStats] when
 * those are not on show. The sampler only runs while Home is up.
 */
object TrafficMonitor {

    /** One sample: up and down, bytes a second. */
    data class Speed(val tx: Long, val rx: Long)

    /** Which corner of the screen the reading sits in. */
    enum class Corner { TopStart, TopEnd, BottomStart, BottomEnd }

    /** Where the totals were read from at the last tick. */
    enum class Source { System, Root }

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

    private val _source = MutableStateFlow(Source.System)
    /** Where the last tick's totals came from, shown in Control Center. */
    val source: StateFlow<Source> = _source

    private var started = false

    /** The corner names of [Corner], in reading order for the picker. */
    val corners = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart, Corner.BottomEnd)

    /** Reads the switches back, and remembers them, in the app's own settings. */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _on.value = prefs.getBoolean("traffic_monitor", false)
        _corner.value = runCatching {
            Corner.valueOf(prefs.getString("traffic_corner", Corner.TopEnd.name)!!)
        }.getOrDefault(Corner.TopEnd)
    }

    /** Sets the switch, and starts or stops the sampler to match. */
    fun setOn(context: Context, value: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("traffic_monitor", value).apply()
        _on.value = value
        if (value) start(context)
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
        // Root is probed once per run of the loop: a `su` found on disk is asked to prove itself,
        // since a file named su alone has let people down before.
        val rooted = probeRoot()
        // The totals over every interface but loopback, as the system itself counts them, read the
        // public way. These paths are opened once; the interfaces of a TV do not come and go.
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
                // Root first, when there is root: a full reading out of /proc/net/dev. Should the
                // su call hang or come back empty — an unfinished boot, a manager asking for
                // permission with no one to answer — the tick falls to the public way instead.
                var totals = if (rooted) withTimeoutOrNull(900) {
                    runInterruptible(Dispatchers.IO) { rootTotals() }
                } else null
                _source.value = if (totals != null) Source.Root else Source.System
                if (totals == null) {
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
                    totals = tx to rx
                }
                val now = System.currentTimeMillis()
                if (lastTx >= 0) {
                    val factor = 1000.0 / (now - lastAt).coerceAtLeast(200)
                    val up = ((totals.first - lastTx) * factor).toLong().let { if (it < 0) 0 else it }
                    val down = ((totals.second - lastRx) * factor).toLong().let { if (it < 0) 0 else it }
                    val old = _speed.value
                    // The same mild smoothing TVBoxNetGuard gives its window: six tenths new.
                    _speed.value = Speed(
                        tx = (old.tx * 0.4 + up * 0.6).toLong(),
                        rx = (old.rx * 0.4 + down * 0.6).toLong(),
                    )
                }
                lastTx = totals.first
                lastRx = totals.second
                lastAt = now
            }
            started = false
            _speed.value = Speed(0, 0)
        }
    }

    /** A `su` on disk, and one that answers; without both, the public way serves. */
    private fun probeRoot(): Boolean {
        val files = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/vendor/bin/su", "/su/bin/su", "/system/sd/xbin/su",
        ).map(::File).any { it.exists() }
        if (!files) return false
        return runCatching {
            val p = ProcessBuilder("su", "-c", "id").start()
            val done = p.waitFor(2, TimeUnit.SECONDS)
            val out = if (done) p.inputStream.bufferedReader().readText() else ""
            p.destroy()
            done && out.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * Every interface's totals at once, out of `/proc/net/dev` with root's leave. Null when su
     * would not speak — the caller then reads the public way this tick.
     */
    private fun rootTotals(): Pair<Long, Long>? {
        val p = ProcessBuilder("su", "-c", "cat /proc/net/dev").start()
        val ok = p.waitFor(800, TimeUnit.MILLISECONDS)
        if (!ok) { p.destroy(); return null }
        val text = p.inputStream.bufferedReader().readText()
        p.destroy()
        var tx = 0L
        var rx = 0L
        var any = false
        for (line in text.lineSequence()) {
            val sep = line.indexOf(':')
            if (sep < 0) continue
            val face = line.substring(0, sep).trim()
            if (face == "lo" || face.isEmpty()) continue
            val f = line.substring(sep + 1).trim().split(Regex("\\s+"))
            if (f.size < 9) continue
            rx += f[0].toLongOrNull() ?: 0L
            tx += f[8].toLongOrNull() ?: 0L
            any = true
        }
        return if (any) tx to rx else null
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
