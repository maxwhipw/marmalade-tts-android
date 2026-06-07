package app.marmalade.tts.perf

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File

/** One CPU core's busy percentage over the last sample interval. */
data class CoreLoad(val core: Int, val busyPct: Int)

/**
 * A point-in-time view of device load, for the debug benchmark overlay.
 *
 * The fields that actually explain Pocket's RTF on a loaded phone are
 * [ramAvailMb] + [zramUsedMb] (memory pressure → zram thrash → a
 * bandwidth-bound AR loop tanks) and the CPU busy figures (contention from
 * other apps). Thermal is here too, but on a Tensor G3 it's rarely the cause.
 */
data class SystemStatsSnapshot(
    val ramAvailMb: Long,
    val ramTotalMb: Long,
    val zramUsedMb: Long,
    val zramTotalMb: Long,
    val cpuBusyPct: Int,
    val cores: List<CoreLoad>,
    val thermalStatus: String,
    val thermalHeadroom: Float?,
    val clusterTemps: List<Pair<String, Float>>,
)

/**
 * Reads device load from `/proc` + the thermal API. Debug-only (the benchmark
 * overlay). CPU% is a rate, so it needs two samples to delta — callers thread
 * the returned [CpuCounters] back into the next [sample] call.
 *
 * All reads are best-effort and exception-swallowing: `/proc/stat` and
 * `/proc/meminfo` are app-readable, but `/sys/class/thermal` is often
 * SELinux-blocked on recent Android, so [clusterTemps] may come back empty —
 * the thermal *status* from [PowerManager] is the reliable signal.
 */
object SystemStats {

    /** Per-cpu jiffy counters; key -1 = aggregate "cpu", 0..n = per core → (idleAll, total). */
    data class CpuCounters(val perCpu: Map<Int, Pair<Long, Long>>)

    fun sample(context: Context, prev: CpuCounters?): Pair<SystemStatsSnapshot, CpuCounters> {
        val counters = readCpu()
        val mem = readMem()
        val cores = ArrayList<CoreLoad>()
        var aggBusy = 0
        if (prev != null) {
            for ((id, cur) in counters.perCpu) {
                val p = prev.perCpu[id] ?: continue
                val dIdle = cur.first - p.first
                val dTotal = cur.second - p.second
                val busy = if (dTotal > 0) {
                    (((dTotal - dIdle) * 100) / dTotal).toInt().coerceIn(0, 100)
                } else {
                    0
                }
                if (id == -1) aggBusy = busy else cores.add(CoreLoad(id, busy))
            }
            cores.sortBy { it.core }
        }
        val (status, headroom) = readThermal(context)
        val snapshot = SystemStatsSnapshot(
            ramAvailMb = mem.avail / 1024,
            ramTotalMb = mem.total / 1024,
            zramUsedMb = mem.swapUsed / 1024,
            zramTotalMb = mem.swapTotal / 1024,
            cpuBusyPct = aggBusy,
            cores = cores,
            thermalStatus = status,
            thermalHeadroom = headroom,
            clusterTemps = readClusterTemps(),
        )
        return snapshot to counters
    }

    private fun readCpu(): CpuCounters {
        val map = HashMap<Int, Pair<Long, Long>>()
        try {
            File("/proc/stat").forEachLine { line ->
                if (!line.startsWith("cpu")) return@forEachLine
                val parts = line.trim().split(Regex("\\s+"))
                val id = if (parts[0] == "cpu") -1 else parts[0].removePrefix("cpu").toIntOrNull()
                    ?: return@forEachLine
                val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
                if (nums.size < 5) return@forEachLine
                val idleAll = nums[3] + nums[4] // idle + iowait
                map[id] = idleAll to nums.sum()
            }
        } catch (_: Exception) {
            // /proc/stat unreadable — leave map empty; CPU% renders as 0.
        }
        return CpuCounters(map)
    }

    private data class Mem(val avail: Long, val total: Long, val swapUsed: Long, val swapTotal: Long)

    private fun readMem(): Mem {
        var total = 0L
        var avail = 0L
        var swapTotal = 0L
        var swapFree = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                val v = line.substringAfter(':', "").trim().removeSuffix(" kB").trim().toLongOrNull()
                    ?: return@forEachLine
                when {
                    line.startsWith("MemTotal:") -> total = v
                    line.startsWith("MemAvailable:") -> avail = v
                    line.startsWith("SwapTotal:") -> swapTotal = v
                    line.startsWith("SwapFree:") -> swapFree = v
                }
            }
        } catch (_: Exception) {
            // unreadable — zeros render harmlessly.
        }
        return Mem(avail, total, (swapTotal - swapFree).coerceAtLeast(0), swapTotal)
    }

    private fun readThermal(context: Context): Pair<String, Float?> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "n/a" to null
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { thermalStatusName(pm.currentThermalStatus) } catch (_: Exception) { "n/a" }
        } else {
            "n/a"
        }
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { pm.getThermalHeadroom(0).takeIf { !it.isNaN() } } catch (_: Exception) { null }
        } else {
            null
        }
        return status to headroom
    }

    private fun thermalStatusName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "?"
    }

    private fun readClusterTemps(): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        try {
            val zones = File("/sys/class/thermal")
                .listFiles { f -> f.name.startsWith("thermal_zone") } ?: return out
            for (z in zones) {
                val type = runCatching { File(z, "type").readText().trim() }.getOrNull() ?: continue
                val l = type.lowercase()
                val wanted = l.contains("cpu") || l.contains("big") || l.contains("mid") ||
                    l.contains("little") || l.contains("soc") || l.contains("skin")
                if (!wanted) continue
                val milli = runCatching { File(z, "temp").readText().trim().toLong() }.getOrNull() ?: continue
                out.add(type to if (milli > 1000) milli / 1000f else milli.toFloat())
                if (out.size >= 4) break
            }
        } catch (_: Exception) {
            // /sys/class/thermal blocked — rely on the PowerManager status above.
        }
        return out
    }
}
