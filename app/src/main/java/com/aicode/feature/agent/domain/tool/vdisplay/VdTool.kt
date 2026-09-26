package com.aicode.feature.agent.domain.tool.vdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.vdisplay.VdController
import com.aicode.feature.agent.domain.vdisplay.VdInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * 影子屏工具（vscreen）：在宿主上操作一块**不在设备屏幕显示**的虚拟显示屏。
 *
 * 典型用法：`start` 建屏 → `launch` 把某个 App 开进去 → `shot` 看画面 →
 * `tap`/`swipe`/`key` 操作 → `stop` 收掉。全程不占用用户屏幕（需 Shizuku 就绪）。
 */
class VdTool @Inject constructor(
    private val vdController: VdController,
) : AgentTool() {
    private companion object {
        const val TAG = "VdTool"
        const val MAX_IMAGE_EDGE = 1440
        const val JPEG_QUALITY = 75
    }

    override val name = "vscreen"

    override val description =
        "影子屏：宿主上的无头虚拟显示屏（不在设备屏幕显示）。可在其中启动 App、截图、点按、滑动、按键，全程不影响用户主屏。" +
            "action=start 创建（可选 width/height/dpi）；status 查询；stop 停止；launch 启动应用（component=\"包名/Activity\" 或仅包名）；" +
            "shot 截图（图片随结果返回）；tap / swipe / key 触控。需 Shizuku 就绪。"

    override val permissionPolicy = ToolPermissionPolicy.ASK

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "操作：start / status / stop / launch / shot / tap / swipe / key", true,
            enum = listOf("start", "status", "stop", "launch", "shot", "tap", "swipe", "key"),
        ),
        "width" to ToolParameter("width", ParameterType.INTEGER, "start 专用：宽（默认 1080）", false),
        "height" to ToolParameter("height", ParameterType.INTEGER, "start 专用：高（默认 1920）", false),
        "dpi" to ToolParameter("dpi", ParameterType.INTEGER, "start 专用：dpi（默认 440）", false),
        "component" to ToolParameter("component", ParameterType.STRING, "launch 专用：组件（\"pkg/act\" 或 \"pkg\"）", false),
        "x" to ToolParameter("x", ParameterType.INTEGER, "tap/swipe 起点 X（像素）", false),
        "y" to ToolParameter("y", ParameterType.INTEGER, "tap/swipe 起点 Y（像素）", false),
        "x2" to ToolParameter("x2", ParameterType.INTEGER, "swipe 终点 X", false),
        "y2" to ToolParameter("y2", ParameterType.INTEGER, "swipe 终点 Y", false),
        "duration" to ToolParameter("duration", ParameterType.INTEGER, "swipe 持续时间（毫秒，默认 300）", false),
        "keycode" to ToolParameter("keycode", ParameterType.STRING, "key 专用：按键（KEYCODE_BACK / 4 等）", false),
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
            title = "确认使用影子屏",
            summary = "action = $action",
            details = "将经 Shizuku 在宿主上创建/操作无头虚拟屏（不会占用你的屏幕）。",
            argsPreview = argsPreview,
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少 action（start / status / stop / launch / shot / tap / swipe / key）", "MISSING_ACTION")
        return try {
            when (action) {
                "start" -> {
                    val w = args["width"]?.jsonPrimitive?.intOrNull ?: VdController.DEFAULT_WIDTH
                    val h = args["height"]?.jsonPrimitive?.intOrNull ?: VdController.DEFAULT_HEIGHT
                    val dpi = args["dpi"]?.jsonPrimitive?.intOrNull ?: VdController.DEFAULT_DPI
                    val info = vdController.start(w, h, dpi)
                    ToolResult.Success(infoJson(info, "影子屏已就绪"))
                }

                "status" -> {
                    val info = vdController.refresh()
                    if (info == null) {
                        ToolResult.Success(buildJsonObject { put("running", false) })
                    } else {
                        ToolResult.Success(infoJson(info, "运行中"))
                    }
                }

                "stop" -> {
                    vdController.stop()
                    ToolResult.Success(
                        buildJsonObject {
                            put("running", false)
                            put("message", "影子屏已停止")
                        }
                    )
                }

                "launch" -> {
                    val component = args["component"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (component.isEmpty()) {
                        return ToolResult.Error("launch 需要 component（\"pkg/act\" 或 \"pkg\"）", "MISSING_COMPONENT")
                    }
                    val info = vdController.launch(component)
                    ToolResult.Success(infoJson(info, "已在影子屏启动 $component"))
                }

                "shot" -> {
                    val (info, file) = vdController.screenshot()
                    val base64 = fileToJpegBase64(file)
                    ToolResult.Success(
                        infoJson(info, "已截图（图片随结果返回）"),
                        if (base64.isNotEmpty()) listOf(AgentImage(mimeType = "image/jpeg", base64Data = base64)) else emptyList(),
                    )
                }

                "tap" -> {
                    val x = args["x"]?.jsonPrimitive?.intOrNull
                    val y = args["y"]?.jsonPrimitive?.intOrNull
                    if (x == null || y == null) return ToolResult.Error("tap 需要 x / y", "MISSING_ARGS")
                    vdController.tap(x, y)
                    ToolResult.Success(buildJsonObject { put("message", "已点击 ($x, $y)") })
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
                    vdController.swipe(x, y, x2, y2, duration)
                    ToolResult.Success(buildJsonObject { put("message", "已滑动 ($x,$y) → ($x2,$y2)") })
                }

                "key" -> {
                    val code = args["keycode"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (code.isEmpty()) return ToolResult.Error("key 需要 keycode（如 KEYCODE_BACK / 4）", "MISSING_KEYCODE")
                    vdController.keyEvent(code)
                    ToolResult.Success(buildJsonObject { put("message", "已发送按键 $code") })
                }

                else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "vscreen 执行失败：$action", e)
            ToolResult.Error("影子屏操作失败：${e.message}")
        }
    }

    private fun infoJson(info: VdInfo, message: String): JsonElement = buildJsonObject {
        put("message", message)
        put("running", true)
        put("displayId", info.displayId)
        if (info.sfDisplayId.isNotEmpty()) put("sfDisplayId", info.sfDisplayId)
        put("width", info.width)
        put("height", info.height)
        put("dpi", info.dpi)
    }

    /** 截图转 JPEG base64：长边压到 ≤1440，控制给模型的图片体积。 */
    private fun fileToJpegBase64(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
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
