package com.aicode.feature.agent.domain.tool.a11y

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import com.aicode.accessibility.AharouAccessibilityService
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * 无障碍工具（a11y）：让 Agent「看得懂、点得动」宿主屏幕。
 *
 * 依赖用户在系统设置里开启的 [AharouAccessibilityService]（设置 → 权限与后台 → 无障碍）；
 * 未开启时返回明确指引。能力：节点树 dump / 按文字查找 / 点按（id 或坐标）/ 滑动 /
 * 系统键 / 系统截图 / 输入框写文字 / 窗口列表 / 前台应用。
 */
class A11yTool @Inject constructor() : AgentTool() {
    private companion object {
        const val TAG = "A11yTool"
        const val MAX_NODES = 400
        const val MAX_DEPTH = 25
        const val MAX_IMAGE_EDGE = 1440
        const val JPEG_QUALITY = 75
    }

    override val name = "a11y"

    override val description =
        "无障碍操作宿主屏幕（需先在 设置 → 权限与后台 → 无障碍 开启服务）：" +
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
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "?"
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认无障碍操作",
            summary = "action = $action",
            details = "将通过无障碍服务读取或操作屏幕（需已在系统中开启该服务）。",
            argsPreview = argsPreview,
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
        return try {
            when (action) {
                "dump" -> ToolResult.Success(kotlinx.serialization.json.JsonPrimitive(dumpTree(service)))

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
                        if (clickNode(service, node)) {
                            ToolResult.Success(buildJsonObject { put("message", "已点击节点 $id") })
                        } else {
                            ToolResult.Error("节点点击失败（不可点击且坐标兜底失败）", "TAP_FAILED")
                        }
                    } else {
                        val x = args["x"]?.jsonPrimitive?.intOrNull
                        val y = args["y"]?.jsonPrimitive?.intOrNull
                        if (x == null || y == null) return ToolResult.Error("tap 需要 id 或 x/y", "MISSING_ARGS")
                        if (tapAt(service, x, y)) {
                            ToolResult.Success(buildJsonObject { put("message", "已点击 ($x, $y)") })
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
                    if (swipeAt(service, x, y, x2, y2, duration)) {
                        ToolResult.Success(buildJsonObject { put("message", "已滑动 ($x,$y) → ($x2,$y2)") })
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
                    val base64 = bitmapToJpegBase64(bitmap)
                    bitmap.recycle()
                    ToolResult.Success(
                        buildJsonObject { put("message", "已截图（图片随结果返回）") },
                        if (base64.isNotEmpty()) listOf(AgentImage(mimeType = "image/jpeg", base64Data = base64)) else emptyList(),
                    )
                }

                "settext" -> {
                    val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                    if (id.isEmpty() || text == null) return ToolResult.Error("setText 需要 id 和 text", "MISSING_ARGS")
                    val node = service.nodeRegistry.get(id)
                        ?: return ToolResult.Error("节点 id「$id」已过期——请重新 dump/find。", "STALE_NODE")
                    if (service.setNodeText(node, text)) {
                        ToolResult.Success(buildJsonObject { put("message", "已写入文本") })
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

    private fun dumpTree(service: AharouAccessibilityService): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_DEPTH || count >= MAX_NODES) return
            val id = service.nodeRegistry.put(node)
            sb.append("  ".repeat(depth)).append(describeNode(node, id)).append('\n')
            count++
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        service.rootNodes().forEach { walk(it, 0) }
        return if (count == 0) "（界面为空——无障碍服务可能未就绪）" else sb.toString()
    }

    private fun findNodes(service: AharouAccessibilityService, query: String): String {
        val out = StringBuilder()
        var found = 0
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || found >= 50) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.contains(query, ignoreCase = true) || desc.contains(query, ignoreCase = true)) {
                val id = service.nodeRegistry.put(node)
                out.append(describeNode(node, id)).append('\n')
                found++
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        service.rootNodes().forEach { walk(it) }
        return if (found == 0) "没有找到包含「$query」的节点" else out.toString()
    }

    private fun describeNode(node: AccessibilityNodeInfo, id: String): String {
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
        }
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
