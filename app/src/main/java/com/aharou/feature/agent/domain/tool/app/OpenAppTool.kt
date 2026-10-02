package com.aharou.feature.agent.domain.tool.app

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import java.net.URLEncoder
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 打开宿主上的应用，或让地图直接起一条导航。
 *
 * 为什么不复用 [com.aharou.feature.agent.domain.tool.shizuku.ShizukuTool]：那条路每次调用都要弹确认框，
 * 而语音唤醒场景下人往往不在屏幕前，弹窗等于把流程卡死。本工具把能力面收窄到
 * 「打开某个已安装应用」「用地图起导航」两件可枚举的事，不接受任意命令，
 * 因此可以免确认执行。
 *
 * 导航不依赖地图 SDK、也不需要开发者 key：只是用 URI scheme 把目的地文字
 * 交给已经装好的地图应用，由它自己去解析地址。
 */
class OpenAppTool @Inject constructor(
    private val hostShell: HostShellManager
) : AgentTool() {

    private companion object {
        const val TAG = "OpenAppTool"
        const val SHELL_TIMEOUT_MS = 20_000L

        const val GAODE_PKG = "com.autonavi.minimap"
        const val BAIDU_PKG = "com.baidu.BaiduMap"

        /** 包名白名单式校验：解析结果只会是它，杜绝把用户输入拼进命令。 */
        val PACKAGE_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        val NAV_MODES = setOf("driving", "walking", "transit")
    }

    override val name = "open_app"

    override val description =
        "打开手机上的应用，或直接用地图起导航（免确认，不需要地图 SDK 与 key）。" +
            "action=open 打开应用：target 给包名（最准，如抖音 com.ss.android.ugc.aweme）或应用名关键词；" +
            "action=navigate 起导航：target 直接给目的地文字（如「天安门」，不用经纬度），mode 可选 driving/walking/transit。" +
            "装了高德就走高德、装了百度就走百度，都没有则退回系统地图让用户选。" +
            "需要已授予 root 或已安装并授权 Shizuku；没有时返回开启指引。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "open=打开应用；navigate=地图起导航", true,
            enum = listOf("open", "navigate"),
        ),
        "target" to ToolParameter(
            "target", ParameterType.STRING,
            "open 时给包名或应用名关键词；navigate 时给目的地文字", true,
        ),
        "mode" to ToolParameter(
            "mode", ParameterType.STRING, "navigate 专用：driving / walking / transit，默认 driving", false,
            enum = listOf("driving", "walking", "transit"),
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少 action（open / navigate）", "MISSING_ACTION")
        val target = args["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (target.isEmpty()) return ToolResult.Error("缺少 target", "MISSING_TARGET")

        if (hostShell.mode.value == HostShellMode.UNAVAILABLE) {
            return ToolResult.Error(
                "宿主命令通道不可用：${hostShell.shizukuState.value.hint()}。" +
                    "请先安装并启动 Shizuku、在 Shizuku 里授权本应用，或给本应用 root 权限，然后重试。",
                "HOST_SHELL_UNAVAILABLE",
            )
        }

        return try {
            when (action) {
                "open" -> openApp(target)
                "navigate" -> navigate(target, args["mode"]?.jsonPrimitive?.contentOrNull)
                else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "open_app 失败：$action $target", e)
            ToolResult.Error("操作失败：${e.message}")
        }
    }

    private suspend fun openApp(target: String): ToolResult {
        val pkg = resolvePackage(target)
            ?: return ToolResult.Error(
                "没找到应用「$target」。直接把包名给我最准（例如抖音是 com.ss.android.ugc.aweme），" +
                    "也可以先用 Shizuku 工具跑 `pm list packages` 查一下包名。",
                "APP_NOT_FOUND",
            )
        // monkey 比起 am start 的好处是不必知道入口 Activity，包名对了就能拉起来
        val result = hostShell.run(
            "monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1",
            SHELL_TIMEOUT_MS,
        )
        return if (result.exitCode == 0 && !result.output.contains("No activities found")) {
            ToolResult.Success(buildJsonObject { put("message", "已打开 $pkg") })
        } else {
            ToolResult.Error("打开 $pkg 失败：${result.output.take(300)}", "OPEN_FAILED")
        }
    }

    private suspend fun navigate(target: String, modeRaw: String?): ToolResult {
        val mode = modeRaw?.trim()?.lowercase()?.takeIf { it in NAV_MODES } ?: "driving"
        val place = URLEncoder.encode(target, "UTF-8")
        val installed = installedPackages()

        val (url, via) = when {
            GAODE_PKG in installed -> {
                // 高德：t=0 驾车 / 1 公交 / 2 步行 / 3 骑行
                val t = when (mode) {
                    "walking" -> "2"
                    "transit" -> "1"
                    else -> "0"
                }
                "androidamap://route/planner?sourceApplication=com.aharou.agent&dname=$place&dev=0&t=$t" to "高德地图"
            }

            BAIDU_PKG in installed ->
                "baidumap://map/direction?destination=$place&mode=$mode&src=android.aharou.agent" to "百度地图"

            else ->
                "geo:0,0?q=$place" to "系统地图"
        }

        val result = hostShell.run(
            "am start -a android.intent.action.VIEW -d \"$url\" 2>&1",
            SHELL_TIMEOUT_MS,
        )
        if (result.exitCode != 0 || result.output.contains("Error", ignoreCase = true)) {
            return ToolResult.Error("唤起${via}失败：${result.output.take(300)}", "NAVIGATE_FAILED")
        }

        val note = if (via == "系统地图") {
            "手机上没装高德或百度地图，已交给系统地图；要让我的话直接起导航，先装其中一个。"
        } else {
            "目的地文字是交给${via}自己解析的，它认不出来时会列出候选让你挑。"
        }
        return ToolResult.Success(
            buildJsonObject {
                put("message", "已用${via}导航到「$target」（$mode）")
                put("note", note)
            }
        )
    }

    /** 解析成包名：target 本身像包名就直接用，否则在已装包里按关键词模糊找。 */
    private suspend fun resolvePackage(target: String): String? {
        if (PACKAGE_RE.matches(target)) return target
        val needle = target.lowercase()
        return installedPackages()
            .firstOrNull { it.lowercase().contains(needle) }
    }

    private suspend fun installedPackages(): Set<String> {
        val result = hostShell.run("pm list packages", SHELL_TIMEOUT_MS)
        if (result.exitCode != 0) return emptySet()
        return result.output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { PACKAGE_RE.matches(it) }
            .toSet()
    }

    private fun ShizukuState.hint(): String = when (this) {
        ShizukuState.NOT_INSTALLED -> "未安装 Shizuku"
        ShizukuState.NOT_RUNNING -> "Shizuku 服务未运行"
        ShizukuState.PERMISSION_DENIED -> "本应用尚未获得 Shizuku 授权"
        ShizukuState.READY -> ""
    }
}
