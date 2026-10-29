package com.aharou.feature.agent.domain.vdisplay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shell.ShellCommandResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 影子屏上的一个控件（由 runner 以 shell 身份的 UiAutomation 读出，不依赖用户开无障碍服务）。
 * [flags]：c 可点 / e 可编辑 / x、X 可勾选（X 已勾）/ s 可滚动 / f 有焦点 / - 禁用。
 */
data class VdNode(
    val index: Int,
    val text: String,
    val desc: String,
    val resourceId: String,
    val className: String,
    val flags: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val clickable: Boolean get() = 'c' in flags
    val editable: Boolean get() = 'e' in flags

    /** 给模型看的一行：文字（或描述）· 类型 [标志] @ 中心点像素坐标。 */
    fun render(): String {
        val label = text.ifBlank { desc }.ifBlank { resourceId.substringAfterLast('/') }
        val kind = className.substringAfterLast('.')
        return "$label · $kind${if (flags.isNotEmpty()) " [$flags]" else ""} @ $centerX,$centerY"
    }
}

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
 * `tools/aharou-vd/`）：资产里内置一个约 3KB 的 runner，经 root 或 Shizuku 以 shell
 * 身份 `app_process` 拉起——创建无头虚拟屏后保活；后续启动 App / 截图 / 触控
 * 全部经同一条命令行通道完成，用户主屏零打扰。
 */
@Singleton
class VdController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hostShell: HostShellManager,
) {
    companion object {
        private const val TAG = "VdController"

        const val ASSET_PATH = "vd/aharou-vd.jar"

        /** 命令通道：等 runner 取走命令、写回结果的轮询间隔与上限。 */
        const val CTL_POLL_MS = 30L
        const val CTL_WAIT_MS = 8_000L

        /**
         * 按包名取实例后缀：正式包为空（沿用旧文件名），`.debug` / `.beta` 各带后缀。
         * 两个包同机时各开各的影子屏；共用一块屏时，两边都往里发点击，用户的按键会被打乱。
         */
        internal fun instanceSuffix(packageName: String): String =
            packageName.removePrefix("com.aharou.agent").trimStart('.')
                .replace(Regex("[^A-Za-z0-9_]"), "_")
                .let { if (it.isEmpty()) "" else "-$it" }

        /** 等 runner 出图的轮询间隔与上限。 */
        const val SHOT_POLL_MS = 200L
        const val SHOT_WAIT_MS = 5_000L

        /** 从 runner 的命令行里取截图输出路径；取不到返回 null。 */
        internal fun parseRunnerShotPath(psArgs: String): String? =
            Regex("\"([^\"]+\\.png)\"").find(psArgs)?.groupValues?.get(1)

        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DPI = 440

        /** KEYCODE_PASTE：让目标输入框自己从剪贴板取文本，中文等非 ASCII 走这条路。 */
        const val KEYCODE_PASTE = 279
    }

    private val suffix = instanceSuffix(context.packageName)
    val displayName = "aharou-vd$suffix"
    private val jarRemote = "/data/local/tmp/aharou-vd$suffix.jar"
    private val stopFile = "/data/local/tmp/aharou-vd$suffix.stop"
    private val logFile = "/data/local/tmp/vd$suffix.log"
    private val shotReqFile = "/data/local/tmp/aharou-vd$suffix.shot"

    /** 命令通道目录：落在 App 私有外部目录，runner（shell 身份）可写、App 可直接读写，不经 Shizuku。 */
    private val ctlDir: File
        get() = File(context.getExternalFilesDir(null), "vd/ctl")

    private val ctlSeq = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    /** runner 是否带命令通道（旧版 runner 没有）；null = 还没探测。 */
    @Volatile
    private var ctlAvailable: Boolean? = null

    /** runner 能不能读控件、写文字（UiAutomation 连上没）；null = 还没探测。 */
    @Volatile
    private var uiaAvailable: Boolean? = null

    private val _state = MutableStateFlow<VdInfo?>(null)
    val state: StateFlow<VdInfo?> = _state.asStateFlow()

    /**
     * runner 出图的落点：App 私有外部目录。runner 以 shell/root 身份可写，App 又能直接读，
     * 二进制不经 shell 文本通道（那是给命令输出用的）。
     */
    /** 文件名带实例后缀：正式包与调试包各自开影子屏时，画面不会互相覆盖。 */
    private val shotFileName = "vd/frame-${java.util.UUID.randomUUID().toString().take(8)}.png"

    /**
     * runner 实际写图的路径。本实例自己启动时就是我们传进去的那个名字；复用别的实例
     * （App 重启后残留、或同机另一个包）留下的 runner 时，输出路径由**启动者**决定，
     * 必须从它的命令行参数里读回来，否则截图会一直等一个没人写的文件。
     */
    @Volatile
    private var runnerShotPath: String = defaultShotPath()

    /** 本实例是否亲手起过 runner——只有没起过才需要去读别人的启动参数。 */
    @Volatile
    private var startedHere = false

    private fun defaultShotPath(): String =
        File(context.getExternalFilesDir(null), shotFileName).absolutePath

    private val shotOutFile: File
        get() = File(runnerShotPath)

    private suspend fun exec(command: String, timeoutMs: Long = 60_000L): ShellCommandResult {
        val mode = hostShell.ensureMode()
        check(mode != HostShellMode.UNAVAILABLE) {
            "影子屏需要 root 或 Shizuku 就绪（当前通道：$mode，Shizuku: ${hostShell.shizukuState.value}）"
        }
        return hostShell.run(command, timeoutMs)
    }

    /** 把资产里的 runner 部署到 /data/local/tmp（md5 不同才覆盖）。 */
    suspend fun ensureDeployed() {
        val local = exportAsset()
        val localMd5 = md5Hex(local)
        val remote = exec("md5sum $jarRemote 2>/dev/null | awk '{print \$1}'", 20_000L).output.trim()
        if (remote != localMd5) {
            val r = exec("cp \"${local.absolutePath}\" $jarRemote && chmod 644 $jarRemote && echo DEPLOYED", 30_000L)
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
        refresh()?.let { running ->
            if (ctlAvailable == null) probeChannel()
            if (ctlAvailable == true) return running
            // 升级前起的旧 runner 没有命令通道：照样复用的话，不弹键盘、直接写字这些都不生效。
            FileLogger.i(TAG, "影子屏 runner 是旧版或非本实例启动，重建")
            stop()
        }
        ensureDeployed()
        // 自己起就把随机名定下来；之后截图按这个路径等 runner 出图。
        runnerShotPath = defaultShotPath()
        startedHere = true
        ctlDir.apply { deleteRecursively(); mkdirs() }
        ctlAvailable = null
        uiaAvailable = null
        // 参数顺序与 VdMain.main 一一对应；截图路径必须是命令行里唯一带引号的 .png（parseRunnerShotPath 靠它读回）。
        exec(
            "cd /data/local/tmp; rm -f $stopFile $logFile; " +
                "CLASSPATH=$jarRemote setsid app_process / com.aharou.vd.VdMain $width $height $dpi " +
                "\"${shotOutFile.absolutePath}\" $shotReqFile $displayName $stopFile ${shellQuote(ctlDir.absolutePath)} " +
                "> $logFile 2>&1 < /dev/null & echo LAUNCHED",
            30_000L,
        )
        var id = -1
        var log = ""
        for (attempt in 0 until 20) {
            delay(500L)
            log = exec("cat $logFile 2>/dev/null", 10_000L).output
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
            "影子屏启动超时（日志尾部：${exec("tail -5 $logFile 2>/dev/null", 10_000L).output.take(300)}）"
        }
        // VD_CTL 紧跟在 VD_READY 后面打印，再等一下。
        for (attempt in 0 until 6) {
            if (log.contains("VD_CTL")) break
            delay(300L)
            log = exec("cat $logFile 2>/dev/null", 10_000L).output
        }
        ctlAvailable = log.contains("VD_CTL")
        uiaAvailable = log.contains("VD_CTL ok")
        FileLogger.i(TAG, "影子屏就绪：${log.lineSequence().filter { it.startsWith("VD_") }.joinToString(" | ")}")
        val info = refresh() ?: VdInfo(id, "", width, height, dpi)
        // DisplayInfo 不跟着改的话，App 拿到的 screenWidthDp 仍是物理设备的值
        // （363dp = 手机档），宽屏/平板分支永远走不到，也就模拟不出大屏布局。
        runCatching {
            exec("wm size -d ${info.displayId} ${width}x$height", 15_000L)
            exec("wm density -d ${info.displayId} $dpi", 15_000L)
        }.onFailure { FileLogger.i(TAG, "设置影子屏尺寸失败：${it.message}") }
        _state.value = info
        return info
    }

    /** 停止影子屏。 */
    suspend fun stop() {
        exec("touch $stopFile && echo OK", 15_000L)
        delay(600L)
        startedHere = false
        ctlAvailable = null
        uiaAvailable = null
        _state.value = refresh()
    }

    /** 刷新当前状态；未运行返回 null。 */
    suspend fun refresh(): VdInfo? {
        if (hostShell.ensureMode() == HostShellMode.UNAVAILABLE) {
            _state.value = null
            return null
        }
        // 逻辑 display id / 尺寸 / density 都从 DisplayManager 侧取：runner 报的 VD_READY 是创建
        // 瞬间的编号，之后系统会重排（实测 runner 报 5、AM 实际是 8），拿它去 am/input 会打偏。
        // mViewports 的字段随 Android 版本变（14+ 有 densityDpi，13 没有，density 只在
        // DisplayDeviceInfo 行里），别把某一版的字段当成必需项。
        val displayDump = runCatching {
            exec("dumpsys display | grep -E 'mViewports|\"$displayName\":'", 20_000L).output
        }.getOrDefault("")
        // uniqueId 形如 virtual:<包名>,<uid>,<显示名>,<序号>：显示名前后必须是逗号，
        // 否则 aharou-vd 会误配上 aharou-vd-debug，正式包就去操作调试包的屏。
        val vp = Regex(
            "type=VIRTUAL[^}]*?displayId=(\\d+)[^}]*?uniqueId='[^']*,${Regex.escape(displayName)},[^']*'" +
                "[^}]*?deviceWidth=(\\d+), deviceHeight=(\\d+)",
        ).find(displayDump)
        val id = vp?.groupValues?.get(1)?.toIntOrNull()
        val w = vp?.groupValues?.get(2)?.toIntOrNull() ?: DEFAULT_WIDTH
        val h = vp?.groupValues?.get(3)?.toIntOrNull() ?: DEFAULT_HEIGHT
        val dpi = Regex("density (\\d+)").find(displayDump)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("densityDpi=(\\d+)").find(displayDump)?.groupValues?.get(1)?.toIntOrNull()
            ?: DEFAULT_DPI
        // 截图要的是 SurfaceFlinger 的 display token。Android 14+ 的 --display-id 会列出虚拟屏，
        // Android 13 只列物理屏，得从 DisplayDevice 行（Display <id> (virtual, "aharou-vd")）里捞。
        val sfOut = runCatching {
            exec(
                "dumpsys SurfaceFlinger --display-id; dumpsys SurfaceFlinger | grep -F '\"$displayName\")'",
                20_000L,
            ).output
        }.getOrDefault("")
        val sf = Regex("Display (\\d+) \\(Virtual display\\): displayName=\"${Regex.escape(displayName)}\"")
            .find(sfOut)?.groupValues?.get(1)
            ?: Regex("Display (\\d+) \\(virtual, \"${Regex.escape(displayName)}\"\\)")
                .find(sfOut)?.groupValues?.get(1)
        val info = if (id != null) VdInfo(id, sf.orEmpty(), w, h, dpi) else null
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
        val pkg = comp.substringBefore('/')
        // 直接往影子屏启动，**不走 move-stack**。move-stack 那条路要先把应用在主屏拉起来、
        // 再把它整个搬进影子屏：屏幕上会先闪出这个应用，目标应用若本就在主屏运行还会被连任务搬走
        // （用户会看到屏幕跳转）。曾经以为 shell 用 `--display` 会被 SecurityException 挡下，
        // 实测在本屏（带 TRUSTED/OWN_FOCUS 标志）不成立，直启就能落上去。
        val r = exec("am start --display ${info.displayId} -f 0x10000000 -n $comp", 30_000L)
        val out = r.output
        check(!out.contains("Error") && !out.contains("Exception")) { "启动失败：${out.take(300)}" }
        // am 对「已在别处运行的实例」会回一句 Warning 并直接交旗，那种情况下这一步不会出现在影子屏。
        for (attempt in 0 until 8) {
            delay(400L)
            if (hasTaskOnDisplay(pkg, info.displayId)) return refresh() ?: info
        }
        error(
            "$comp 没能落到影子屏：多半是它已经运行在别的屏幕上，系统把 intent 交给了那个实例。" +
                "先在那边退出它再试（不做 move-stack 搬家，以免动到用户当前屏幕）。"
        )
    }

    /** 目标包是否已是 [displayId] 那块屏的栈顶（每块屏只有一条 topResumedActivity，用作落屏校验）。 */
    private suspend fun hasTaskOnDisplay(pkg: String, displayId: Int): Boolean = runCatching {
        // 不能改用「整段 section 里 grep 包名」：屏幕段之后还跟着全局区域，会把别的屏上的包一起数进来。
        exec(
            "dumpsys activity activities | awk '/Display #$displayId \\(activities from top to bottom\\)/{f=1;next}" +
                " f&&/Display #/{exit} f&&/topResumedActivity/{print;exit}'",
            15_000L,
        ).output.contains("$pkg/")
    }.getOrDefault(false)

    /**
     * 截图影子屏 → 返回本地 PNG 文件（传入已知 [known] 时跳过状态探测，供高频预览取帧用）。
     *
     * 走 runner 自己出图：Android 13 的 `screencap` 只认物理屏 token，对虚拟屏会静默写出
     * 0 字节文件（14+ 才支持），所以改成「touch 请求文件 → runner 写 PNG → 直接读结果」。
     */
    suspend fun screenshot(known: VdInfo? = null): Pair<VdInfo, File> {
        val info = known ?: refresh() ?: error("影子屏未运行，请先 start")
        check(info.sfDisplayId.isNotEmpty()) {
            "拿不到影子屏的显示 token（SurfaceFlinger 里没列出这块屏），暂时无法截图"
        }
        val shot = File(resolveRunnerShotPath())
        shot.parentFile?.mkdirs()
        if (shot.exists()) shot.delete()
        exec("touch $shotReqFile", 10_000L)
        var waited = 0L
        while (waited < SHOT_WAIT_MS && !(shot.exists() && shot.length() > 0)) {
            delay(SHOT_POLL_MS)
            waited += SHOT_POLL_MS
        }
        check(shot.exists() && shot.length() > 0) {
            "截图失败：runner 未在 ${SHOT_WAIT_MS / 1000} 秒内产出画面" +
                "（日志尾部：${exec("tail -3 $logFile 2>/dev/null", 10_000L).output.take(200)}）"
        }
        return info to shot
    }

    /**
     * 取 runner 实际在写的截图路径。复用别人启动的 runner 时，从它的命令行里读回来
     * （`VdMain <w> <h> <dpi> "<png路径>" <请求文件>`，路径是唯一带引号的参数）。
     *
     * 另一个 Aharou 实例（正式包 / 调试包）的 runner 把图写在自己包的目录里，我们读不到——
     * 这种情况明确报错让人重建，而不是干等到超时。
     */
    private suspend fun resolveRunnerShotPath(): String {
        if (startedHere) return runnerShotPath
        // 正式包与调试包各有一个 runner，按显示名（前后带空格）挑出本实例那一个。
        val args = runCatching {
            exec("ps -A -o args | grep 'com[.]aharou[.]vd[.]VdMain'", 10_000L).output
        }.getOrDefault("").lineSequence().firstOrNull { " $displayName " in " $it " }.orEmpty()
        val path = parseRunnerShotPath(args)
        val ownDir = context.getExternalFilesDir(null)?.absolutePath
        check(path != null && ownDir != null && path.startsWith(ownDir)) {
            "影子屏由另一个 Aharou 实例启动（截图写在 ${path ?: "未知路径"}），先 stop 再 start"
        }
        runnerShotPath = path
        return path
    }

    // ------------------------------------------------------------------ 命令通道

    /** runner 执行一条命令的结果。 */
    private sealed interface CtlResult {
        data class Ok(val body: String) : CtlResult
        data class Err(val reason: String) : CtlResult
    }

    /**
     * 让常驻的 runner 执行一条命令：写 `cmd-<序号>.txt`，等它写回 `res-<序号>.txt`。
     * 全程只读写本 App 自己的目录，不经 Shizuku；点一下不必再现拉一个 `input` 进程，也就不会
     * 因为那几百毫秒的延迟点在界面还没就绪的时候。
     *
     * 返回 null 表示通道不可用（旧版 runner、不是本实例起的、或超时没人取），调用方退回旧路径。
     */
    private suspend fun runnerCommand(vararg lines: String): CtlResult? {
        if (ctlAvailable == null) probeChannel()
        if (ctlAvailable != true) return null
        val dir = ctlDir.apply { mkdirs() }
        val seq = "%016d".format(ctlSeq.incrementAndGet())
        val res = File(dir, "res-$seq.txt")
        val tmp = File(dir, "cmd-$seq.tmp")
        tmp.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        // runner 只收 .txt，先写 .tmp 再改名，它就不会读到写了一半的命令。
        if (!tmp.renameTo(File(dir, "cmd-$seq.txt"))) {
            tmp.delete()
            return null
        }
        var waited = 0L
        while (waited < CTL_WAIT_MS) {
            if (res.exists()) {
                val body = runCatching { res.readText(Charsets.UTF_8) }.getOrNull()
                res.delete()
                if (body != null) {
                    val head = body.substringBefore('\n')
                    val rest = body.substringAfter('\n', "")
                    return if (head == "OK") CtlResult.Ok(rest) else CtlResult.Err(rest.ifBlank { head })
                }
            }
            delay(CTL_POLL_MS)
            waited += CTL_POLL_MS
        }
        File(dir, "cmd-$seq.txt").delete()
        FileLogger.w(TAG, "runner 命令超时未取走：${lines.firstOrNull()}，本轮改走旧路径")
        ctlAvailable = false
        return null
    }

    /**
     * App 重启后复用之前起的 runner 时，不知道它带不带命令通道：看它的命令行里有没有本实例的
     * 控制目录，有就 ping 一次。只探一次，结果缓存到下次 start / stop。
     */
    private suspend fun probeChannel() {
        val args = runCatching {
            exec("ps -A -o args | grep 'com[.]aharou[.]vd[.]VdMain'", 10_000L).output
        }.getOrDefault("").lineSequence().firstOrNull { " $displayName " in " $it " }.orEmpty()
        if (ctlDir.absolutePath !in args) {
            ctlAvailable = false
            uiaAvailable = false
            return
        }
        ctlAvailable = true
        when (val r = runnerCommand("ping")) {
            is CtlResult.Ok -> uiaAvailable = r.body.startsWith("uiautomation")
            else -> uiaAvailable = false
        }
    }

    /** 影子屏能不能读控件 / 直接写字；不能时报原因（供工具转告模型）。 */
    fun nodeTreeUnavailableReason(): String? = when {
        ctlAvailable != true -> "影子屏 runner 没有命令通道（旧版或不是本实例启动的），先 stop 再 start"
        uiaAvailable != true -> "影子屏读不到控件：本机不允许 shell 身份连 UiAutomation（或已被别的自动化工具占用）"
        else -> null
    }

    /**
     * 读影子屏上的控件（可点、可编辑或带文字的）。拿不到时抛错，错误里写明原因。
     */
    suspend fun nodes(): List<VdNode> {
        currentInfo()
        if (ctlAvailable == null) probeChannel()
        nodeTreeUnavailableReason()?.let { error(it) }
        return when (val r = runnerCommand("dump")) {
            is CtlResult.Ok -> parseNodes(r.body)
            is CtlResult.Err -> error("读控件失败：${r.reason.take(200)}")
            null -> error(nodeTreeUnavailableReason() ?: "读控件失败：runner 没响应")
        }
    }

    /**
     * 点影子屏上文字或描述包含 [target] 的控件（先完全相等、再包含；同等条件下优先可点的）。
     * 按控件实际位置点，不靠模型对着缩小的截图估坐标。返回被点的控件。
     */
    suspend fun tapByText(target: String): VdNode {
        val needle = target.trim()
        require(needle.isNotEmpty()) { "target 不能为空" }
        val all = nodes()
        fun label(n: VdNode) = n.text.ifBlank { n.desc }
        val hit = all.filter { label(it).equals(needle, ignoreCase = true) }.sortedByDescending { it.clickable }.firstOrNull()
            ?: all.filter { label(it).contains(needle, ignoreCase = true) }.sortedByDescending { it.clickable }.firstOrNull()
            ?: error("影子屏上找不到文字为「$needle」的控件，先用 nodes 看看屏上有什么")
        tap(hit.centerX, hit.centerY)
        return hit
    }

    /**
     * 动作前拿影子屏信息：命令通道就绪时直接用缓存。每点一下都 refresh 会先经 Shizuku 跑两次
     * dumpsys，那才是「点不动」的大头；通道一旦失败，调用方退回旧路径时会重新 refresh。
     */
    private suspend fun currentInfo(): VdInfo =
        _state.value?.takeIf { ctlAvailable == true } ?: refresh() ?: error("影子屏未运行")

    /** 在影子屏上点按。 */
    suspend fun tap(x: Int, y: Int) {
        currentInfo()
        if (runnerCommand("tap", "$x", "$y") is CtlResult.Ok) return
        val info = refresh() ?: error("影子屏未运行")
        exec("input -d ${info.displayId} tap $x $y", 20_000L)
    }

    /** 在影子屏上滑动。 */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        currentInfo()
        if (runnerCommand("swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs") is CtlResult.Ok) return
        val info = refresh() ?: error("影子屏未运行")
        exec("input -d ${info.displayId} swipe $x1 $y1 $x2 $y2 $durationMs", 20_000L)
    }

    /** 在影子屏上发送按键（如 KEYCODE_BACK / KEYCODE_HOME / 4 / 3）。 */
    suspend fun keyEvent(code: String) {
        val c = code.trim()
        require(keyCodeRe.matches(c)) { "keycode 不合法：$c" }
        currentInfo()
        val numeric = c.toIntOrNull() ?: android.view.KeyEvent.keyCodeFromString(c).takeIf { it > 0 }
        if (numeric != null && runnerCommand("key", "$numeric") is CtlResult.Ok) return
        val info = refresh() ?: error("影子屏未运行")
        exec("input -d ${info.displayId} keyevent $c", 20_000L)
    }

    /**
     * 在影子屏当前获得焦点的输入框里输入文字，[submit] 为真时补一个回车。
     *
     * 优先让 runner 把字直接写进输入框（接在原有内容后面）：不经输入法、不经剪贴板，
     * 用户在主屏打字不会被打断，剪贴板里的东西也不会被盖掉。
     * runner 写不进去时才退回旧路径：ASCII 走 `input text`（空格写成 `%s`），
     * 中文等非 ASCII 走宿主剪贴板 + `KEYCODE_PASTE`（`input text` 传不进多字节字符）。
     */
    suspend fun type(text: String, submit: Boolean) {
        val info = currentInfo()
        if (ctlAvailable == null) probeChannel()
        val direct = text.isNotEmpty() && uiaAvailable == true &&
            runnerCommand("settext", "append", text) is CtlResult.Ok
        if (text.isNotEmpty() && !direct) {
            if (text.any { it.code > 0x7F }) {
                setHostClipboard(text)
                // 等系统把新剪贴板同步到目标进程，再发粘贴键，否则可能粘到旧内容。
                delay(150L)
                exec("input -d ${info.displayId} keyevent $KEYCODE_PASTE", 20_000L)
            } else {
                // text 来自模型/用户，进 shell 前整体单引号转义；空格按 input 约定换成 %s。
                val encoded = text.replace(" ", "%s")
                exec("input -d ${info.displayId} text ${shellQuote(encoded)}", 20_000L)
            }
        }
        if (submit) {
            if (runnerCommand("key", "66") !is CtlResult.Ok) {
                exec("input -d ${info.displayId} keyevent 66", 20_000L)
            }
        }
    }

    /** runner dump 的每行：`序号|文字|描述|resource-id|类名|标志|left,top,right,bottom`。 */
    private fun parseNodes(body: String): List<VdNode> = body.lineSequence().mapNotNull { line ->
        val p = line.split('|')
        if (p.size < 7) return@mapNotNull null
        val b = p[6].split(',').mapNotNull { it.trim().toIntOrNull() }
        if (b.size != 4) return@mapNotNull null
        VdNode(
            index = p[0].toIntOrNull() ?: return@mapNotNull null,
            text = p[1], desc = p[2], resourceId = p[3], className = p[4], flags = p[5],
            left = b[0], top = b[1], right = b[2], bottom = b[3],
        )
    }.toList()

    /** 把文本写进宿主剪贴板——App 与目标 App 同机，影子屏上的粘贴键即可取到。 */
    private fun setHostClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: error("拿不到宿主剪贴板服务")
        cm.setPrimaryClip(ClipData.newPlainText("aharou-vd", text))
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
