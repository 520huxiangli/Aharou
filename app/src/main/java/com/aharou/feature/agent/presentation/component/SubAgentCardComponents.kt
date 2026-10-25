package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Brand
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Users
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 子代理派发卡片（`task` 工具的 create 分支）+ 从 [ToolMessageComponents] 迁出的工具行公共小件。
 *
 * 迁出的那几件不是子代理专用，只是单文件行数棘轮只许缩减：卡片分支要加进 ToolMessageComponents，
 * 就得先把这些可复用件搬出来腾位置。搬家只换了文件，调用点与行为一字未改。
 */

// ---------------------------------------------------------------- 工具行公共小件（迁自 ToolMessageComponents）

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false)

/**
 * 从工具结果 transport JSON 顶层的 `notifications` 字段提取搭车通知（后台任务/子代理完成）。
 * 该字段由 [com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow] 在 AI 忙碌时注入。
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message"
        )
    }
}

/** 工具卡片底部的搭车通知提示条：状态点 + 摘要。 */
@Composable
internal fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = MaterialTheme.semanticColors.diffAdd,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = MaterialTheme.semanticColors.diffRemove,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ---------------------------------------------------------------- 子代理派发卡片

/** 卡片状态：与 [com.aharou.feature.agent.data.local.entity.AgentTaskStatus] 一一对应。 */
internal enum class SubAgentCardStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

/**
 * 一张派发卡片的展示状态。只放卡片要显示的东西（状态、结果摘要、改动文件）：
 * 任务指令本身就是派发参数里的 `description`，不必再从库里读一遍。
 */
internal data class SubAgentCardState(
    val status: SubAgentCardStatus = SubAgentCardStatus.RUNNING,
    val summary: String? = null,
    val changedFiles: List<String> = emptyList(),
)

/**
 * `task` 工具一次 create 派发解析出来的卡片内容。
 *
 * [sessionId] 为空表示结果还没回来（占位行 / 刚派发）：卡片先以「运行中」出现，
 * 结果落地后同一张卡换成摘要与改动文件。
 */
internal data class ParsedSubAgentDispatch(
    val sessionId: String?,
    val agentName: String?,
    val taskText: String,
)

/** 卡片里最多逐条列几个改动文件，其余只报数量（绝对路径很占版面）。 */
private const val CHANGED_FILES_SHOWN_MAX = 4

private const val StatusPillAlpha = 0.12f

/** 任务描述兜底截断：`prompt` 可能是一整段需求，卡片只显示第一行。 */
private const val TASK_TEXT_MAX = 120

/**
 * 容错解析 `task` 工具的参数与结果。任何一步拿不到必需字段就返回 null（退回普通工具行），
 * 不抛异常——工具行渲染路径上崩一次就是整页聊天白掉。
 *
 * 出卡条件（缺一不可）：参数是对象、`action` 是 create（或缺省）、拿得到任务描述。
 * 结果一侧：空白/占位前缀 = 派发中；transport 形态且 `status=error` = 不出卡；
 * `data` 可能是对象也可能是包着 JSON 文本的字符串，两种都试；拿不到子会话 id = 不出卡。
 */
internal fun parseSubAgentDispatch(argsJson: String?, resultJson: String?): ParsedSubAgentDispatch? {
    val args = argsJson?.let { raw ->
        runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    } ?: return null
    // read / stop / send / list / team / tell / grant / roles / pipeline 都不是「派发」，保持普通工具行。
    val action = args.stringOrNull("action")?.lowercase()
    if (!action.isNullOrEmpty() && action != "create") return null
    val description = args.stringOrNull("description")?.replace(Regex("\\s+"), " ")?.trim()
    val promptLine = args.stringOrNull("prompt")
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.replace(Regex("\\s+"), " ")?.trim()
    val taskText = (description?.takeIf { it.isNotBlank() } ?: promptLine?.takeIf { it.isNotBlank() })
        ?.take(TASK_TEXT_MAX) ?: return null
    val argAgent = args.stringOrNull("agent")?.takeIf { it.isNotBlank() }

    val raw = resultJson?.trim().orEmpty()
    val pending = raw.isEmpty() ||
        raw.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        raw.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    if (pending) return ParsedSubAgentDispatch(sessionId = null, agentName = argAgent, taskText = taskText)

    // 只认 transport 形态（顶层带 status 的对象）；旧版 Result.toString、中断说明等一律不出卡。
    val transport = parseToolTransport(raw.withoutToolStatusPrefix()) ?: return null
    val status = transport.stringOrNull("status")
    if (status != "success" && status != "partial") return null
    val data = transport["data"].asJsonObjectOrNull() ?: return null
    val sessionId = data.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
    return ParsedSubAgentDispatch(
        sessionId = sessionId,
        agentName = data.stringOrNull("agent")?.takeIf { it.isNotBlank() } ?: argAgent,
        taskText = taskText
    )
}

/** 该 TOOL 消息是否渲染成派发卡片；工具行的卡片分支与「卡片不随过程折叠」共用这一条判定。 */
internal fun AgentUIMessage.isSubAgentDispatchCard(): Boolean =
    role == MessageRole.TOOL && toolName == "task" && !isError &&
        parseSubAgentDispatch(toolArgs, content) != null

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** `data` 既可能是对象（当前形状），也可能是包着一层 JSON 文本的字符串（历史落库形状），两种都兼容。 */
private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
    val element = this ?: return null
    return when (element) {
        is JsonObject -> element
        is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.startsWith("{") }
            ?.let { text -> runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() }
        else -> null
    }
}

/**
 * 卡片与宿主的窄接口：状态读持久化快照（[cards]），[refresh] 触发一次读库补全，
 * [openSession] 点卡片进入子会话。
 *
 * 走 CompositionLocal 而不是层层加参数：卡片渲染在 ToolMessageBody 里，中间还隔着
 * AgentMessageItem，为三个回调把整条参数链改一遍不值得。
 */
internal class SubAgentCardBridge(
    val cards: StateFlow<Map<String, SubAgentCardState>>,
    val refresh: (String) -> Unit,
    val openSession: (String) -> Unit,
)

/** 没有宿主提供时的兜底：卡片仍会渲染，只是没有状态可读、点不动。 */
private val EmptySubAgentCardBridge = SubAgentCardBridge(
    cards = MutableStateFlow<Map<String, SubAgentCardState>>(emptyMap()),
    refresh = {},
    openSession = {},
)

internal val LocalSubAgentCardBridge = compositionLocalOf { EmptySubAgentCardBridge }

/**
 * 子代理派发卡片：标题行（代理名 + 状态药丸）、任务描述，结束后换成结果摘要与改动文件，
 * 点击进入子会话。
 *
 * [running] 只是兜底：状态优先取 [SubAgentCardState]（持久化任务行），拿不到时才按工具行是否在跑推断。
 */
@Composable
internal fun SubAgentDispatchCard(
    dispatch: ParsedSubAgentDispatch,
    running: Boolean,
    modifier: Modifier = Modifier
) {
    val bridge = LocalSubAgentCardBridge.current
    val cards by bridge.cards.collectAsStateWithLifecycle()
    val sessionId = dispatch.sessionId
    val state = sessionId?.let { cards[it] }
    // 落地后补一次读库：跨进程重启的会话没有事件可等，卡片靠这一次拿到真实终态、摘要与改动文件。
    // 只在本卡还没有终态时读：拿到终态后不必再为每次重新进入组合重复扫一遍子会话消息。
    LaunchedEffect(sessionId, state?.status) {
        if (sessionId != null && (state == null || state.status == SubAgentCardStatus.RUNNING)) {
            bridge.refresh(sessionId)
        }
    }
    val status = state?.status
        ?: if (running || sessionId == null) SubAgentCardStatus.RUNNING else SubAgentCardStatus.COMPLETED
    val shape = RoundedCornerShape(ChatStyle.cardCorner)
    // 有子会话 id 就能点进去看它的全过程；派发中还不知道 id，先不可点。
    val openModifier = if (sessionId != null) {
        Modifier.clickable(
            onClickLabel = stringResource(R.string.subagent_card_open_session),
            onClick = { bridge.openSession(sessionId) }
        )
    } else {
        Modifier
    }
    val summary = state?.summary?.takeIf { it.isNotBlank() }
    val files = state?.changedFiles.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(chatCardSurfaceColor())
            .border(ChatStyle.hairline, chatHairlineColor(), shape)
            .then(openModifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = FeatherIcons.Users,
                contentDescription = null,
                tint = Brand.IconGray,
                modifier = Modifier.size(ChatStyle.rowIconSize)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = dispatch.agentName ?: stringResource(R.string.subagent_card_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            SubAgentStatusPill(status)
            if (sessionId != null) {
                Spacer(Modifier.width(Spacing.xs))
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = Brand.IconGray,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Text(
            text = dispatch.taskText,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (status != SubAgentCardStatus.RUNNING && summary != null) {
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (status != SubAgentCardStatus.RUNNING && files.isNotEmpty()) {
            Text(
                text = stringResource(R.string.subagent_card_changed_files, files.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            files.take(CHANGED_FILES_SHOWN_MAX).forEach { path ->
                Text(
                    text = path,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (files.size > CHANGED_FILES_SHOWN_MAX) {
                Text(
                    text = stringResource(
                        R.string.subagent_card_more_files,
                        files.size - CHANGED_FILES_SHOWN_MAX
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** 状态药丸：文字用状态色，底色取同色低透明度，不引入硬编码色值。 */
@Composable
private fun SubAgentStatusPill(status: SubAgentCardStatus) {
    val color = subAgentStatusColor(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(Radius.sm))
            .background(color.copy(alpha = StatusPillAlpha))
            .padding(horizontal = Spacing.sm, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(subAgentStatusLabelRes(status)),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun subAgentStatusColor(status: SubAgentCardStatus): Color {
    val colors = MaterialTheme.semanticColors
    return when (status) {
        SubAgentCardStatus.RUNNING -> colors.info
        SubAgentCardStatus.COMPLETED -> colors.success
        SubAgentCardStatus.FAILED -> MaterialTheme.colorScheme.error
        SubAgentCardStatus.CANCELLED -> colors.subtleText
        SubAgentCardStatus.INTERRUPTED -> colors.warning
    }
}

private fun subAgentStatusLabelRes(status: SubAgentCardStatus): Int = when (status) {
    SubAgentCardStatus.RUNNING -> R.string.subagent_card_status_running
    SubAgentCardStatus.COMPLETED -> R.string.subagent_card_status_completed
    SubAgentCardStatus.FAILED -> R.string.subagent_card_status_failed
    SubAgentCardStatus.CANCELLED -> R.string.subagent_card_status_cancelled
    SubAgentCardStatus.INTERRUPTED -> R.string.subagent_card_status_interrupted
}

/**
 * 派发卡片不随整轮过程折叠：被折叠头 / 工具分组藏起来的卡片，在这里补成顶层 item。
 *
 * 过程项在收起时根本不产出子项（见 `buildChatItems` 与 `flattenTurnProcesses`），卡片要是也跟着
 * 消失，用户就看不到这一轮派了哪些子代理、跑完改了什么 —— 那正是这张卡存在的理由。
 * 补出来的卡片仍按消息原序插回它本来的位置，展开/收起切换时不会在时间轴上跳来跳去。
 */
internal fun keepSubAgentCardsVisible(
    items: List<ChatRenderItem>,
    messages: List<AgentUIMessage>,
): List<ChatRenderItem> {
    // 只有「消息本体作为独立 item 渲染」才算已显示：工具分组头虽然挂着组内首条消息，
    // 但它收起时并不渲染成员行，卡片在该位仍须补出来。
    val rendered = items.asSequence()
        .filter { it.turnHeader == null && it.toolGroup == null }
        .mapTo(HashSet()) { it.message.id }
    val hidden = messages.filter { it.id !in rendered && it.isSubAgentDispatchCard() }
    if (hidden.isEmpty()) return items
    val order = messages.withIndex().associate { (index, message) -> message.id to index }
    val out = ArrayList<ChatRenderItem>(items.size + hidden.size)
    var next = 0
    for (item in items) {
        val index = order[item.message.id]
        if (index != null) {
            while (next < hidden.size && (order[hidden[next].id] ?: Int.MAX_VALUE) < index) {
                out += subAgentCardItem(hidden[next])
                next++
            }
        }
        out += item
    }
    while (next < hidden.size) {
        out += subAgentCardItem(hidden[next])
        next++
    }
    return out
}

private fun subAgentCardItem(message: AgentUIMessage) = ChatRenderItem(
    message = message,
    key = message.id,
    contentType = message.role.name,
)
