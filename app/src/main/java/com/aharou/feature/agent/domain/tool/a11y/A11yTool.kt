package com.aharou.feature.agent.domain.tool.a11y

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.aharou.accessibility.AharouAccessibilityService
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.vdisplay.ScreenActionSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * 无障碍工具（a11y）：让 Agent「看得懂、点得动」宿主屏幕。
 *
 * 依赖用户在系统设置里开启的 [AharouAccessibilityService]（设置 → 权限与后台 → 无障碍）；
 * 未开启时返回明确指引。能力：节点树 dump / 按文字查找 / 点按（id 或坐标）/ 滑动 /
 * 系统键 / 系统截图 / 输入框写文字 / 窗口列表 / 前台应用。
 */
class A11yTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val hostShell: HostShellManager,
) : AgentTool() {
    private companion object {
        const val TAG = "A11yTool"
        const val MAX_NODES = 400
        const val MAX_DEPTH = 25
        const val MAX_IMAGE_EDGE = 1440
        const val JPEG_QUALITY = 75

        const val NORMALIZED_SPAN = 1000

        /** 结构快照的预算：只用来前后对比，不需要看得全，压小一点省时间。 */
        const val SNAPSHOT_MAX_NODES = 150
        const val SNAPSHOT_BUDGET_MS = 2_000L
        const val ACTION_SETTLE_MS = 500L

        /** 回执文案：三态必须分开说，否则模型会把「看不到变化」当成「没点中」再点一遍。 */
        const val NOTE_CHANGED = "动作已发出，界面元素树有变化，可以按新画面继续。"
        const val NOTE_UNCHANGED =
            "动作已发出，但界面元素树没有变化：刷新、开关、提交这类原地动作本来就画面不变，这不是没点中的证据，别重瞄。"
        const val NOTE_UNKNOWN = "动作已发出，但这次没拿到前后画面，结果是未知，不是没点中。"

        /** 自绘界面的类名特征：读得到类名却一个控件都读不到时，用它区分「自绘」和「连接僵了」。 */
        val SURFACE_CLASS_HINTS =
            listOf("webview", "surfaceview", "textureview", "flutter", "unity", "cocos", "canvas")

        /** 会改变他人应用状态的写动作：有影子屏可用时必须走影子屏。 */
        val WRITE_ACTIONS = setOf("tap", "swipe", "key", "settext")

        /** 有「手指下是什么」这回事、需要 label 的动作：语义风控只看这几个。 */
        val LABEL_ACTIONS = setOf("tap", "settext")
    }

    /**
     * 曾经从哪些包读到过可用节点。用来看「同一个应用这次读到 0 个」——那多半是无障碍连接
     * 失效，而不是界面本来就读不到。不写死包名：谁读到过谁才进这个集合。
     */
    private val packagesWithReadableTree = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override val name = "a11y"

    override val description =
        "无障碍操作宿主屏幕（需先在 设置 → 权限与后台 → 无障碍 开启服务）。" +
            "只读动作 dump/find/shot/windows/foreground 可随时用（看用户屏幕上有什么，不抢焦点）；" +
            "写动作 tap/swipe/key/setText 仅用于 Aharou 自己的界面——前台是第三方 App 时会被拒统，改用 vscreen 影子屏。" +
            "dump=读当前界面元素树（带节点 id）；find=按文字找节点；tap=按 id 或坐标点按；" +
            "swipe=滑动；key=返回/主页/最近任务；shot=系统截图（图片随结果返回）；" +
            "setText=给输入框写文字；windows=窗口列表；foreground=前台应用。"

    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "操作：dump / find / tap / swipe / key / shot / setText / windows / foreground", true,
            enum = listOf("dump", "find", "tap", "swipe", "key", "shot", "setText", "windows", "foreground"),
        ),
        "id" to ToolParameter("id", ParameterType.STRING, "节点 id（tap / setText 用；来自 dump / find 结果）", false),
        "text" to ToolParameter("text", ParameterType.STRING, "find=要搜的文字；setText=要写入的文本", false),
        "x" to ToolParameter("x", ParameterType.INTEGER, "tap / swipe 起点 X", false),
        "y" to ToolParameter("y", ParameterType.INTEGER, "tap / swipe 起点 Y", false),
        "x2" to ToolParameter("x2", ParameterType.INTEGER, "swipe 终点 X", false),
        "y2" to ToolParameter("y2", ParameterType.INTEGER, "swipe 终点 Y", false),
        "duration" to ToolParameter("duration", ParameterType.INTEGER, "swipe 持续时间（毫秒，默认 300）", false),
        "key" to ToolParameter(
            "key", ParameterType.STRING, "key 专用：back / home / recents / notifications", false,
            enum = listOf("back", "home", "recents", "notifications"),
        ),
        "label" to ToolParameter(
            "label", ParameterType.STRING,
            "tap / setText 专用：手指下或目标元素的文字（按钮、链接、输入框的标签），如「确认支付」。" +
                "命中敏感词会把本次调用提级为敏感操作、逐次确认；tap/setText 不传会收到一条告警。",
            false
        ),
    )

    /**
     * 命中敏感词的 tap/setText 借用 NON_REMEMBERABLE_CAPABILITIES 里的能力位，让策略引擎把本次调用
     * 判成「必问、不可记忆」——[com.aharou.feature.agent.domain.tool.AgentTool.effectiveCapabilities]
     * 是工具侧唯一能影响引擎裁决的既有机制。
     */
    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        if (assessArgs(args)?.isSensitive == true) capabilities + ToolCapability.MODIFY_AGENT_CONFIG
        else capabilities

    /** 仅对带 label 的动作做语义评估；其余动作没有「手指下是什么」这回事，返回 null。 */
    private fun assessArgs(args: Map<String, JsonElement>): ScreenActionSemantics.Assessment? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return null
        if (action !in LABEL_ACTIONS) return null
        return ScreenActionSemantics.assess(args["label"]?.jsonPrimitive?.contentOrNull)
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "?"
        val label = args["label"]?.jsonPrimitive?.contentOrNull
        val assessment = assessArgs(args)
        val sensitive = assessment?.isSensitive == true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = if (sensitive) "敏感操作确认（不可记忆）" else "确认无障碍操作",
            summary = if (label.isNullOrBlank()) "action = $action" else "action = $action\nlabel = $label",
            details = buildString {
                append("将通过无障碍服务读取或操作屏幕（需已在系统中开启该服务）。")
                assessment?.warning?.let { append("\n\n").append(it) }
            },
            argsPreview = argsPreview,
            // 意图声明：真正的「不可记忆」由 effectiveCapabilities 触发的引擎裁决兜住，
            // 引擎会用自身的 rememberDisabledReason 覆盖这一条（见 PermissionGate）。
            rememberDisabledReason = assessment?.warning?.takeIf { sensitive },
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少 action（dump / find / tap / swipe / key / shot / setText / windows / foreground）", "MISSING_ACTION")
        val service = AharouAccessibilityService.getInstance()
            ?: return ToolResult.Error(
                "无障碍服务未开启：请到「设置 → 权限与后台 → 无障碍」开启 Aharou 无障碍服务后重试。",
                "A11Y_NOT_ENABLED",
            )

        // 写动作不碰用户主屏上的第三方 App：有影子屏可用就让它去影子屏。
        // 但宿主通道不可用时影子屏根本起不来，这时无障碍是唯一可行路径，放行。
        if (action in WRITE_ACTIONS && hostShell.ensureMode() != HostShellMode.UNAVAILABLE) {
            val (foregroundPkg, _) = service.foregroundPackage()
            if (foregroundPkg != null && !foregroundPkg.startsWith(context.packageName)) {
                return ToolResult.Error(
                    "前台是第三方 App（$foregroundPkg）：操作其他应用请改用 vscreen 影子屏，避免抢占用户主屏。",
                    "USE_VSCREEN",
                )
            }
        }

        return try {
            when (action) {
                "dump" -> {
                    val dump = dumpTree(service)
                    ToolResult.Success(
                        buildJsonObject {
                            put("tree", dump.text)
                            put("node_count", dump.count)
                            // 只有空树才给诊断：非空时模型直接读 tree 就行，多一个字段只会分散注意。
                            dump.diagnosis?.let { put("diagnosis", it) }
                        }
                    )
                }

                "find" -> {
                    val query = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (query.isEmpty()) return ToolResult.Error("find 需要 text 参数", "MISSING_TEXT")
                    ToolResult.Success(kotlinx.serialization.json.JsonPrimitive(findNodes(service, query)))
                }

                "tap" -> {
                    val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (id.isNotEmpty()) {
                        val node = service.nodeRegistry.get(id)
                            ?: return ToolResult.Error("节点 id「$id」已过期——请重新 dump/find 再点。", "STALE_NODE")
                        val before = structureSnapshot(service)
                        if (clickNode(service, node)) {
                            actionSuccess(service, "已点击节点 $id", before)
                        } else {
                            ToolResult.Error("节点点击失败（不可点击且坐标兜底失败）", "TAP_FAILED")
                        }
                    } else {
                        val x = args["x"]?.jsonPrimitive?.intOrNull
                        val y = args["y"]?.jsonPrimitive?.intOrNull
                        if (x == null || y == null) return ToolResult.Error("tap 需要 id 或 x/y", "MISSING_ARGS")
                        val before = structureSnapshot(service)
                        if (tapAt(service, x, y)) {
                            actionSuccess(service, "已点击 ($x, $y)", before)
                        } else {
                            ToolResult.Error("点击手势失败", "TAP_FAILED")
                        }
                    }
                }

                "swipe" -> {
                    val x = args["x"]?.jsonPrimitive?.intOrNull
                    val y = args["y"]?.jsonPrimitive?.intOrNull
                    val x2 = args["x2"]?.jsonPrimitive?.intOrNull
                    val y2 = args["y2"]?.jsonPrimitive?.intOrNull
                    if (x == null || y == null || x2 == null || y2 == null) {
                        return ToolResult.Error("swipe 需要 x / y / x2 / y2", "MISSING_ARGS")
                    }
                    val duration = args["duration"]?.jsonPrimitive?.intOrNull ?: 300
                    val before = structureSnapshot(service)
                    if (swipeAt(service, x, y, x2, y2, duration)) {
                        actionSuccess(service, "已滑动 ($x,$y) → ($x2,$y2)", before)
                    } else {
                        ToolResult.Error("滑动手势失败", "SWIPE_FAILED")
                    }
                }

                "key" -> {
                    val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
                    val globalAction = when (key) {
                        "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                        "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                        "notifications" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                        else -> return ToolResult.Error("key 仅支持 back / home / recents / notifications", "INVALID_KEY")
                    }
                    val ok = service.performGlobalAction(globalAction)
                    if (ok) ToolResult.Success(buildJsonObject { put("message", "已发送 $key") })
                    else ToolResult.Error("系统键发送失败", "KEY_FAILED")
                }

                "shot" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        return ToolResult.Error("系统截图需要 Android 11+", "UNSUPPORTED")
                    }
                    val shot = service.captureScreenshot()
                    val bitmap = shot.bitmap
                        ?: return ToolResult.Error("截图失败：${shot.errorCode} ${shot.errorMessage}", "SHOT_FAILED")
                    val caption = buildShotCaption(service, bitmap)
                    val base64 = bitmapToJpegBase64(bitmap)
                    bitmap.recycle()
                    ToolResult.Success(
                        buildJsonObject {
                            put("message", "已截图（图片随结果返回）")
                            if (caption.isNotBlank()) put("caption", caption)
                        },
                        if (base64.isNotEmpty()) listOf(AgentImage(mimeType = "image/jpeg", base64Data = base64)) else emptyList(),
                    )
                }

                "settext" -> {
                    val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                    if (id.isEmpty() || text == null) return ToolResult.Error("setText 需要 id 和 text", "MISSING_ARGS")
                    val node = service.nodeRegistry.get(id)
                        ?: return ToolResult.Error("节点 id「$id」已过期——请重新 dump/find。", "STALE_NODE")
                    val before = structureSnapshot(service)
                    if (service.setNodeText(node, text)) {
                        actionSuccess(service, "已写入文本", before)
                    } else {
                        ToolResult.Error("写入失败（该节点可能不支持设置文本）", "SET_TEXT_FAILED")
                    }
                }

                "windows" -> ToolResult.Success(
                    kotlinx.serialization.json.JsonPrimitive(
                        service.windowInfos().joinToString("\n") { it.entries.joinToString(", ") { e -> "${e.key}=${e.value}" } }
                    )
                )

                "foreground" -> {
                    val (pkg, cls) = service.foregroundPackage()
                    ToolResult.Success(buildJsonObject {
                        put("package", pkg ?: "")
                        put("class", cls ?: "")
                    })
                }

                else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "a11y 执行失败：$action", e)
            ToolResult.Error("无障碍操作失败：${e.message}")
        }
    }

    /** dump 的结果：空树时 [diagnosis] 非空，[text] 就是给模型看的那句诊断提示。 */
    private class DumpResult(val text: String, val count: Int, val diagnosis: String? = null)

    private fun dumpTree(service: AharouAccessibilityService): DumpResult {
        val sb = StringBuilder()
        val screen = service.screenSize()
        var count = 0
        val packages = HashSet<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || count >= MAX_NODES) return
            val id = service.nodeRegistry.put(node)
            sb.append("  ".repeat(depth)).append(describeNode(node, id, screen)).append('\n')
            node.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { packages.add(it) }
            count++
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        service.rootNodes().forEach { walk(it, 0) }
        if (count == 0) {
            val (diagnosis, hint) = diagnoseEmptyTree(service)
            return DumpResult(hint, 0, diagnosis)
        }
        packagesWithReadableTree.addAll(packages)
        return DumpResult(sb.toString(), count)
    }

    private fun findNodes(service: AharouAccessibilityService, query: String): String {
        val out = StringBuilder()
        val screen = service.screenSize()
        var found = 0
        val packages = HashSet<String>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || found >= 50) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
                val id = service.nodeRegistry.put(node)
                out.append(describeNode(node, id, screen)).append('\n')
                node.packageName?.toString()?.takeIf { it.isNotBlank() }?.let { packages.add(it) }
                found++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        service.rootNodes().forEach { walk(it) }
        if (found == 0) return "没有找到包含「$query」的节点"
        packagesWithReadableTree.addAll(packages)
        return out.toString()
    }

    private fun describeNode(node: AccessibilityNodeInfo, id: String, screen: Pair<Int, Int>?): String {
        val className = node.className?.toString()?.substringAfterLast('.') ?: "?"
        val rect = Rect().also { node.getBoundsInScreen(it) }
        val flags = buildString {
            if (node.isClickable) append(" clickable")
            if (node.isScrollable) append(" scrollable")
            if (node.isEditable) append(" editable")
            if (!node.isEnabled) append(" disabled")
        }
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        return buildString {
            append("[$id] ").append(className)
            text?.let { append(" text=\"").append(it.take(60)).append('"') }
            desc?.let { append(" desc=\"").append(it.take(60)).append('"') }
            append(" bounds=[").append(rect.left).append(',').append(rect.top)
                .append("][").append(rect.right).append(',').append(rect.bottom).append(']')
            append(flags)
            // 像素 bounds 保留给能自己算的场合，归一化中心点直接给算好的：
            // 实测模型从 bounds 自己算中心再按屏幕宽高换算会算错，不如把结果写进同一行。
            if (screen != null) {
                val cx = (rect.left + rect.right) / 2
                val cy = (rect.top + rect.bottom) / 2
                val span = NORMALIZED_SPAN.toLong()
                val nx = (cx.toLong() * NORMALIZED_SPAN / screen.first).coerceIn(0L, span)
                val ny = (cy.toLong() * NORMALIZED_SPAN / screen.second).coerceIn(0L, span)
                append(" @ 中心($cx,$cy)px 归一($nx,$ny)/1000")
            }
        }
    }

    /**
     * 空树时区分三种情况：能做的处置完全不同（自绘→改看截图；连接僵了→重开服务；纯空→等一等）。
     * 只看还能拿到的元数据：此时树里一个节点也读不到。
     */
    private fun diagnoseEmptyTree(service: AharouAccessibilityService): Pair<String, String> {
        val foregroundPkg = foregroundPackageHint(service)
        // 先看「以前读到过、这次 0 个」：这是最强的判据，处置也最具体。
        // 放在自绘判断之前，是因为连接僵死时窗口根也可能还在、却一个子节点都没有，
        // 那种形态与自绘界面分不开；一旦这个包以前读到过，就该按连接失效处置。
        if (foregroundPkg != null && packagesWithReadableTree.contains(foregroundPkg)) {
            return "stale" to
                "这个应用（$foregroundPkg）以前能读到控件，现在读到 0 个，多半是无障碍连接失效，" +
                "需要用户去 设置 → 权限与后台 → 无障碍 把本服务关掉再打开，三秒即可。"
        }
        if (looksSelfDrawn(service)) {
            return "surface" to
                "这个界面是自绘的，读不到控件，改看截图；要离开它优先用系统返回/主页换一条路，不要凭坐标盲点。"
        }
        return "empty" to "（界面为空——无障碍服务可能未就绪；也可能是这个界面正在切换）"
    }

    /**
     * 树读不到时 rootInActiveWindow 往往也是 null，退回最近事件里的包名才能把
     * 「这个应用以前读到过、现在读到 0 个」判出来；拿不到就交给「纯空」分支。
     */
    private fun foregroundPackageHint(service: AharouAccessibilityService): String? {
        val (pkg, _) = service.foregroundPackage()
        if (!pkg.isNullOrBlank()) return pkg
        return service.snapshotEvents().asReversed()
            .firstOrNull { !it.packageName.isNullOrBlank() }
            ?.packageName
    }

    /**
     * 自绘界面判据：拿到类名就看类名；类名拿不到时，有 application 窗口且根存在却一个子节点都没有，
     * 也按自绘处理（纯 SurfaceView / Unity 类界面就是这样）。
     */
    private fun looksSelfDrawn(service: AharouAccessibilityService): Boolean {
        val (_, cls) = service.foregroundPackage()
        if (cls != null && isSurfaceClassName(cls)) return true
        for (w in service.windowInfos()) {
            if (w["type"] != "application") continue
            val rootPackage = (w["rootPackage"] as? String).orEmpty()
            val rootChildren = (w["rootChildren"] as? Number)?.toInt() ?: -1
            if (rootPackage.isNotBlank() && rootChildren == 0) return true
        }
        return false
    }

    private fun isSurfaceClassName(className: String): Boolean {
        val lower = className.lowercase()
        return SURFACE_CLASS_HINTS.any { lower.contains(it) }
    }

    /**
     * 动作前后各取一次「结构快照」并比较：模型看不见点按有没有落地，只能靠返回值判断。
     *
     * 不复用 nodeRegistry.put：那会往 id 表里塞一堆后续没人点的节点，把模型手里已有的 id 顶掉。
     * 拿不到可读树、或没能在预算内走完（截断的两帧不可比）时返回 null——此时结果只能是「未知」，
     * 不能当「没变化」。
     */
    private fun structureSnapshot(service: AharouAccessibilityService): Long? {
        val deadline = System.currentTimeMillis() + SNAPSHOT_BUDGET_MS
        var hash = 1L
        var count = 0
        var readable = false
        var aborted = false

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || count >= SNAPSHOT_MAX_NODES) return
            if (System.currentTimeMillis() > deadline) {
                aborted = true
                return
            }
            try {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                val signature = buildString(64) {
                    append(node.packageName?.toString().orEmpty()).append('|')
                    append(node.className?.toString().orEmpty()).append('|')
                    append(node.viewIdResourceName.orEmpty()).append('|')
                    append(node.text?.toString().orEmpty()).append('|')
                    append(rect.left).append(',').append(rect.top).append(',')
                    append(rect.right).append(',').append(rect.bottom)
                }
                hash = hash * 31 + signature.hashCode()
                readable = true
                count++
            } catch (_: Throwable) {
            }
            val children = try {
                node.childCount
            } catch (_: Throwable) {
                0
            }
            for (i in 0 until children) {
                walk(try { node.getChild(i) } catch (_: Throwable) { null }, depth + 1)
            }
        }

        service.rootNodes().forEach { walk(it, 0) }
        return if (readable && !aborted) hash else null
    }

    private data class ChangeReceipt(val screenChanged: JsonElement, val note: String)

    /** 动作后等 500ms 再取后帧：界面动画与列表刷新都需要一点时间，等太短会把「刚变完」当成「没变」。 */
    private suspend fun observeChange(service: AharouAccessibilityService, before: Long?): ChangeReceipt {
        delay(ACTION_SETTLE_MS)
        val after = structureSnapshot(service)
        return when {
            before == null || after == null -> ChangeReceipt(JsonPrimitive("unknown"), NOTE_UNKNOWN)
            before != after -> ChangeReceipt(JsonPrimitive(true), NOTE_CHANGED)
            else -> ChangeReceipt(JsonPrimitive(false), NOTE_UNCHANGED)
        }
    }

    /** 写动作统一回执；tap_delivered 只说明手势被系统接受了，界面是否响应看 screen_changed。 */
    private suspend fun actionSuccess(
        service: AharouAccessibilityService,
        message: String,
        before: Long?,
    ): ToolResult.Success {
        val receipt = observeChange(service, before)
        return ToolResult.Success(
            buildJsonObject {
                put("message", message)
                put("tap_delivered", true)
                put("screen_changed", receipt.screenChanged)
                put("note", receipt.note)
            }
        )
    }

    /** 优先点可点击祖先；否则用坐标兜底。 */
    private fun clickNode(service: AharouAccessibilityService, node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 12) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
            depth++
        }
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() > 0 && rect.height() > 0) {
            return tapAt(service, rect.exactCenterX().toInt(), rect.exactCenterY().toInt())
        }
        return false
    }

    private fun tapAt(service: AharouAccessibilityService, x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x.toFloat() + 1f, y.toFloat() + 1f)
        }
        return service.dispatchSimpleGesture(path, 0, 60)
    }

    private fun swipeAt(
        service: AharouAccessibilityService,
        x: Int, y: Int, x2: Int, y2: Int, durationMs: Int,
    ): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return service.dispatchSimpleGesture(path, 0, durationMs.coerceIn(50, 5_000).toLong())
    }

    /** 截图 caption：前台 App/Activity、屏幕像素尺寸、键盘是否在（拿不到的字段略去）。 */
    private fun buildShotCaption(service: AharouAccessibilityService, bitmap: Bitmap): String {
        val (pkg, _) = service.foregroundPackage()
        val keyboard = service.windowInfos().any { it["type"] == "input_method" }
        return ScreenActionSemantics.caption(
            appName = appLabel(pkg),
            packageName = pkg,
            activity = currentActivity(service),
            width = bitmap.width,
            height = bitmap.height,
            keyboardShown = keyboard,
        )
    }

    /** 最近的 WINDOW_STATE_CHANGED 事件给出真正的 Activity 类；拿不到再退回窗口根类（滤掉系统视图类）。 */
    private fun currentActivity(service: AharouAccessibilityService): String? {
        service.snapshotEvents()
            .lastOrNull { it.type.contains("WINDOW_STATE_CHANGED") && !it.className.isNullOrBlank() }
            ?.className
            ?.let { return it }
        val (_, cls) = service.foregroundPackage()
        return cls?.takeIf { it.isNotBlank() && !it.startsWith("android.") && !it.startsWith("com.android.") }
    }

    /** 包名 → 桌面显示的应用名；查不到返回 null。 */
    private fun appLabel(packageName: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_IMAGE_EDGE) {
            val ratio = MAX_IMAGE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}
