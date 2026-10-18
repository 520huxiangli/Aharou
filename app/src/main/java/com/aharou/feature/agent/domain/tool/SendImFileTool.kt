package com.aharou.feature.agent.domain.tool

import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.ImBotBridge
import java.io.File
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把本地文件发回聊天里（微信 / 飞书 / QQ / Telegram）。
 *
 * 发给「最近说过话的那个聊天」：四个平台各有一个桥，挑当前在跑、且真的有活跃会话的那个发。
 * 一个都没在跑就报错，不猜平台。
 *
 * 桥用 [Provider] 延迟取：桥自己要注入 [AgentTurnRunner]，而 agent 又要注入本工具，
 * 直接注入会形成 Dagger 依赖环。
 */
@Singleton
class SendImFileTool @Inject constructor(
    @param:Named("telegram") private val telegram: Provider<ImBotBridge>,
    @param:Named("weixin") private val weixin: Provider<ImBotBridge>,
    @param:Named("feishu") private val feishu: Provider<ImBotBridge>,
    @param:Named("qq") private val qq: Provider<ImBotBridge>
) : AgentTool() {

    override val name: String = "sendToChat"

    override val description: String =
        "把本地文件或图片发送到当前正在聊天的 IM 会话（微信/飞书/QQ/Telegram）。" +
            "只在用户通过 IM 与你对话时可用；把要做完的文件写到本地后调这个工具发出去。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "path" to ToolParameter(
            name = "path",
            type = ParameterType.STRING,
            description = "要发送的本地文件绝对路径，须在当前工作区或应用私有目录内",
            required = true
        ),
        "caption" to ToolParameter(
            name = "caption",
            type = ParameterType.STRING,
            description = "随文件一起发送的说明文字，可省略",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (path.isBlank()) return ToolResult.Error("缺少 path 参数", "INVALID_ARGS")
        val file = File(path)
        if (!file.exists() || !file.isFile) return ToolResult.Error("文件不存在：$path", "NOT_FOUND")

        val caption = args["caption"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val kind = when (file.extension.lowercase()) {
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> ImAttachment.Kind.IMAGE
            "mp3", "wav", "ogg", "m4a", "amr", "silk" -> ImAttachment.Kind.AUDIO
            "mp4", "mov", "mkv", "webm" -> ImAttachment.Kind.VIDEO
            else -> ImAttachment.Kind.FILE
        }

        val running = listOf(telegram, weixin, feishu, qq).map { it.get() }.filter { it.isRunning() }
        if (running.isEmpty()) {
            return ToolResult.Error("当前没有正在运行的 IM 渠道，发不出去", "NO_CHANNEL")
        }
        for (bridge in running) {
            if (bridge.activeChatId() == null) continue
            if (bridge.sendFile(file, kind, caption)) {
                return ToolResult.Success(JsonPrimitive("已发送：${file.name}（${file.length()} 字节）"))
            }
        }
        return ToolResult.Error("没有可用的活跃会话，或平台拒绝了这个文件", "SEND_FAILED")
    }
}
