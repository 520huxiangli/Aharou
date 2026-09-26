package com.aharou.feature.agent.domain.tool.file

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证识图会话历史（[AgentMessage] 多态 sealed class，含图片）经 JSON 落盘后能完整还原。
 * VisionSessionStore 依赖此序列化；sealed class 基类缺 @Serializable 会在运行时抛
 * "Serializer for class 'AgentMessage' is not found"，此处用与存储相同的 Json 配置做往返验证。
 */
class VisionSessionSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun messageHistory_roundTripsWithImages() {
        val messages = listOf(
            AgentMessage.UserMessage(
                content = "请对比这两张图片的差异",
                images = listOf(
                    AgentImage(mimeType = "image/jpeg", base64Data = "AAAA", path = "/a.jpg"),
                    AgentImage(mimeType = "image/png", base64Data = "BBBB", path = "/b.png")
                )
            ),
            AgentMessage.AssistantMessage(content = "左图是 A，右图是 B，主要差异在配色。"),
            AgentMessage.UserMessage(content = "左图右上角是什么？"),
            AgentMessage.AssistantMessage(content = "是一个红色按钮。")
        )

        val restored = json.decodeFromString<List<AgentMessage>>(json.encodeToString(messages))

        assertEquals(messages.size, restored.size)
        assertEquals(messages, restored)
    }
}
