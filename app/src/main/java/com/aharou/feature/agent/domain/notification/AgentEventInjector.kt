package com.aharou.feature.agent.domain.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 把待送系统事件搭车注入一条工具结果。抽成接口便于各功能复用同一注入位置与语义，
 * 无需各自实现「往原始结果里塞事件」的细节。
 */
interface AgentEventInjector {
    /**
     * @param rawToolResult 原始工具结果（通常是 transport JSON 文本）
     * @param events 待送系统事件
     * @return 注入后仍可被下游解析的结果文本
     */
    fun inject(rawToolResult: String, events: List<PendingNotification>): String
}

/**
 * 默认实现：优先把事件作为 transport JSON 顶层的 `notifications` 字段注入，结果仍是合法 JSON，
 * UI 的 formatToolResult（只读 data/message）与各类结构化解析不受影响。
 * raw 已不是合法 JSON（如已被模式切换提示等纯文本追加过）时，退化为文本追加。
 */
class DefaultAgentEventInjector : AgentEventInjector {

    override fun inject(rawToolResult: String, events: List<PendingNotification>): String {
        if (events.isEmpty()) return rawToolResult
        val obj = runCatching { Json.parseToJsonElement(rawToolResult).jsonObject }.getOrNull()
            ?: return rawToolResult + "\n\n" + AgentNotificationFormatter.buildMessage(events)
        return JsonObject(obj + ("notifications" to AgentNotificationFormatter.buildJsonArray(events))).toString()
    }
}