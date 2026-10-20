package com.aharou.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.aharou.R
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
    else -> R.string.chat_status_calling_tool
}
