package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.REASONING_CONTENT_TYPE
import com.aharou.feature.agent.presentation.TOOL_GROUP_CONTENT_TYPE
import com.aharou.feature.agent.presentation.TURN_HEADER_CONTENT_TYPE
import com.aharou.feature.agent.presentation.TurnHeaderState
import com.aharou.feature.agent.presentation.hasVisibleContent
import com.aharou.feature.agent.presentation.leadingHeadKey
import com.aharou.feature.agent.presentation.leadingHeadSessionKey
import com.aharou.feature.agent.presentation.turnKeyOf

/**
 * 「消息 → 渲染项」的全部构造逻辑：分组判定、单条消息拆块（[messageRenderItems]）、整轮折叠与过程项
 * （[buildChatItems]）、以及待定位消息的查找（[findChatMessageIndex]）。
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

/**
 * 该消息在整轮折叠里是否「常显」——不参与过程折叠，无论轮展开与否都显示。
 *
 * 仅限带附件的工具（`sendFile` / `generateImage` 产出的文件行）：文件就是结果本身，折进
 * 整轮折叠等于把产出物藏起来。后台通知与压缩卡片只是过程性提示，随过程一起折叠
 * （见 [isSystemCardInTurn]）。
 */
internal fun AgentUIMessage.isPersistentInTurn(): Boolean =
    role == MessageRole.TOOL && attachments.isNotEmpty()

/**
 * 系统注入的卡片型消息：后台通知条、压缩标记 / 摘要 / 压缩失败、采样熔断提示。
 *
 * 它们折进轮过程，且不得被选为轮「结果」或轮末耗时锚点——压缩摘要是 ASSISTANT 角色，
 * 不排除会顶掉真正的回复正文。
 */
internal fun AgentUIMessage.isSystemCardInTurn(): Boolean =
    isCompactionMarker || isContextSummary || isCompactionFailure || isBackgroundNotification ||
        isSamplingLoopStop

/** 能否作为一轮的「结果」：最后一条有可见正文的普通助手消息（系统卡片不算，见 [isSystemCardInTurn]）。 */
internal fun AgentUIMessage.isResultCandidate(): Boolean =
    role == MessageRole.ASSISTANT && !isSystemCardInTurn() && content.hasVisibleContent()

/**
 * 长消息拆块渲染：
 *
 * 超长助手正文（长文档复述）如果塞成单条 LazyColumn item，撑到几屏深时手势命中区
 * 会在列表可见区之外，深处表格的横滑/长按复制/点击全部失效（原版深处交互失效的根因）。
 * 限高+内滚的单窗口方案又会让窗口与列表世界之间出现「接不上、独立一块」的断接。
 *
 * 拆块的思路是：分裂成多条「有界高度」的列表 item，每条都是普通气泡（思考只在首块、
 * 操作行只在末块、相邻块零间距无缝衔接），列表单一滚动轴——世界上下完全接通、深处
 * 交互（表 8+ 横滑/复制/点击）因每条 item 高度有界而全部恢复。
 *
 * 若正文长度不超过阈值，不拆块，与普通消息完全一致。
 *
 * internal（而非 private）是为了让 [buildChatItems] 的用例能断言渲染项，见 ToolGroupExpansionTest。
 */
internal data class ChatRenderItem(
    val message: AgentUIMessage,
    val key: String,
    val contentType: String,
    val slice: String? = null,
    val isChunkHeader: Boolean = true,
    val isChunkFooter: Boolean = true,
    /** 是否在该 item 上渲染「身份行」（Soul 图标 + 名字）：每轮助手回复的首条。 */
    val showSoulHeader: Boolean = false,
    /**
     * 非空表示这是一条「连续工具调用」分组头 item。成员各自仍是独立 item（仅在该组展开时生成），
     * 因此无论展开与否，任何一条 item 的高度都有界——这是深处交互（表格横滑、长按复制）不失效的前提。
     */
    val toolGroup: List<AgentUIMessage>? = null,
    /** 分组当前是否展开（已含用户手动覆盖的结果）。 */
    val groupExpanded: Boolean = true,
    /** 非空 = 整轮折叠头 item（「执行中 / 已完成 Xs」）。 */
    val turnHeader: TurnHeaderState? = null,
    /** 轮头携带的过程项（思考 / 工具分组 / 中间正文）：由 [flattenTurnProcesses] 平铺进主列表。 */
    val turnProcess: List<ChatRenderItem> = emptyList(),
    /** 是否渲染消息自带的思考块；结果块把思考抽为过程项后传 false。 */
    val reasoningVisible: Boolean = true,
    /** 工具分组平铺后的成员行：作为主列表的独立 item 渲染，需缩进一级（[ToolGroupMemberPadding]）。 */
    val groupMember: Boolean = false,
)

/**
 * 消息列表 → LazyColumn item 列表。
 *
 * 三件事：**整轮任务折叠**、长文拆块、**连续工具调用分组**。
 *
 * 先按「用户消息 → 下一个用户消息之前」切成轮（见 [splitChatTurns]）：每轮依次产出「用户消息 →
 * 轮头（携带过程项，仅当该轮有过程或正在运行）→ 常显项与结果」。过程项挂在轮头 item 内，再由
 * [flattenTurnProcesses] 无条件平铺进主列表；只有带附件的工具常显（见 [isPersistentInTurn]），
 * 压缩卡片与后台通知随过程折叠。
 * 结果 = 轮内最后一条有可见正文的普通助手消息（其思考抽为过程项）。
 *
 * 轮首之前剩下的散消息同样按「一轮过程」处理：消息按条数截断加载，窗口边界常切在某一轮中间，
 * 这一段缺的是轮首用户消息而不是折叠头——原样常显会在历史最前面摊开一整轮过程。它们补一个不带
 * 耗时的折叠头（key 见 [leadingHeadKey]），默认收起；窗口里一个轮首都找不到时块尾会随工具输出移动，
 * 改用 [leadingHeadSessionKey] 作折叠 key。
 *
 * 轮头展开态：没手动记录时「末轮且 agent 忙」默认展开、其余默认收起（[activeTurnKey]）；
 * 一旦用户在 [turnOverrides] 里点过，就以其选择为准，任务完成自动收起不会覆盖它。
 *
 * [turnKeys] 是上层（ViewModel 的 `turnMessageKeys`）记下的「消息 id → 轮 key」：分页把轮首补进来
 * 之前，这一截过程先挂在块尾生成的 `turn:head:<块尾 id>` 下，等轮首到齐后沿用旧 key，用户已经点开的
 * 整轮折叠与 LazyColumn item key 才不随窗口移动而丢。
 *
 * 身份行只挂每轮第一条有可见正文的助手消息；「一轮」由 [splitChatTurns] 直接给出，
 * 不再需要单独追踪运行态。
 *
 * 工具调用分组仍**默认收起**，只认 [groupOverrides] 的手动选择。
 */
internal fun buildChatItems(
    messages: List<AgentUIMessage>,
    groupOverrides: Map<String, Boolean>,
    turnOverrides: Map<String, Boolean> = emptyMap(),
    activeTurnKey: String? = null,
    sessionId: String? = null,
    turnKeys: Map<String, String> = emptyMap(),
): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>(messages.size)
    val (leading, turns) = splitChatTurns(messages)
    if (leading.isNotEmpty()) {
        val headKey = turnKeys[leading.last().id] ?: if (turns.isEmpty() && sessionId != null) {
            leadingHeadSessionKey(sessionId)
        } else {
            leadingHeadKey(leading.last())
        }
        // 这一截通常结尾就是上一轮的最终回复（窗口从某一轮中间切开）：它照旧常显，
        // 只有它的思考与更早的过程步骤折进折叠头，与完整轮次的呈现一致。
        val resultId = leading.last().takeIf { it.isResultCandidate() }?.id
        // 身份行不在这里补：这一截的轮首助手消息已被窗口切掉，给它挂身份行等于把身份行挂到轮中间。
        val process = buildTurnProcessItems(leading, resultId, groupOverrides, soulHeaderId = null)
        if (process.isNotEmpty()) {
            items += ChatRenderItem(
                message = leading.last(),
                key = "$headKey#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = headKey,
                    endMessageId = null,
                    running = false,
                    expanded = turnOverrides[headKey] ?: false,
                ),
                turnProcess = process,
            )
        }
        items += buildTurnPersistentItems(leading, resultId, soulHeaderId = null)
    }
    for (turn in turns) {
        items += messageRenderItems(turn.userMessage, showSoulHeader = true)
        val endAssistant = turn.messages.lastOrNull {
            it.role == MessageRole.ASSISTANT && !it.isSystemCardInTurn()
        }
        // 「是否正在跑」按规范 key 判定：key 可能被沿用成块尾生成的旧 key，不能拿它比 activeTurnKey。
        val running = turn.key == activeTurnKey
        // 轮 key 优先沿用旧记录：轮首用户消息刚被分页补进来时，这一轮的消息早已挂在块尾的
        // `turn:head:<块尾 id>` 下，就在同一帧里直接沿用它，不等 ViewModel 回头写入映射。
        val key = turnKeys[turn.userMessage.id]
            ?: turn.messages.firstNotNullOfOrNull { turnKeys[it.id] }
            ?: turn.key
        val resultId = if (running) null else turn.messages.lastOrNull { it.isResultCandidate() }?.id
        val soulHeaderId = turn.messages.firstOrNull {
            it.role == MessageRole.ASSISTANT && !it.isSystemCardInTurn() && it.content.hasVisibleContent()
        }?.id
        // 过程项挂在轮头 item 里一起展开（保证展开动画是整体高度变化）；常显项与结果另作独立 item。
        val process = buildTurnProcessItems(turn.messages, resultId, groupOverrides, soulHeaderId)
        val expanded = turnOverrides[key] ?: running
        if (process.isNotEmpty() || running) {
            items += ChatRenderItem(
                message = turn.userMessage,
                key = "$key#header",
                contentType = TURN_HEADER_CONTENT_TYPE,
                turnHeader = TurnHeaderState(
                    key = key,
                    endMessageId = endAssistant?.id,
                    running = running,
                    expanded = expanded,
                ),
                turnProcess = process,
            )
        }
        items += buildTurnPersistentItems(turn.messages, resultId, soulHeaderId)
    }
    return items
}

/** 一轮任务：轮首用户消息 + 其后的全部消息（助手 / 工具 / 压缩卡片等）。 */
internal class ChatTurn(
    val key: String,
    val userMessage: AgentUIMessage,
    val messages: List<AgentUIMessage>,
)

/** 该消息是否可作为轮起点（后台通知/压缩锚点虽可能是 USER 角色，但不是用户输入，见 [splitChatTurns]）。 */
internal fun AgentUIMessage.isTurnHead(): Boolean =
    role == MessageRole.USER && !isBackgroundNotification && !isCompactionMarker

/**
 * 按「用户消息」切成轮。返回 (轮首之前的散消息, 轮列表)。
 *
 * 后台通知虽可能是 USER 角色，但它是系统注入的提示条而非用户输入，不作为轮起点（归入当前轮内容）。
 * 压缩锚点同为 USER 角色（见 ContextCompactor），但它不渲染、只是个定位标记，当轮首会让那一轮
 * 失去可见的用户消息，同时把摘要卡片从轮末挑到轮头下方——也归入当前轮内容。
 */
internal fun splitChatTurns(messages: List<AgentUIMessage>): Pair<List<AgentUIMessage>, List<ChatTurn>> {
    val leading = ArrayList<AgentUIMessage>()
    val turns = ArrayList<ChatTurn>()
    var user: AgentUIMessage? = null
    var body: MutableList<AgentUIMessage>? = null
    for (message in messages) {
        if (message.isTurnHead()) {
            user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
            user = message
            body = ArrayList()
        } else {
            if (body == null) leading += message else body.add(message)
        }
    }
    user?.let { turns += ChatTurn(turnKeyOf(it.id), it, body?.toList().orEmpty()) }
    return leading to turns
}

/**
 * 轮内「过程」项（保持原序）：思考、连续工具调用分组、中间助手正文、压缩卡片与后台通知。挂在轮头 item 内一起展开。
 *
 * 结果消息的思考归过程（抽为一个 [REASONING_CONTENT_TYPE] 项）；带附件工具常显、不进过程，
 * 由 [buildTurnPersistentItems] 单独产出。
 */
private fun buildTurnProcessItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    groupOverrides: Map<String, Boolean>,
    soulHeaderId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>(messages.size)
    var i = 0
    while (i < messages.size) {
        val message = messages[i]
        if (message.isGroupableTool()) {
            var j = i
            while (j < messages.size && messages[j].isGroupableTool()) j++
            val members = messages.subList(i, j).toList()
            // 只有一条工具时不折成「1 次工具调用」，直接当普通工具行显示。
            if (members.size == 1) {
                out += messageRenderItems(members.first())
            } else {
                val key = toolGroupKey(members.first())
                out += ChatRenderItem(
                    message = members.first(),
                    key = key,
                    contentType = TOOL_GROUP_CONTENT_TYPE,
                    toolGroup = members,
                    groupExpanded = groupOverrides[key] == true,
                )
            }
            i = j
            continue
        }
        when {
            message.isPersistentInTurn() -> Unit
            message.id == resultId -> {
                if (!message.reasoning.isNullOrEmpty()) {
                    out += ChatRenderItem(
                        message = message,
                        key = "${message.id}#reasoning",
                        contentType = REASONING_CONTENT_TYPE,
                    )
                }
            }
            else -> out += messageRenderItems(message, message.id == soulHeaderId)
        }
        i++
    }
    return out
}

/** 轮内「常显」项（保持原序）：结果正文（拆块，不重复渲染思考）与带附件的工具行。 */
private fun buildTurnPersistentItems(
    messages: List<AgentUIMessage>,
    resultId: String?,
    soulHeaderId: String?,
): List<ChatRenderItem> {
    val out = ArrayList<ChatRenderItem>()
    for (message in messages) {
        if (message.id == resultId) {
            messageRenderItems(message, message.id == soulHeaderId)
                .forEach { out += it.copy(reasoningVisible = false) }
        } else if (message.isPersistentInTurn()) {
            out += messageRenderItems(message)
        }
    }
    return out
}

/**
 * 目标消息在顶层 item 列表里的下标；找不到返回 -1。
 *
 * 不能只比 `message.id == 目标`：超长正文被拆成 `${id}#chunk$idx` 多条 item，键前缀才是消息 id。
 * 过程项在 [flattenTurnProcesses] 之后也是顶层 item（`message.id` 不变），收起的整轮则不产出子项，
 * 这种情形返回 -1，由调用方先把那一轮展开再重试。
 */
internal fun findChatMessageIndex(items: List<ChatRenderItem>, messageId: String): Int =
    items.indexOfFirst { it.message.id == messageId || it.key.startsWith("$messageId#") }
