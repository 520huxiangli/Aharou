package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.hasVisibleContent

/**
 * 消息级的渲染判定与「单条消息 → 渲染项」转换：分组成员行缩进、分组判定、操作行 / 常显 / 结果判定，
 * 以及 [ChatRenderItem] 的产出（[messageRenderItems]）。
 *
 * 单独成文件是因为 [AIChatPanel] 受单文件行数棘轮约束，只能缩不能涨。
 */

/**
 * 分组展开时成员行的左缩进：分组头与成员行行首元素原本左右完全对齐（同一格 16dp 图标 + 同一间距），
 * 看不出从属关系；给成员整行缩进一级，分组头是父、成员行是子，层级一眼可辨。
 * 固定取 16dp（不用 [Spacing.lg]：紧凑密度下它只有 14dp，缩进会随屏幕形态忽大忽小）。
 */
internal val ToolGroupMemberPadding = PaddingValues(start = 16.dp)

/**
 * 该消息是否参与「连续工具调用」分组。
 *
 * 上下文压缩失败/摘要、后台通知这些 TOOL 消息各有专用渲染分支（见 [AgentMessageItem] 的早退），
 * 混进分组会被当成普通工具行，故一并排除；普通消息（用户/助手）天然打断分组。
 *
 * **带附件的工具（`sendFile` / `generateImage`）也不分组**：它们产出的文件行就是结果本身，
 * 折进「N 次工具调用」后随分组默认收起，等于把发来的文件藏起来；留作顶层 item 才常显。
 */
internal fun AgentUIMessage.isGroupableTool(): Boolean =
    role == MessageRole.TOOL && !isCompactionFailure && !isContextSummary &&
        !isCompactionMarker && !isBackgroundNotification && !isSamplingLoopStop && attachments.isEmpty()

/** 分组标识：取组内首条消息 id，保证一批工具调用在追加过程中 item key 稳定（不会重建导致视口跳动）。 */
internal fun toolGroupKey(first: AgentUIMessage): String = "toolgroup:${first.id}"

/**
 * 该助手消息是否会渲染出「气泡下方的元信息行」——即能不能承接那排「复制 / 更多」按钮。
 *
 * 压缩标记、上下文摘要、压缩失败、后台通知条在 [AgentMessageItem] 里各有专用渲染分支并提前
 * return，不产生这一行；纯思考无正文的助手消息同理（没有正文可复制）。把「最新一条」的按钮
 * 挂到它们身上，整段会话就一个按钮都不剩。
 */
internal fun AgentUIMessage.rendersActionRow(): Boolean =
    role == MessageRole.ASSISTANT &&
        !isCompactionMarker && !isContextSummary && !isCompactionFailure && !isBackgroundNotification &&
        !isSamplingLoopStop &&
        (content.hasVisibleContent() || attachments.isNotEmpty())

/**
 * 单条消息（非工具分组）在一次渲染中占据的 item：
 * 超长助手正文拆成多条有界 chunk，其余消息 1:1。
 */
internal fun messageRenderItems(message: AgentUIMessage, showSoulHeader: Boolean = false): List<ChatRenderItem> {
    val canSplit = message.role == MessageRole.ASSISTANT &&
        !message.isCompactionMarker &&
        !message.isContextSummary &&
        !message.isCompactionFailure &&
        !message.isSamplingLoopStop &&
        !message.isBackgroundNotification &&
        message.content.length > CHUNK_SPLIT_THRESHOLD_CHARS
    if (!canSplit) {
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name, showSoulHeader = showSoulHeader)
        )
    }
    val slices = splitLongContent(message.content)
    if (slices.size <= 1) {
        // 只拆出一块（如无空行的超长单段）：等同普通消息。
        return listOf(
            ChatRenderItem(message = message, key = message.id, contentType = message.role.name, showSoulHeader = showSoulHeader)
        )
    }
    return slices.mapIndexed { idx, slice ->
        ChatRenderItem(
            message = message,
            key = "${message.id}#chunk$idx",
            contentType = "assistant-chunk",
            slice = slice,
            isChunkHeader = idx == 0,
            isChunkFooter = idx == slices.lastIndex,
            showSoulHeader = showSoulHeader && idx == 0,
        )
    }
}

/** 该消息在整轮折叠里是否「常显」——不参与过程折叠，无论轮展开与否都显示。 */
internal fun AgentUIMessage.isPersistentInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification ||
        isSamplingLoopStop ||
        (role == MessageRole.TOOL && attachments.isNotEmpty())

/** 能否作为一轮的「结果」：最后一条有可见正文的普通助手消息。 */
internal fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isPersistentInTurn() && content.hasVisibleContent()
