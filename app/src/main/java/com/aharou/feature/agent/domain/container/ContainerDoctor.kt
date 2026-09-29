package com.aharou.feature.agent.domain.container

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.annotation.StringRes
import com.aharou.R
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 体检条目的状态。 */
enum class HealthStatus {
    HEALTHY,
    WARNING,
    ERROR,

    /**
     * 探测拿不到结果（沙箱正忙、超时、异常）。
     *
     * 探测不可达 ≠ 配置出错：如实给灰牌，不计入待修复项，
     * 免得把「沙箱正忙导致的超时」误报成故障。
     */
    UNKNOWN,
}

/**
 * 一条体检结果。
 *
 * 文案以资源 id 形式持有，由 UI 层解析——domain 层不出现用户可见中文。
 */
data class HealthItem(
    val id: String,
    val status: HealthStatus,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val summaryArgs: List<Any> = emptyList(),
    @param:StringRes val detailRes: Int? = null,
    val detailArgs: List<Any> = emptyList(),
)

/** 一次体检的完整结果。 */
data class HealthReport(
    val items: List<HealthItem>,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val overall: HealthStatus
        get() = when {
            items.any { it.status == HealthStatus.ERROR } -> HealthStatus.ERROR
            items.any { it.status == HealthStatus.WARNING } -> HealthStatus.WARNING
            items.any { it.status == HealthStatus.UNKNOWN } -> HealthStatus.UNKNOWN
            else -> HealthStatus.HEALTHY
        }

    val needsFix: Boolean
        get() = items.any { it.status == HealthStatus.ERROR || it.status == HealthStatus.WARNING }
}

/**
 * 容器环境体检。
 *
 * 容器出问题时用户能看到的往往只是一句「命令失败」，而真实原因可能是 DNS 没通、共享存储权限没给、
 * 基础工具没装。逐项探一遍并把结论摆在设置页，省得每次都要靠翻日志反推。
 *
 * 所有探测都走 [LinuxContainerEngine.runCommandSyncIfReady]，未就绪时返回 null——
 * 那说明当前根本没有可探测的沙箱，对应条目记 UNKNOWN 而不是 ERROR。
 */
@Singleton
class ContainerDoctor @Inject constructor(
    private val engine: LinuxContainerEngine,
    @ApplicationContext private val context: Context,
) {
    suspend fun check(): HealthReport = withContext(Dispatchers.IO) {
        HealthReport(
            items = listOf(
                checkStoragePermission(),
                checkBasicTools(),
                checkDns(),
                checkOutboundNetwork(),
            )
        )
    }

    private fun checkStoragePermission(): HealthItem {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        return HealthItem(
            id = ID_STORAGE,
            status = if (granted) HealthStatus.HEALTHY else HealthStatus.WARNING,
            titleRes = R.string.container_health_title_storage,
            summaryRes = if (granted) {
                R.string.container_health_storage_ok
            } else {
                R.string.container_health_storage_missing
            },
            detailRes = if (granted) null else R.string.container_health_storage_missing_detail,
        )
    }

    private suspend fun checkBasicTools(): HealthItem = runProbe(
        id = ID_SANDBOX,
        titleRes = R.string.container_health_title_tools,
        // 逐项报出存在与否：只数总数的话，用户看到「4/5」也不知道该补哪个。
        command = "for t in sh git curl tar xz; do command -v \"\$t\" >/dev/null 2>&1 " +
            "&& echo \"OK \$t\" || echo \"MISSING \$t\"; done",
        timeoutMs = 20_000,
    ) { output, exitCode ->
        if (exitCode != 0 || output.isBlank()) {
            return@runProbe HealthItem(
                id = ID_SANDBOX,
                status = HealthStatus.WARNING,
                titleRes = R.string.container_health_title_tools,
                summaryRes = R.string.container_health_tools_missing,
                detailRes = R.string.container_health_tools_missing_detail,
            )
        }
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val missing = lines
            .filter { it.startsWith("MISSING ") }
            .map { it.removePrefix("MISSING ").trim() }
            .filter { it.isNotEmpty() }
        val present = lines.count { it.startsWith("OK ") }
        HealthItem(
            id = ID_SANDBOX,
            status = if (missing.isEmpty()) HealthStatus.HEALTHY else HealthStatus.WARNING,
            titleRes = R.string.container_health_title_tools,
            summaryRes = R.string.container_health_tools_ready,
            summaryArgs = listOf(present, TOOL_COUNT),
            // 缺哪个直接点名，比一句「可在安装里补装」有用得多。
            detailRes = if (missing.isEmpty()) {
                null
            } else {
                R.string.container_health_tools_missing_names
            },
            detailArgs = if (missing.isEmpty()) emptyList() else listOf(missing.joinToString("、")),
        )
    }

    private suspend fun checkDns(): HealthItem = runProbe(
        id = ID_DNS,
        titleRes = R.string.container_health_title_dns,
        command = "getent hosts github.com || ping -c1 -W2 github.com",
        timeoutMs = 15_000,
    ) { output, exitCode ->
        if (exitCode == 0 && output.isNotBlank()) {
            HealthItem(
                id = ID_DNS,
                status = HealthStatus.HEALTHY,
                titleRes = R.string.container_health_title_dns,
                summaryRes = R.string.container_health_dns_ok,
            )
        } else {
            HealthItem(
                id = ID_DNS,
                status = HealthStatus.ERROR,
                titleRes = R.string.container_health_title_dns,
                summaryRes = R.string.container_health_dns_fail,
                detailRes = R.string.container_health_dns_fail_detail,
            )
        }
    }

    private suspend fun checkOutboundNetwork(): HealthItem = runProbe(
        id = ID_NETWORK,
        titleRes = R.string.container_health_title_network,
        command = "curl -sS -o /dev/null -w '%{http_code}' --max-time 12 https://api.github.com",
        timeoutMs = 30_000,
    ) { output, exitCode ->
        val code = output.trim().takeLast(3)
        if (exitCode == 0 && code.startsWith("2")) {
            HealthItem(
                id = ID_NETWORK,
                status = HealthStatus.HEALTHY,
                titleRes = R.string.container_health_title_network,
                summaryRes = R.string.container_health_net_ok,
                summaryArgs = listOf(code),
            )
        } else {
            HealthItem(
                id = ID_NETWORK,
                status = HealthStatus.WARNING,
                titleRes = R.string.container_health_title_network,
                summaryRes = R.string.container_health_net_fail,
                detailRes = R.string.container_health_net_fail_detail,
            )
        }
    }

    /**
     * 跑一条探测命令并把结果交给 [interpret]。
     *
     * 命令本身没跑起来（沙箱未就绪 / 执行异常）时返回 UNKNOWN 而不是 ERROR——
     * 拿不到结果和查出问题是两回事，混为一谈的体检会整天误报。
     */
    private suspend fun runProbe(
        id: String,
        @StringRes titleRes: Int,
        command: String,
        timeoutMs: Long,
        interpret: (output: String, exitCode: Int) -> HealthItem,
    ): HealthItem {
        val result = runCatching { engine.runCommandSyncIfReady(command, null, timeoutMs) }.getOrNull()
            ?: return HealthItem(
                id = id,
                status = HealthStatus.UNKNOWN,
                titleRes = titleRes,
                summaryRes = R.string.container_health_busy,
                detailRes = R.string.container_health_busy_detail,
            )
        return runCatching { interpret(result.output, result.exitCode ?: -1) }
            .getOrElse { error ->
                FileLogger.w(TAG, "体检项 $id 解析失败: ${error.message}")
                HealthItem(
                    id = id,
                    status = HealthStatus.UNKNOWN,
                    titleRes = titleRes,
                    summaryRes = R.string.container_health_parse_fail,
                    detailRes = R.string.container_health_parse_fail_detail,
                )
            }
    }

    private companion object {
        const val TAG = "ContainerDoctor"
        const val ID_STORAGE = "host_storage_access"
        const val ID_SANDBOX = "container_sandbox"
        const val ID_DNS = "container_dns"
        const val ID_NETWORK = "container_network"
        const val TOOL_COUNT = 5
    }
}
