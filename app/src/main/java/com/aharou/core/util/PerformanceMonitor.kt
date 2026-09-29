package com.aharou.core.util

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 单次采样的结果。速率与占用都是「相对上一次采样」的差分值。 */
data class PerformanceSnapshot(
    val timestampMs: Long,
    /** 本应用主进程 CPU 占用，已按核心数归一化；多核满载为 100。 */
    val appCpuPercent: Double,
    val appPssKb: Long,
    val appThreadCount: Int,
    /**
     * 整机 CPU 占用。
     *
     * null 表示读不到：Android 10 起 /proc/stat 对普通应用返回 Permission denied，
     * 拿不到就如实留空，不要假装是 0%（那会让人以为设备完全空闲）。
     */
    val deviceCpuPercent: Double?,
    val deviceTotalMb: Long,
    val deviceAvailMb: Long,
    /** null 表示内核不提供该计数器（拿不到就如实留空，不捏造 0）。 */
    val appRxBytesPerSec: Long?,
    val appTxBytesPerSec: Long?,
    val deviceRxBytesPerSec: Long?,
    val deviceTxBytesPerSec: Long?,
)

/**
 * 进程级性能采样器。
 *
 * 只在界面打开期间采样（[start] / [stop]），不做常驻后台轮询——采样本身要读 /proc 与
 * TrafficStats，没必要在用户不看的时候一直跑。历史窗口仅存内存，不落盘。
 *
 * CPU 走 /proc：`/proc/self/stat` 是主进程，`/proc/stat` 是整机。
 * 内存走 Debug.MemoryInfo 与 ActivityManager，网络走 TrafficStats。
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _snapshot = MutableStateFlow<PerformanceSnapshot?>(null)
    val snapshot: StateFlow<PerformanceSnapshot?> = _snapshot.asStateFlow()

    /** CPU 历史（最近 [HISTORY_SIZE] 个样本），供曲线图读取。 */
    private val _cpuHistory = MutableStateFlow<List<Double>>(emptyList())
    val cpuHistory: StateFlow<List<Double>> = _cpuHistory.asStateFlow()

    private var samplingJob: Job? = null

    /** 上一次的原始计数，用于算差分。采样重启时必须清空，否则会拿跨会话的巨大间隔算出假速率。 */
    private var lastAppCpuTicks: Long? = null
    private var lastDeviceCpuTicks: DeviceCpuTicks? = null
    private var lastNetwork: NetworkCounters? = null
    private var lastSampleAtMs: Long = 0L

    fun start(scope: CoroutineScope) {
        if (samplingJob?.isActive == true) return
        lastAppCpuTicks = null
        lastDeviceCpuTicks = null
        lastNetwork = null
        lastSampleAtMs = 0L
        samplingJob = scope.launch(Dispatchers.IO) {
            while (true) {
                sampleOnce()
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        samplingJob?.cancel()
        samplingJob = null
    }

    private fun sampleOnce() {
        val now = System.currentTimeMillis()
        val elapsedMs = if (lastSampleAtMs == 0L) 0L else now - lastSampleAtMs
        lastSampleAtMs = now

        val appStat = readProcStat("/proc/self/stat")
        val deviceCpu = readDeviceCpu()
        val network = readNetworkCounters()

        val appCpuTicks = appStat?.let { it.utime + it.stime }
        val prevAppTicks = lastAppCpuTicks
        // 首次采样（elapsedMs == 0）只记录基线，不产出速率——没有间隔就没有速率。
        val appCpuPercent = if (elapsedMs > 0L && appCpuTicks != null && prevAppTicks != null) {
            ticksToPercent(appCpuTicks - prevAppTicks, elapsedMs, Runtime.getRuntime().availableProcessors())
        } else {
            0.0
        }
        lastAppCpuTicks = appCpuTicks

        val prevDeviceCpu = lastDeviceCpuTicks
        val deviceCpuPercent = if (elapsedMs > 0L && deviceCpu != null && prevDeviceCpu != null) {
            val busyDelta = (deviceCpu.total - deviceCpu.idle) - (prevDeviceCpu.total - prevDeviceCpu.idle)
            val totalDelta = deviceCpu.total - prevDeviceCpu.total
            if (totalDelta > 0L) (busyDelta.toDouble() / totalDelta * 100.0).coerceIn(0.0, 100.0) else 0.0
        } else {
            // 首次采样与「读不到 /proc/stat」都归为空：后者在 Android 10+ 是常态。
            null
        }
        lastDeviceCpuTicks = deviceCpu

        val seconds = elapsedMs / 1000.0
        val appRx = diffRate(network?.uidRx, lastNetwork?.uidRx, seconds)
        val appTx = diffRate(network?.uidTx, lastNetwork?.uidTx, seconds)
        val deviceRx = diffRate(network?.deviceRx, lastNetwork?.deviceRx, seconds)
        val deviceTx = diffRate(network?.deviceTx, lastNetwork?.deviceTx, seconds)
        lastNetwork = network

        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val deviceMemory = readDeviceMemory()

        _snapshot.value = PerformanceSnapshot(
            timestampMs = now,
            appCpuPercent = appCpuPercent,
            appPssKb = memory.totalPss.toLong(),
            appThreadCount = File("/proc/self/task").list()?.size ?: 0,
            deviceCpuPercent = deviceCpuPercent,
            deviceTotalMb = deviceMemory?.first ?: 0L,
            deviceAvailMb = deviceMemory?.second ?: 0L,
            appRxBytesPerSec = appRx,
            appTxBytesPerSec = appTx,
            deviceRxBytesPerSec = deviceRx,
            deviceTxBytesPerSec = deviceTx,
        )
        _cpuHistory.value = (_cpuHistory.value + appCpuPercent).takeLast(HISTORY_SIZE)
    }

    /**
     * 解析 /proc/<pid>/stat。
     *
     * 字段数不固定（comm 里可能含空格甚至括号），所以从**最后一个** ')' 之后开始切，
     * 这样字段 N 恒对应索引 N-3。
     */
    private fun readProcStat(path: String): ProcStat? = runCatching {
        val text = File(path).readText()
        val closeParen = text.lastIndexOf(')')
        if (closeParen < 0) return null
        val fields = text.substring(closeParen + 2).split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
        ProcStat(utime = utime, stime = stime)
    }.getOrNull()

    private fun readDeviceCpu(): DeviceCpuTicks? = runCatching {
        File("/proc/stat").bufferedReader().use { reader ->
            val line = reader.readLine() ?: return null
            if (!line.startsWith("cpu ")) return null
            val values = line.trim().split(Regex("\\s+")).drop(1).map { it.toLongOrNull() ?: 0L }
            // user nice system idle iowait …；idle + iowait 为空闲
            val idle = (values.getOrNull(3) ?: 0L) + (values.getOrNull(4) ?: 0L)
            DeviceCpuTicks(total = values.sum(), idle = idle)
        }
    }.getOrNull()

    private fun readNetworkCounters(): NetworkCounters? {
        val uidRx = TrafficStats.getUidRxBytes(Process.myUid())
        val uidTx = TrafficStats.getUidTxBytes(Process.myUid())
        val deviceRx = TrafficStats.getTotalRxBytes()
        val deviceTx = TrafficStats.getTotalTxBytes()
        if (uidRx < 0 || uidTx < 0 || deviceRx < 0 || deviceTx < 0) {
            // TrafficStats.UNSUPPORTED：内核没提供计数，上报不可用而不是捏造 0
            return null
        }
        return NetworkCounters(uidRx = uidRx, uidTx = uidTx, deviceRx = deviceRx, deviceTx = deviceTx)
    }

    private fun readDeviceMemory(): Pair<Long, Long>? {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        return Pair(info.totalMem / 1024L / 1024L, info.availMem / 1024L / 1024L)
    }

    private fun diffRate(current: Long?, previous: Long?, seconds: Double): Long? {
        if (current == null || previous == null || seconds <= 0.0) return null
        val delta = current - previous
        // 计数器回绕或重置时 delta 为负，丢弃这个样本好过报一个负速率。
        if (delta < 0L) return null
        return (delta / seconds).toLong()
    }

    private fun ticksToPercent(deltaTicks: Long, deltaMs: Long, coreCount: Int): Double {
        if (deltaTicks <= 0L || deltaMs <= 0L || coreCount <= 0) return 0.0
        val cpuSeconds = deltaTicks.toDouble() / CLOCK_TICKS_PER_SECOND
        val wallSeconds = deltaMs / 1000.0
        return ((cpuSeconds / wallSeconds) / coreCount * 100.0).coerceIn(0.0, 100.0)
    }

    private data class ProcStat(val utime: Long, val stime: Long)

    private data class DeviceCpuTicks(val total: Long, val idle: Long)

    private data class NetworkCounters(
        val uidRx: Long,
        val uidTx: Long,
        val deviceRx: Long,
        val deviceTx: Long,
    )

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1000L
        const val HISTORY_SIZE = 60

        /** Android 上 _SC_CLK_TCK 恒为 100。 */
        const val CLOCK_TICKS_PER_SECOND = 100.0
    }
}

/** 把 KB 格式化成带单位的字符串，供界面直接展示。 */
fun formatMemoryKb(kb: Long): String =
    if (kb >= 1024L) {
        String.format(Locale.US, "%.1f MB", kb / 1024.0)
    } else {
        "$kb KB"
    }

/** 把每秒字节数格式化成人能读的速率。 */
fun formatRatePerSec(bytesPerSec: Long?): String {
    if (bytesPerSec == null) return "—"
    val kb = bytesPerSec / 1024.0
    return if (kb >= 1024.0) {
        String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
    } else {
        String.format(Locale.US, "%.1f KB/s", kb)
    }
}
