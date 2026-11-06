package com.aharou.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Brand
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AgentUIMessage
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

/**
 * 工具调用分组的活动摘要：把组内调用按「用户视角」归类计数。
 *
 * 折叠行只写「N 次工具调用」时，用户得点开才知道这一批到底干了什么；分组里本来就带着
 * 每次调用的工具名，分类计数能把这件事提前到折叠行上。归类口径与 [toolRunningLabelRes]
 * 是同一套「用户视角」划分（读文件与列目录都算读），改一处记得同步另一处。
 */
internal enum class ToolActionKind(@StringRes val labelRes: Int) {
    READ_FILE(R.string.chat_tool_group_read_files),
    WRITE_FILE(R.string.chat_tool_group_write_files),
    RUN_COMMAND(R.string.chat_tool_group_run_commands),
    SEARCH(R.string.chat_tool_group_search),
    BROWSE(R.string.chat_tool_group_browse),
    SUBAGENT(R.string.chat_tool_group_subagents),
    OTHER(R.string.chat_tool_group_other),
}

internal fun toolActionKind(toolName: String?): ToolActionKind = when (toolName?.lowercase()) {
    "readfile", "list", "viewimage" -> ToolActionKind.READ_FILE
    "editfile", "writefile" -> ToolActionKind.WRITE_FILE
    "bash", "terminal" -> ToolActionKind.RUN_COMMAND
    "search", "websearch", "webfetch", "git_blame", "check_dependencies", "diagnostics" ->
        ToolActionKind.SEARCH
    "browser" -> ToolActionKind.BROWSE
    // 子代理是耗时大头，和「其它工具」堆在一起会让摘要看不出这一批主要是在等它。
    "task" -> ToolActionKind.SUBAGENT
    else -> ToolActionKind.OTHER
}

/** 最多念几类动作：再多一行塞不下，尾巴折成「还有 N 类」。 */
private const val MAX_SUMMARY_KINDS = 4

/**
 * 折叠行摘要，按调用次数从多到少列动作，如「读取了 3 个文件、运行了 2 条命令」。
 *
 * 排序用次数而不是固定枚举顺序：一批调用里占多数的动作才是用户最该先看到的。
 * 次数相同时保持枚举声明顺序，保证同一批调用每次重组出来的文案一致（不会闪烁变化）。
 */
@Composable
internal fun toolGroupActivitySummary(messages: List<AgentUIMessage>): String {
    if (messages.isEmpty()) return ""
    val counts = LinkedHashMap<ToolActionKind, Int>()
    messages.forEach { message ->
        val kind = toolActionKind(message.toolName)
        counts[kind] = (counts[kind] ?: 0) + 1
    }
    val ordered = counts.entries.sortedByDescending { it.value }
    val shown = ordered.take(MAX_SUMMARY_KINDS)
    val restKinds = ordered.size - shown.size
    val parts = shown
        .map { (kind, count) -> stringResource(kind.labelRes, count) }
        .toMutableList()
    if (restKinds > 0) parts += stringResource(R.string.chat_tool_group_more_kinds, restKinds)
    return parts.joinToString(stringResource(R.string.chat_tool_group_join))
}

/**
 * 工具调用分组头：DSH 把一轮任务里连续的工具调用折成一行，**默认收起**，
 * 点一下手动展开/收起（运行中也不自动弹开）。
 * 布局对齐思考行：左侧工具图标（锤子/施工） + 中间活动摘要 + 折叠箭头。
 *
 * [running] 为真时给这一行文案本身走涟漪高光，表示这批调用还在跑。
 * 不再另挂三个跳动的点，也不在这里重复场景文案（「正在编辑文件」这类说的是**具体哪个工具**，
 * 展开后由每条工具行的运行状态给出，见 [ToolMessageBody]）。
 *
 * 单独放在本文件而不是 [ToolMessageComponents]：后者贴着单文件行数棘轮，只能缩不能涨。
 */
@Composable
internal fun ToolCallGroupHeader(
    count: Int,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    /** 活动摘要（如「读取了 3 个文件、运行了 2 条命令」）；为空时回退到纯计数文案。 */
    summary: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight)
            .clip(RoundedCornerShape(ChatStyle.panelCorner))
            .clickable(
                // 无障碍：点这一行会展开还是收起，读屏能念出来（展开状态本身由摘要文本表达）
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Construction,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(ChatStyle.rowIconSize)
        )
        Spacer(Modifier.width(Spacing.sm))
        // 权重挂在外层占位，涟漪画在文字自身的尺寸上：光带只扫摘要这几个字，
        // 不会在整行空白里慢慢爬（见 Modifier.rippleHighlight）。
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RippleText(
                text = summary ?: stringResource(R.string.chat_tool_calls_count, count),
                highlight = if (running) Color.White else null
            )
        }
        Icon(
            imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            modifier = Modifier.size(18.dp)
        )
    }
}
