package com.aharou.feature.agent.domain.tool.vdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mobileagent.HandActResult
import com.aharou.feature.agent.domain.mobileagent.HandObservation
import com.aharou.feature.agent.domain.mobileagent.MobileHand
import com.aharou.feature.agent.domain.mobileagent.MobileOperator
import com.aharou.feature.agent.domain.mobileagent.MobileTraceStore
import com.aharou.feature.agent.domain.mobileagent.ScreenFingerprint
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.ocr.TesseractOcrEngine
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.vdisplay.VdController
import com.aharou.feature.agent.domain.vdisplay.VdInfo
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * 影子屏工具（vscreen）：在宿主上操作一块**不在设备屏幕显示**的虚拟显示屏。
 *
 * 典型用法：`start` 建屏 → `launch` 把某个 App 开进去 → `shot` 看画面 →
 * `tap`/`swipe`/`key` 操作 → `stop` 收掉。全程不占用用户屏幕（需 Shizuku 就绪）。
 */
class VdTool @Inject constructor(
    private val vdController: VdController,
    private val ocrEngine: TesseractOcrEngine,
    private val hostShell: HostShellManager,
    private val mobileOperator: MobileOperator,
    private val mobileTraceStore: MobileTraceStore,
) : AgentTool(), MobileHand {
    private companion object {
        const val TAG = "VdTool"
        const val MAX_IMAGE_EDGE = 1440
        const val JPEG_QUALITY = 75

        /** 子代理每看一屏附带的控件上限：再多会把每一步的提示撑大，模型也读不过来。 */
        const val MAX_OBSERVE_NODES = 80

        /** 有「手指下是什么」这回事、需要 label 的动作：语义风控只看这几个。 */
        val LABEL_ACTIONS = setOf("tap", "type")
    }

    override val name = "vscreen"

    override val description =
        "影子屏：宿主上的无头虚拟显示屏（不在设备屏幕显示）。可在其中启动 App、截图、点按、滑动、输入文字、按键，全程不影响用户主屏。" +
            "操作宿主上第三方 App 的标准手段——要动 Aharou 以外的应用就用它，别用 a11y 抢占用户主屏。" +
            "action=start 创建（可选 width/height/dpi）；status 查询；stop 停止；launch 启动应用（component=\"包名/Activity\" 或仅包名）；" +
            "shot 截图（图片随结果返回，图上带坐标网格；传 ocr=true 则改回文字、传 grid=false 关闭网格）；" +
            "nodes 列出屏上控件（文字、类型、中心点像素坐标），比对着截图估坐标准；" +
            "tap / swipe / key 触控，tap 可以不给 x/y、改传 target=控件上的文字，按控件实际位置点；" +
            "type 往焦点输入框输文字（text=内容，可选 submit=true 回车，支持中文；优先直接写进输入框，不弹键盘、不动用户剪贴板）。" +
            "task 把「看屏→决策→动作→再看屏」的多步循环交给手机操作子代理：goal 是要完成的事，一次调用返回一个结果（不逐步吃上下文）；" +
            "子代理内部的动作坐标是 0..1000 归一化值（换分辨率不失效），与本工具 tap/swipe 用的像素坐标不是一套，不要把两者混用；" +
            "trace 列出或回放手机操作记录（traceId 为空则列出）。需 root 或 Shizuku 就绪；未就绪时改用 a11y 并在操作前告知用户。"

    override val permissionPolicy = ToolPermissionPolicy.ASK

    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "操作：start / status / stop / launch / shot / nodes / tap / swipe / key / type / task / trace", true,
            enum = listOf("start", "status", "stop", "launch", "shot", "nodes", "tap", "swipe", "key", "type", "task", "trace"),
        ),
        "width" to ToolParameter("width", ParameterType.INTEGER, "start 专用：宽（默认 1080）", false),
        "height" to ToolParameter("height", ParameterType.INTEGER, "start 专用：高（默认 1920）", false),
        "dpi" to ToolParameter("dpi", ParameterType.INTEGER, "start 专用：dpi（默认 440）", false),
        "component" to ToolParameter("component", ParameterType.STRING, "launch 专用：组件（\"pkg/act\" 或 \"pkg\"）", false),
        "x" to ToolParameter("x", ParameterType.INTEGER, "tap/swipe 起点 X（像素，直接读图上网格数字）", false),
        "y" to ToolParameter("y", ParameterType.INTEGER, "tap/swipe 起点 Y（像素，直接读图上网格数字）", false),
        "target" to ToolParameter(
            "target", ParameterType.STRING,
            "tap 专用：要点的控件上的文字（先完全相等、再包含），给了就不用 x/y。屏上读不到控件时会报错，改用 x/y。",
            false,
        ),
        "x2" to ToolParameter("x2", ParameterType.INTEGER, "swipe 终点 X", false),
        "y2" to ToolParameter("y2", ParameterType.INTEGER, "swipe 终点 Y", false),
        "duration" to ToolParameter("duration", ParameterType.INTEGER, "swipe 持续时间（毫秒，默认 300）", false),
        "keycode" to ToolParameter("keycode", ParameterType.STRING, "key 专用：按键（KEYCODE_BACK / 4 等）", false),
        "ocr" to ToolParameter(
            "ocr", ParameterType.BOOLEAN,
            "shot 专用：true 时用本机 OCR 把屏幕画面转成文字返回（不返回图片）。当前模型不支持图片输入时用，省 token。",
            false
        ),
        "grid" to ToolParameter(
            "grid", ParameterType.BOOLEAN,
            "shot 专用：是否在图上叠加坐标网格与缩放信息（默认 true）。网格线标注的数字就是 tap 用的坐标，可直接照读。",
            false
        ),
        "text" to ToolParameter(
            "text", ParameterType.STRING,
            "type 专用：要输入到焦点输入框的文字，接在原有内容后面。支持中文。",
            false
        ),
        "submit" to ToolParameter(
            "submit", ParameterType.BOOLEAN,
            "type 专用：输完后是否回车（默认 false）。",
            false
        ),
        "label" to ToolParameter(
            "label", ParameterType.STRING,
            "tap / type 专用：手指下或目标元素的文字（按钮、链接、输入框的标签），如「确认支付」。" +
                "命中敏感词会把本次调用提级为敏感操作、逐次确认；tap/type 不传会收到一条告警。",
            false
        ),
        "goal" to ToolParameter(
            "goal", ParameterType.STRING,
            "task 专用：要在这台手机上完成的事（一句话，子代理自己决定怎么一步步做）。",
            false
        ),
        "app" to ToolParameter(
            "app", ParameterType.STRING,
            "task 专用：目标 App（名称或包名）；给了就先打开它再开始。",
            false
        ),
        "maxSteps" to ToolParameter(
            "maxSteps", ParameterType.INTEGER,
            "task 专用：步数上限（默认 0 = 不设上限）。",
            false
        ),
        "model" to ToolParameter(
            "model", ParameterType.STRING,
            "task 专用：指定操作模型（可选，需支持图片输入）；不填则复用当前会话的模型。",
            false
        ),
        "traceId" to ToolParameter(
            "traceId", ParameterType.STRING,
            "trace 专用：要回放的任务 id（task 返回；为空则列出全部记录）。",
            false
        ),
    )

    /**
     * 命中敏感词的 tap/type 借用 NON_REMEMBERABLE_CAPABILITIES 里的能力位，让策略引擎把本次调用
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
        // 按文字点时 target 就是手指下的东西，没给 label 就拿它做敏感词判断。
        val label = args["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: args["target"]?.jsonPrimitive?.contentOrNull
        return ScreenActionSemantics.assess(label)
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String,
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "?"
        // 把真正会被拼进 shell 命令的参数原样回显。之前只显示 action，用户看不到 payload
        // 就被执行了——这是 Shizuku（adb shell）身份的命令，必须让用户看得见再确认。
        val payload = listOfNotNull(
            args["component"]?.jsonPrimitive?.contentOrNull?.let { "component = $it" },
            args["keycode"]?.jsonPrimitive?.contentOrNull?.let { "keycode = $it" },
            args["package"]?.jsonPrimitive?.contentOrNull?.let { "package = $it" },
            args["text"]?.jsonPrimitive?.contentOrNull?.let { "text = $it" },
            args["label"]?.jsonPrimitive?.contentOrNull?.let { "label = $it" },
            args["target"]?.jsonPrimitive?.contentOrNull?.let { "target = $it" },
            args["goal"]?.jsonPrimitive?.contentOrNull?.let { "goal = $it" },
        ).joinToString("\n")
        val assessment = assessArgs(args)
        val sensitive = assessment?.isSensitive == true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = if (sensitive) "敏感操作确认（不可记忆）" else "确认使用影子屏",
            summary = if (payload.isEmpty()) "action = $action" else "action = $action\n$payload",
            details = buildString {
                append("将经 Shizuku 在宿主上创建/操作无头虚拟屏（不会占用你的屏幕）。")
                append("上面列出的参数会原样传给系统命令，请确认后再放行。")
                assessment?.warning?.let { append("\n\n").append(it) }
            },
            argsPreview = argsPreview,
            // 意图声明：真正的「不可记忆」由 effectiveCapabilities 触发的引擎裁决兜住，
            // 引擎会用自身的 rememberDisabledReason 覆盖这一条（见 PermissionGate）。
            rememberDisabledReason = assessment?.warning?.takeIf { sensitive },
        )
    }

    /** 不带会话上下文的执行（工具调用链统一走 [executeWithContext]，这里只为兼容签名）。 */
    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = executeInternal(args, null)

    /** 带会话上下文：task 动作需要 sessionId（模型解析）。 */
    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        executeInternal(args, context.sessionId)

    // ------------------------------------------------------------------ MobileHand

    /** 确保影子屏在跑，必要时先打开目标 App（component 为包名或“包名/Activity”）。 */
    override suspend fun prepare(component: String?): String? {
        return try {
            if (vdController.refresh() == null) vdController.start()
            if (!component.isNullOrBlank()) vdController.launch(component)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "准备影子屏失败", e)
            e.message ?: "准备影子屏失败"
        }
    }

    /**
     * 看一次屏：复用 shot 分支（带坐标网格），取回 caption、图片与界面指纹。
     * 读得到控件时一并附上：模型照着控件的实际位置瞄准，比对着缩小的截图估坐标准。
     */
    override suspend fun observe(): HandObservation {
        return try {
            val snap = capture(withGrid = true)
            val nodes = runCatching { vdController.nodes() }.getOrNull()
                ?.filter { it.text.isNotBlank() || it.desc.isNotBlank() || it.editable }
                ?.take(MAX_OBSERVE_NODES)
                ?.joinToString("\n") { it.render() }
                ?.takeIf { it.isNotBlank() }
            HandObservation(
                caption = snap.caption,
                image = snap.shot.base64.takeIf { it.isNotEmpty() }
                    ?.let { AgentImage(mimeType = "image/jpeg", base64Data = it) },
                nodesText = nodes,
                width = snap.shot.inputWidth,
                height = snap.shot.inputHeight,
                fingerprint = snap.fingerprint,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            HandObservation(caption = "", image = null, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 只取界面指纹：不叠网格、不编 base64、不查前台（轮询要便宜），只做接图 + 降采样哈希。
     * 拿不到就返回 null，让循环降级为「动作后直接重看」，不把任务卡在截图失败上。
     */
    override suspend fun fingerprint(): ScreenFingerprint? {
        return try {
            val (_, file) = vdController.screenshot()
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            try {
                ScreenFingerprint.fromBitmap(bitmap)
            } finally {
                bitmap?.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.i(TAG, "取界面指纹失败：${e.message}")
            null
        }
    }

    /** 下落一个动作：复用现有 execute（tap/swipe/key/type/launch）。 */
    override suspend fun act(args: Map<String, JsonElement>): HandActResult {
        return when (val result = execute(args)) {
            is ToolResult.Success -> HandActResult(
                ok = true,
                message = (result.data as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: "ok",
            )

            is ToolResult.Error -> HandActResult(ok = false, message = result.message)
            is ToolResult.Partial -> HandActResult(ok = true, message = result.message)
        }
    }

    private suspend fun executeInternal(args: Map<String, JsonElement>, sessionId: String?): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少 action（start / status / stop / launch / shot / nodes / tap / swipe / key / type / task / trace）", "MISSING_ACTION")
        return try {
            when (action) {
                "task" -> runMobileTask(args, sessionId)
                "trace" -> showTrace(args)

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
                    val wantOcr = args["ocr"]?.jsonPrimitive?.booleanOrNull == true
                    val wantGrid = args["grid"]?.jsonPrimitive?.booleanOrNull ?: true
                    // OCR 直接吃画面，网格线会干扰识别，故 OCR 时不叠网格。
                    val snap = capture(withGrid = wantGrid && !wantOcr)
                    if (wantOcr) {
                        val image = AgentImage(mimeType = "image/jpeg", base64Data = snap.shot.base64)
                        val text = ocrEngine.recognize(image)
                        ToolResult.Success(
                            infoJson(
                                snap.info,
                                if (text.isNullOrBlank()) "已截图，但未识别到文字" else "已截图并识别出屏幕文字",
                                ocrText = text.orEmpty(),
                                shot = snap.shot,
                                caption = snap.caption,
                            )
                        )
                    } else {
                        ToolResult.Success(
                            infoJson(
                                snap.info,
                                "已截图（图片随结果返回，图中网格标注的数字即 input 像素坐标）",
                                shot = snap.shot,
                                caption = snap.caption,
                            ),
                            if (snap.shot.base64.isNotEmpty()) listOf(AgentImage(mimeType = "image/jpeg", base64Data = snap.shot.base64)) else emptyList(),
                        )
                    }
                }

                "nodes" -> {
                    val list = vdController.nodes()
                    ToolResult.Success(
                        buildJsonObject {
                            put("count", list.size)
                            put(
                                "message",
                                if (list.isEmpty()) "屏上没有读到可交互或带文字的控件（可能是自绘界面，改看截图）"
                                else "每行：文字 · 类型 [标志] @ 中心点像素坐标（c 可点 / e 可输入 / x、X 可勾选 / s 可滚动 / f 焦点 / - 禁用）\n" +
                                    list.joinToString("\n") { it.render() },
                            )
                        }
                    )
                }

                "tap" -> {
                    val target = args["target"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    if (target != null) {
                        val hit = vdController.tapByText(target)
                        return ToolResult.Success(buildJsonObject { put("message", "已点「${hit.render()}」") })
                    }
                    val x = args["x"]?.jsonPrimitive?.intOrNull
                    val y = args["y"]?.jsonPrimitive?.intOrNull
                    if (x == null || y == null) return ToolResult.Error("tap 需要 x / y，或给 target（控件上的文字）", "MISSING_ARGS")
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

                "type" -> {
                    val text = args["text"]?.jsonPrimitive?.contentOrNull
                    if (text == null) return ToolResult.Error("type 需要 text", "MISSING_ARGS")
                    val submit = args["submit"]?.jsonPrimitive?.booleanOrNull ?: false
                    vdController.type(text, submit)
                    ToolResult.Success(
                        buildJsonObject {
                            put("message", if (submit) "已输入文字并回车" else "已输入文字")
                        }
                    )
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

    /** task 动作：把目标交给手机操作子代理（一次调用一个结果）。 */
    private suspend fun runMobileTask(args: Map<String, JsonElement>, sessionId: String?): ToolResult {
        val goal = args["goal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (goal.isEmpty()) {
            return ToolResult.Error("task 需要 goal（要在这台手机上完成的事）", "MISSING_GOAL")
        }
        val outcome = mobileOperator.run(
            hand = this,
            goal = goal,
            app = args["app"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            maxSteps = args["maxSteps"]?.jsonPrimitive?.intOrNull ?: 0,
            modelOverride = args["model"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            sessionId = sessionId,
        )
        return ToolResult.Success(
            buildJsonObject {
                put("outcome", outcome.status)
                put("steps", outcome.steps)
                put("traceId", outcome.traceId)
                put("message", outcome.render())
            }
        )
    }

    /** trace 动作：traceId 为空列出全部记录，否则回放一份纯文本。 */
    private suspend fun showTrace(args: Map<String, JsonElement>): ToolResult {
        val id = args["traceId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (id.isEmpty()) {
            val list = mobileTraceStore.list()
            val text = if (list.isEmpty()) "（还没有手机操作记录）" else list.joinToString("\n") {
                "- ${it.id} [${it.status}] ${it.steps} 步 · ${it.goal}"
            }
            return ToolResult.Success(buildJsonObject { put("message", text); put("count", list.size) })
        }
        val text = mobileTraceStore.renderText(id) ?: return ToolResult.Error("没有找到 trace：$id", "TRACE_NOT_FOUND")
        return ToolResult.Success(buildJsonObject { put("id", id); put("message", text) })
    }

    private fun infoJson(
        info: VdInfo,
        message: String,
        ocrText: String? = null,
        shot: ShotImage? = null,
        caption: String? = null,
    ): JsonElement = buildJsonObject {
        put("message", message)
        if (!caption.isNullOrBlank()) put("caption", caption)
        put("running", true)
        put("displayId", info.displayId)
        if (info.sfDisplayId.isNotEmpty()) put("sfDisplayId", info.sfDisplayId)
        put("width", info.width)
        put("height", info.height)
        put("dpi", info.dpi)
        if (ocrText != null) put("text", ocrText)
        if (shot != null && shot.imageWidth > 0) {
            put("imageWidth", shot.imageWidth)
            put("imageHeight", shot.imageHeight)
            put("scale", shot.scale)
            put(
                "coordinateHint",
                "返回图片已缩放：图上量到的像素 ×%s（=1/scale）才是 tap 用的坐标；".format("%.3f".format(1f / shot.scale)) +
                    "图中网格线标注的数字已是 input 坐标，可直接照读（宽 ${shot.inputWidth} 高 ${shot.inputHeight}）。",
            )
        }
    }

    /** 一次截图的完整产物：影子屏信息、给模型的图、caption、界面指纹。 */
    private data class ScreenCapture(
        val info: VdInfo,
        val shot: ShotImage,
        val caption: String,
        val fingerprint: ScreenFingerprint?,
    )

    /**
     * 截一屏并一并算出 caption 与界面指纹（只解一次图）。[withGrid] 为真时图上叠一层网格——
     * 网格标的始终是**像素**（tap/swipe 用的那套），归一化只在手机操作子代理内部发生。
     */
    private suspend fun capture(withGrid: Boolean): ScreenCapture {
        val (info, file) = vdController.screenshot()
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
        try {
            val shot = encodeScreenshot(bitmap, withGrid)
            val (pkg, activity) = shadowForeground(info.displayId)
            val caption = ScreenActionSemantics.caption(
                packageName = pkg,
                activity = activity,
                width = shot.inputWidth.takeIf { it > 0 } ?: info.width,
                height = shot.inputHeight.takeIf { it > 0 } ?: info.height,
                keyboardShown = shadowKeyboardShown(),
            )
            return ScreenCapture(info, shot, caption, ScreenFingerprint.fromBitmap(bitmap, pkg, activity))
        } finally {
            bitmap?.recycle()
        }
    }

    /** 影子屏上的前台包名与 Activity（best-effort：拿不到返回 null，不让截图因此失败）。 */
    private suspend fun shadowForeground(displayId: Int): Pair<String?, String?> = runCatching {
        val out = hostShell.run(
            "dumpsys activity activities | awk '/Display #$displayId \\(activities from top to bottom\\)/{f=1;next}" +
                " f&&/Display #/{exit} f&&/topResumedActivity/{print;exit}'",
            15_000L,
        ).output
        val m = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)").find(out)
        m?.groupValues?.get(1) to m?.groupValues?.get(2)
    }.getOrNull() ?: (null to null)

    /** 键盘是否弹出（best-effort：dumpsys 报得不明就返回 null，caption 里略去）。 */
    private suspend fun shadowKeyboardShown(): Boolean? = runCatching {
        val out = hostShell.run("dumpsys input_method | grep -m3 -E 'mInputShown|mShowRequested'", 10_000L).output
        when {
            out.contains("mInputShown=true") -> true
            out.contains("mInputShown=false") -> false
            else -> null
        }
    }.getOrNull()

    /** 一次截图的编码结果：base64 与图上像素/缩放信息，供模型换算坐标。 */
    private data class ShotImage(
        val base64: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val inputWidth: Int,
        val inputHeight: Int,
        val scale: Float,
    )

    /**
     * 截图转 JPEG base64：长边压到 ≤1440 控制给模型的体积；[withGrid] 为真时在图上
     * 叠加坐标网格，网格标注的是 **input（未缩放）像素坐标**，让模型照读即可点击。
     */
    private fun encodeScreenshot(bitmap: Bitmap?, withGrid: Boolean): ShotImage {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return ShotImage("", 0, 0, 0, 0, 1f)
        // 原图就是设备真实像素，也是 tap 使用的坐标系（不依赖 dumpsys 报的尺寸）。
        val inputW = bitmap.width
        val inputH = bitmap.height
        val longest = maxOf(inputW, inputH)
        val ratio = if (longest > MAX_IMAGE_EDGE) MAX_IMAGE_EDGE.toFloat() / longest else 1f
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (inputW * ratio).toInt().coerceAtLeast(1),
                (inputH * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val scale = scaled.width.toFloat() / inputW
        val annotated = if (withGrid && inputW > 0 && inputH > 0) drawGrid(scaled, inputW, inputH, scale) else scaled
        val bos = ByteArrayOutputStream()
        annotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos)
        return ShotImage(
            base64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP),
            imageWidth = annotated.width,
            imageHeight = annotated.height,
            inputWidth = inputW,
            inputHeight = inputH,
            scale = scale,
        )
    }

    /** 在缩略图上叠坐标网格：细线半透明不盖内容，顶部信息条给出缩放比与尺寸。 */
    private fun drawGrid(src: Bitmap, inputW: Int, inputH: Int, scale: Float): Bitmap {
        val bmp = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
        val canvas = Canvas(bmp)
        val textSize = (bmp.width / 32f).coerceIn(18f, 40f)
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x5533B5FF
            strokeWidth = 1f
        }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
        val barH = textSize + 8f
        val step = gridStep(minOf(inputW, inputH))
        var x = step
        while (x < inputW) {
            val px = x * scale
            canvas.drawLine(px, 0f, px, bmp.height.toFloat(), linePaint)
            drawLabel(canvas, x.toString(), px + 2f, barH + textSize, labelText, labelBg)
            x += step
        }
        var y = step
        while (y < inputH) {
            val py = y * scale
            canvas.drawLine(0f, py, bmp.width.toFloat(), py, linePaint)
            drawLabel(canvas, y.toString(), 3f, py - 3f, labelText, labelBg)
            y += step
        }
        val caption = "input ${inputW}x$inputH   image ${bmp.width}x${bmp.height}   tap = image / ${(scale * 1000).toInt() / 1000f}"
        canvas.drawRect(0f, 0f, bmp.width.toFloat(), barH, labelBg)
        canvas.drawText(caption, 4f, textSize + 3f, labelText)
        return bmp
    }

    /** 网格步长：让短边约分 6 段，取 1/2/5×10^k 的整值，不小于 50。 */
    private fun gridStep(shortSide: Int): Int {
        val target = (shortSide / 6).coerceAtLeast(1)
        var pow = 1
        while (pow * 10 < target) pow *= 10
        val step = listOf(1, 2, 5, 10).map { it * pow }.firstOrNull { it >= target } ?: pow * 10
        return step.coerceAtLeast(50)
    }

    /** 画一个带底色的小标签（网格坐标值），底色避免文字被画面吃掉。 */
    private fun drawLabel(canvas: Canvas, text: String, x: Float, baseline: Float, tp: Paint, bg: Paint) {
        val w = tp.measureText(text)
        val h = tp.textSize
        canvas.drawRoundRect(x - 2f, baseline - h, x + w + 2f, baseline + h * 0.3f, 4f, 4f, bg)
        canvas.drawText(text, x, baseline, tp)
    }
}
