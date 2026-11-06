package com.aharou.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.agent.domain.workflow.ToolArgsProgress
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HelpCircle
import compose.icons.feathericons.Image
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool

/**
 * 工具名 → 状态字形 / 场景文案两张映射表：忽略大小写按工具注册名取值。
 *
 * 单独成文件是因为 [ToolMessageComponents] 受单文件行数棘轮约束，只能缩不能涨。
 */

/** 工具名字形映射：键与 [com.aharou.feature.agent.domain.tool.ToolRegistry] 注册名一致（比对时忽略大小写）。 */
internal fun toolIcon(toolName: String?): ImageVector = when (toolName?.lowercase()) {
    "editfile", "writefile", "generateimage" -> FeatherIcons.Edit3
    "readfile", "list", "sendfile" -> FeatherIcons.FileText
    "viewimage" -> FeatherIcons.Image
    "search", "websearch", "webfetch" -> FeatherIcons.Search
    "browser" -> FeatherIcons.Globe
    "bash", "terminal" -> FeatherIcons.Terminal
    "askuserquestion" -> FeatherIcons.HelpCircle
    "todo" -> FeatherIcons.Check
    "task" -> FeatherIcons.Cpu
    "memory" -> FeatherIcons.Database
    else -> FeatherIcons.Tool
}

/**
 * 工具执行中的场景文案：把工具名归成用户一眼能懂的「正在做什么」，供统一忙碌指示器
 * （[AgentBusyIndicator] / [RippleText]）使用。
 *
 * 键与 [toolIcon] 一致（工具注册名，忽略大小写）。归类原则是「用户视角」而不是「实现视角」：
 * 读文件与列目录都算「正在读取文件」，联网搜索与抓网页都算「正在联网搜索」。工具名在执行前
 * 就已到达（`AgentEvent.ToolCallStarted`），所以文案能跟着实际动作走，而不必停在「正在思考」。
 * MCP 动态注册工具、自定义工具等未归类的，一律回落 [R.string.chat_status_calling_tool]。
 */
@StringRes
internal fun toolRunningLabelRes(toolName: String?): Int = when (toolName?.lowercase()) {
    "editfile", "writefile" -> R.string.chat_status_editing_file
    "readfile", "list", "sendfile" -> R.string.chat_status_reading_file
    "viewimage" -> R.string.chat_status_viewing_image
    "search", "websearch", "webfetch" -> R.string.chat_status_searching_web
    "browser" -> R.string.chat_status_browsing_web
    "bash", "terminal" -> R.string.chat_status_running_command
    "askuserquestion" -> R.string.chat_status_asking_user
    "generateimage" -> R.string.chat_status_generating_image
    "todo" -> R.string.chat_status_updating_todo
    "task" -> R.string.chat_status_starting_subagent
    "memory" -> R.string.chat_status_reading_memory
    "check_dependencies" -> R.string.chat_status_checking_dependencies
    "diagnostics" -> R.string.chat_status_checking_diagnostics
    "git_blame" -> R.string.chat_status_querying_blame
    else -> R.string.chat_status_calling_tool
}

/** 参数流式进度的一句话文案：资源 id + 单个整型参数，`stringResource` 与 `getString` 都能直接排。 */
internal data class ToolProgressLabel(@StringRes val resId: Int, val arg: Int)

/**
 * 把工具参数流式进度渲染成「正在写入 342 行」这类一句话。
 *
 * 只对写文件的工具有意义：`Bash` 这类长命令的参数同样会流式传输，但「正在写入 N 行」
 * 描述的是文件内容，套在命令上会让人误以为在写盘。进度为空或不是写文件工具时返回 null，
 * 调用方沿用 [toolRunningLabelRes] 的通用场景文案，与加进度之前的行为一致。
 */
internal fun toolProgressLabel(toolName: String?, progress: ToolArgsProgress?): ToolProgressLabel? {
    if (progress == null || progress.chars <= 0) return null
    val writingFile = toolName?.lowercase() == "editfile" || toolName?.lowercase() == "writefile"
    if (!writingFile) return null
    return if (progress.lines >= MIN_LINES_FOR_LINE_COUNT) {
        ToolProgressLabel(R.string.chat_status_writing_lines, progress.lines)
    } else {
        ToolProgressLabel(R.string.chat_status_writing_kb, (progress.chars + 1023) / 1024)
    }
}

/** 行数太少时报「1 行」没有意义（短文件、单行 JSON），按体积说更自然。 */
private const val MIN_LINES_FOR_LINE_COUNT = 5

/**
 * 等待期间那一行状态文案：进度 > 工具场景 > 兜底的「正在思考」。
 *
 * 三级回落写在一处，免得调用点自己串条件时把顺序弄反——进度比场景更具体，
 * 有进度还报「正在编辑文件」等于把信息退回去了。
 */
@Composable
internal fun toolStatusLabel(toolName: String?, progress: ToolArgsProgress?): String =
    toolProgressLabel(toolName, progress)?.let { stringResource(it.resId, it.arg) }
        ?: toolName?.let { stringResource(toolRunningLabelRes(it)) }
        ?: stringResource(R.string.chat_status_thinking)
