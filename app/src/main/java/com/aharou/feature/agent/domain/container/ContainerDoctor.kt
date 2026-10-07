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
    /**
     * 缺哪几个基础工具。非空时 UI 会给出「安装」入口。
     *
     * 这里存工具名而不是拼好的句子，UI 要的是「能拿去做安装入参」的原始名字。
     */
    val missingTools: List<String> = emptyList(),
)

/** 自动补装基础工具的结果。 */
sealed interface InstallOutcome {
    /** 命令跑完且退出码为 0。 */
    data class Installed(val packages: List<String>) : InstallOutcome

    /** 沙箱未就绪，没法执行。 */
    data object NotReady : InstallOutcome

    /** 认不出包管理器（既没 apk 也没 apt-get）。 */
    data object NoPackageManager : InstallOutcome

    /** 装了但失败，[output] 是末尾输出，用来告诉用户到底卡在哪。 */
    data class Failed(val output: String) : InstallOutcome
}

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
    private val runtimeProcessStore: RuntimeProcessStore,
    @ApplicationContext private val context: Context,
) {
    suspend fun check(): HealthReport = withContext(Dispatchers.IO) {
        HealthReport(
            items = listOf(
                checkStoragePermission(),
                checkBasicTools(),
                checkStaleProcesses(),
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

    /**
     * 上次运行遗留下来的容器进程：发现即按强身份核验后结束，并如实报出数量。
     *
     * 这里敢自动结束，是因为判定条件足够窄——同一次开机、启动时钟对得上、状态活跃，
     * 三条同时满足的只可能是本 App 自己起过的进程。
     */
    private suspend fun checkStaleProcesses(): HealthItem = withContext(Dispatchers.IO) {
        val stale = runtimeProcessStore.stale()
        if (stale.isEmpty()) {
            return@withContext HealthItem(
                id = ID_STALE,
                status = HealthStatus.HEALTHY,
                titleRes = R.string.container_health_title_stale,
                summaryRes = R.string.container_health_stale_none,
            )
        }
        val stopped = stale.count { runtimeProcessStore.terminate(it) }
        HealthItem(
            id = ID_STALE,
            status = HealthStatus.WARNING,
            titleRes = R.string.container_health_title_stale,
            summaryRes = R.string.container_health_stale_found,
            summaryArgs = listOf(stale.size, stopped),
            detailRes = R.string.container_health_stale_detail,
            detailArgs = listOf(stale.take(3).joinToString("、") { it.command.take(40) }),
        )
    }

    private suspend fun checkBasicTools(): HealthItem {
        // 快路径：原生能证明基础工具全在时直接给结论，省掉一次 PRoot 启动；
        // 只要有一个判不了或判成缺失，就退回下面的容器探测复核。
        if (BASIC_TOOLS.all { engine.probeContainerExecutable(it) == true }) {
            return HealthItem(
                id = ID_SANDBOX,
                status = HealthStatus.HEALTHY,
                titleRes = R.string.container_health_title_tools,
                summaryRes = R.string.container_health_tools_ready,
                summaryArgs = listOf(BASIC_TOOLS.size, TOOL_COUNT),
            )
        }
        return probeBasicToolsInContainer()
    }

    private suspend fun probeBasicToolsInContainer(): HealthItem = runProbe(
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
            missingTools = missing,
        )
    }

    /**
     * 补装缺的基础工具。
     *
     * 装在**运行中的容器**里，不进镜像：重写 rootfs 是分钟级的重建，而缺一个 xz 这类
     * 配套工具不值得让用户为它重建一次。代价是镜像重建后要重新装一次，UI 里已注明。
     *
     * 包管理器靠探测而不是看发行版名：自定义镜像可能既不是标准 Alpine 也不是标准 Debian，
     * 但手里有哪个包管理器是确定的。
     */
    suspend fun installMissingTools(tools: List<String>): InstallOutcome = withContext(Dispatchers.IO) {
        if (tools.isEmpty()) return@withContext InstallOutcome.Installed(emptyList())
        val manager = detectPackageManager() ?: return@withContext InstallOutcome.NoPackageManager
        val packages = tools.map { manager.packageFor(it) }.distinct()
        val result = runCatching {
            engine.runCommandSyncIfReady(manager.installCommand(packages), null, INSTALL_TIMEOUT_MS)
        }.getOrNull() ?: return@withContext InstallOutcome.NotReady
        if ((result.exitCode ?: -1) == 0) {
            InstallOutcome.Installed(packages)
        } else {
            InstallOutcome.Failed(result.output.trim().takeLast(FAILURE_TAIL_CHARS))
        }
    }

    private suspend fun detectPackageManager(): PackageManagerKind? {
        // 快路径：原生说某个包管理器在就直接采信（两个都判不出来才走容器探测复核）
        if (engine.probeContainerExecutable("apk") == true) return PackageManagerKind.APK
        if (engine.probeContainerExecutable("apt-get") == true) return PackageManagerKind.APT
        val result = runCatching {
            engine.runCommandSyncIfReady(
                "command -v apk >/dev/null 2>&1 && echo apk; " +
                    "command -v apt-get >/dev/null 2>&1 && echo apt-get",
                null,
                PROBE_TIMEOUT_MS
            )
        }.getOrNull() ?: return null
        val names = result.output.lines().map { it.trim() }
        return when {
            names.any { it == "apk" } -> PackageManagerKind.APK
            names.any { it == "apt-get" } -> PackageManagerKind.APT
            else -> null
        }
    }

    private enum class PackageManagerKind {
        APK,
        APT;

        /**
         * 工具名到包名的映射。
         *
         * Alpine 下这些工具名就是包名；Debian 系把 xz 放在 xz-utils 里，sh 由 dash 提供。
         * 不在表里的按同名试，比不认识就放弃有用地多。
         */
        fun packageFor(tool: String): String = when (this) {
            APK -> tool
            APT -> APT_PACKAGES[tool] ?: tool
        }

        fun installCommand(packages: List<String>): String {
            val list = packages.joinToString(" ")
            return when (this) {
                APK -> "apk add --no-cache $list"
                // update 先跑：自定义镜像的索引可能从来没刷过，直接 install 会 404。
                APT -> "apt-get update -qq && apt-get install -y --no-install-recommends $list"
            }
        }
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
        const val ID_STALE = "container_stale_processes"
        const val TOOL_COUNT = 5

        /** 体检要确认存在的基础工具，与容器探测命令里的清单保持一致。 */
        val BASIC_TOOLS = listOf("sh", "git", "curl", "tar", "xz")
        const val PROBE_TIMEOUT_MS = 15_000L

        /** 装包要刷新索引再下载，宽松些；再久就该让用户看见失败而不是干等。 */
        const val INSTALL_TIMEOUT_MS = 180_000L
        const val FAILURE_TAIL_CHARS = 400

        val APT_PACKAGES = mapOf(
            "xz" to "xz-utils",
            "sh" to "dash",
        )
    }
}
