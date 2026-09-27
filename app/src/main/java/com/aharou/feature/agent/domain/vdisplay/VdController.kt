package com.aharou.feature.agent.domain.vdisplay

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.shizuku.ShizukuCommandResult
import com.aharou.feature.agent.domain.shizuku.ShizukuManager
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** 影子屏信息。 */
data class VdInfo(
    /** 逻辑 display id（短 id；`am start --display` / `input -d` 用）。 */
    val displayId: Int,
    /** SurfaceFlinger display id（长 id；`screencap -d` 用）。 */
    val sfDisplayId: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

/**
 * 影子屏控制器：在宿主上创建一块**不在设备屏幕显示**的虚拟显示屏。
 *
 * 机制参考并改编自 Genymobile/scrcpy 的 new-display（Apache-2.0，源码见
 * `tools/aharou-vd/`）：资产里内置一个约 3KB 的 runner，经 Shizuku 以 shell
 * 身份 `app_process` 拉起——创建无头虚拟屏后保活；后续启动 App / 截图 / 触控
 * 全部经 Shizuku 命令行完成，用户主屏零打扰。
 */
@Singleton
class VdController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
) {
    companion object {
        private const val TAG = "VdController"

        const val DISPLAY_NAME = "aharou-vd"
        const val JAR_REMOTE = "/data/local/tmp/aharou-vd.jar"
        const val STOP_FILE = "/data/local/tmp/aharou-vd.stop"
        const val LOG_FILE = "/data/local/tmp/vd.log"
        const val ASSET_PATH = "vd/aharou-vd.jar"

        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DPI = 440
    }

    private val _state = MutableStateFlow<VdInfo?>(null)
    val state: StateFlow<VdInfo?> = _state.asStateFlow()

    private val shizukuReady: Boolean
        get() = shizukuManager.state.value == ShizukuState.READY

    private suspend fun exec(command: String, timeoutMs: Long = 60_000L): ShizukuCommandResult {
        check(shizukuReady) { "Shizuku 未就绪（${shizukuManager.state.value}），无法使用影子屏" }
        return shizukuManager.runCommand(command, timeoutMs)
    }

    /** 把资产里的 runner 部署到 /data/local/tmp（md5 不同才覆盖）。 */
    suspend fun ensureDeployed() {
        val local = exportAsset()
        val localMd5 = md5Hex(local)
        val remote = exec("md5sum $JAR_REMOTE 2>/dev/null | awk '{print \$1}'", 20_000L).output.trim()
        if (remote != localMd5) {
            val r = exec("cp \"${local.absolutePath}\" $JAR_REMOTE && chmod 644 $JAR_REMOTE && echo DEPLOYED", 30_000L)
            check(r.output.contains("DEPLOYED")) { "部署 runner 失败：${r.output.take(200)}" }
            FileLogger.i(TAG, "runner 已部署（md5=$localMd5）")
        }
    }

    private fun exportAsset(): File {
        val dir = File(context.getExternalFilesDir(null), "vd").apply { mkdirs() }
        val local = File(dir, "aharou-vd.jar")
        context.assets.open(ASSET_PATH).use { input ->
            local.outputStream().use { output -> input.copyTo(output) }
        }
        return local
    }

    /** 启动影子屏（已在运行则直接返回现有信息）。 */
    suspend fun start(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        dpi: Int = DEFAULT_DPI,
    ): VdInfo {
        refresh()?.let { return it }
        ensureDeployed()
        exec(
            "cd /data/local/tmp; rm -f $STOP_FILE $LOG_FILE; " +
                "CLASSPATH=$JAR_REMOTE setsid app_process / com.aharou.vd.VdMain $width $height $dpi " +
                "> $LOG_FILE 2>&1 < /dev/null & echo LAUNCHED",
            30_000L,
        )
        var id = -1
        for (attempt in 0 until 20) {
            delay(500L)
            val log = exec("cat $LOG_FILE 2>/dev/null", 10_000L).output
            val match = Regex("VD_READY id=(\\d+)").find(log)
            if (match != null) {
                id = match.groupValues[1].toInt()
                break
            }
            if (log.contains("VD_ERROR") || log.contains("VD_FAILED")) {
                error("影子屏启动失败：${log.take(400)}")
            }
        }
        check(id >= 0) {
            "影子屏启动超时（日志尾部：${exec("tail -5 $LOG_FILE 2>/dev/null", 10_000L).output.take(300)}）"
        }
        val info = refresh() ?: VdInfo(id, "", width, height, dpi)
        _state.value = info
        return info
    }

    /** 停止影子屏。 */
    suspend fun stop() {
        exec("touch $STOP_FILE && echo OK", 15_000L)
        delay(600L)
        _state.value = refresh()
    }

    /** 刷新当前状态；未运行返回 null。 */
    suspend fun refresh(): VdInfo? {
        if (!shizukuReady) {
            _state.value = null
            return null
        }
        val out = runCatching {
            exec(
                "cat $LOG_FILE 2>/dev/null; dumpsys SurfaceFlinger --display-id | grep $DISPLAY_NAME",
                20_000L,
            ).output
        }.getOrDefault("")
        val id = Regex("VD_READY id=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val sf = Regex("Display (\\d+) \\(Virtual display\\): displayName=\"$DISPLAY_NAME\"")
            .find(out)?.groupValues?.get(1)
        val w = Regex("size=(\\d+)x(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_WIDTH
        val h = Regex("size=(\\d+)x(\\d+)").find(out)?.groupValues?.get(2)?.toIntOrNull() ?: DEFAULT_HEIGHT
        val dpi = Regex("dpi=(\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_DPI
        val info = if (id != null && !sf.isNullOrEmpty()) VdInfo(id, sf, w, h, dpi) else null
        _state.value = info
        return info
    }

    // component / keycode 会被拼进 Shizuku（adb shell, uid 2000）执行的命令，
    // 必须先白名单校验再拼——否则 `com.x/.Y; 任意命令` 能在宿主上跑。
    private val componentSlashRe = Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$")
    private val packageNameRe = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*$")
    private val keyCodeRe = Regex("^(KEYCODE_[A-Z0-9_]+|[0-9]{1,4})$")

    /** 在影子屏上启动应用（component 可传 "pkg/act" 或仅 "pkg"）。 */
    suspend fun launch(component: String): VdInfo {
        val info = refresh() ?: error("影子屏未运行，请先 start")
        val raw = component.trim()
        val comp = if (raw.contains('/')) {
            require(componentSlashRe.matches(raw)) { "component 格式不合法：$raw" }
            raw
        } else {
            require(packageNameRe.matches(raw)) { "包名格式不合法：$raw" }
            exec("cmd package resolve-activity --brief $raw 2>/dev/null | tail -1", 20_000L).output.trim()
        }
        check(comp.contains('/')) { "找不到可启动的入口：$component" }
        val r = exec("am start --display ${info.displayId} -n $comp", 30_000L)
        check(!r.output.contains("Error") && !r.output.contains("Exception")) { "启动失败：${r.output.take(300)}" }
        return info
    }

    /** 截图影子屏 → 返回本地 PNG 文件（传入已知 [known] 时跳过状态探测，供高频预览取帧用）。 */
    suspend fun screenshot(known: VdInfo? = null): Pair<VdInfo, File> {
        val info = known ?: refresh() ?: error("影子屏未运行，请先 start")
        val shot = File(context.getExternalFilesDir(null), "vd/shot.png")
        if (shot.exists()) shot.delete()
        val r = exec("screencap -d ${info.sfDisplayId} -p \"${shot.absolutePath}\"", 30_000L)
        check(shot.exists() && shot.length() > 0) { "截图失败：${r.output.take(300)}" }
        return info to shot
    }

    /** 在影子屏上点按。 */
    suspend fun tap(x: Int, y: Int) {
        val info = refresh() ?: error("影子屏未运行")
        exec("input -d ${info.displayId} tap $x $y", 20_000L)
    }

    /** 在影子屏上滑动。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        val info = refresh() ?: error("影子屏未运行")
        exec("input -d ${info.displayId} swipe $x1 $y1 $x2 $y2 $durationMs", 20_000L)
    }

    /** 在影子屏上发送按键（如 KEYCODE_BACK / KEYCODE_HOME / 4 / 3）。 */
    suspend fun keyEvent(code: String) {
        val info = refresh() ?: error("影子屏未运行")
        val c = code.trim()
        require(keyCodeRe.matches(c)) { "keycode 不合法：$c" }
        exec("input -d ${info.displayId} keyevent $c", 20_000L)
    }

    private fun md5Hex(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
