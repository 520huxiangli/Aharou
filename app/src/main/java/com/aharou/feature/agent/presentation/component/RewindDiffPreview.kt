package com.aharou.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Brand
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.ChevronRotationStyle
import com.aharou.core.ui.ExpandableChevronIcon
import com.aharou.core.util.LineDiff
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager

/**
 * 回退预览用到的全部用户可见文案。
 *
 * 文案从参数传入而不是在组件里直接 stringResource：本仓库不允许 agent 改 strings.xml，
 * 等主代理把 key 落位后，调用处换成 `stringResource(R.string.xxx, ...)` 即可，组件本身不用改。
 * [summary] / [conflictNotice] / [truncatedNotice] 分别带 2 / 1 / 1 个计数占位。
 */
data class RewindDiffPreviewTexts(
    /** 汇总行，形如「将有 %1$d 个文件被还原（%2$d 个有实际改动）」。 */
    val summary: String,
    /** 冲突提示，形如「%1$d 个文件被检查点之外的操作改过，回退会跳过」。 */
    val conflictNotice: String,
    /** 文件数超出预览上限时的提示，形如「文件较多，仅列出前 %1$d 个」。 */
    val truncatedNotice: String,
    /** 单个冲突文件上的小标签，如「跳过」。 */
    val conflictBadge: String,
    /** 会被还原但内容不变的文件标签，如「无改动」。 */
    val unchanged: String,
    val expand: String,
    val collapse: String
)

/** 展开后的差异窗口最大高度：单个文件几千行时不再无限撑高，窗口内滚动。 */
private val RewindDiffLinesMaxHeight = 320.dp

/**
 * 回退前差异预览：一行汇总 + 每个文件的可展开条目，展开后是行级 +/− 差异。
 *
 * 数据全部来自 [CheckpointManager.previewRewindDiff]（只读，不落盘）；这里只负责呈现，
 * 不重新判定冲突——预览与实际回退的口径必须只有一处，分叉会让用户以为回退失败。
 */
@Composable
fun RewindDiffPreview(
    diff: CheckpointManager.RewindDiff,
    texts: RewindDiffPreviewTexts,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = texts.summary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (diff.conflictCount > 0) {
            RewindNotice(text = texts.conflictNotice, color = MaterialTheme.semanticColors.warning)
        }
        if (diff.truncated) {
            RewindNotice(text = texts.truncatedNotice, color = MaterialTheme.semanticColors.subtleText)
        }
        if (diff.files.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
        }
        diff.files.forEach { file ->
            RewindDiffFileRow(file = file, texts = texts)
        }
    }
}

@Composable
private fun RewindNotice(text: String, color: Color) {
    Spacer(Modifier.height(Spacing.xs))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/** 单个文件一行：箭头 + 文件名 + 目录 + 增删统计 / 状态标签；点开看差异。 */
@Composable
private fun RewindDiffFileRow(
    file: CheckpointManager.RewindFileDiff,
    texts: RewindDiffPreviewTexts
) {
    var expanded by remember(file.filePath) { mutableStateOf(false) }
    val expandable = file.lines.isNotEmpty()
    val fileName = file.filePath.substringAfterLast('/')
    val directory = file.filePath.substringBeforeLast('/', missingDelimiterValue = "")

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 不可展开的行保留同宽占位，文件名才不会左右跳。
            ExpandableChevronIcon(
                expanded = expanded,
                style = ChevronRotationStyle.RIGHT_DOWN,
                size = 16.dp,
                tint = if (expandable) Brand.IconGray else Color.Transparent,
                contentDescription = when {
                    !expandable -> null
                    expanded -> texts.collapse
                    else -> texts.expand
                }
            )
            Spacer(Modifier.width(Spacing.xs))
            Column(Modifier.weight(1f)) {
                Text(
                    text = fileName.ifEmpty { file.filePath },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (directory.isNotEmpty()) {
                    Text(
                        text = directory,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.subtleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(Spacing.sm))
            when (file.state) {
                CheckpointManager.RewindFileState.CONFLICT -> RewindStateBadge(
                    text = texts.conflictBadge,
                    color = MaterialTheme.semanticColors.warning
                )

                CheckpointManager.RewindFileState.RESTORED_UNCHANGED -> RewindStateBadge(
                    text = texts.unchanged,
                    color = MaterialTheme.semanticColors.subtleText
                )

                CheckpointManager.RewindFileState.RESTORED_WITH_CHANGES -> Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "+${file.addedLines}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.diffAdd
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = "−${file.removedLines}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.diffRemove
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = expanded && expandable,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Column(Modifier.padding(bottom = Spacing.xs)) {
                RewindDiffLines(file)
            }
        }
    }
}

@Composable
private fun RewindStateBadge(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

/**
 * 行级差异。外层竖向滚动、内层横向滚动：长行靠横滑看全，同时不让单个大文件把整段内容顶出屏幕。
 */
@Composable
private fun RewindDiffLines(file: CheckpointManager.RewindFileDiff) {
    val verticalState = rememberScrollState()
    val horizontalState = rememberScrollState()
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val contextColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .background(chatMutedSurfaceColor())
            .heightIn(max = RewindDiffLinesMaxHeight)
            .nestedScroll(rememberBoundNestedScrollConnection(verticalState))
            .verticalScroll(verticalState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(horizontalState)
        ) {
            file.lines.forEach { line ->
                val marker = when (line.type) {
                    LineDiff.LineType.ADD -> "+"
                    LineDiff.LineType.REMOVE -> "−"
                    LineDiff.LineType.CONTEXT -> " "
                }
                val background = when (line.type) {
                    LineDiff.LineType.ADD -> MaterialTheme.semanticColors.diffAddBg
                    LineDiff.LineType.REMOVE -> MaterialTheme.semanticColors.diffRemoveBg
                    LineDiff.LineType.CONTEXT -> Color.Transparent
                }
                val foreground = when (line.type) {
                    LineDiff.LineType.ADD -> MaterialTheme.semanticColors.diffAdd
                    LineDiff.LineType.REMOVE -> MaterialTheme.semanticColors.diffRemove
                    LineDiff.LineType.CONTEXT -> contextColor
                }
                Text(
                    text = "$marker ${line.text}",
                    style = mono,
                    color = foreground,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(background)
                        .padding(horizontal = Spacing.sm, vertical = 1.dp)
                )
            }
            // 差异被截断时只留一个省略号：具体截了多少行已由 +N/−M 的总数表达，不必再占一行文案。
            if (file.truncated) {
                Text(
                    text = "…",
                    style = mono,
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 1.dp)
                )
            }
        }
    }
}
