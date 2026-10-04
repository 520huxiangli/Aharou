package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.presentation.ScheduledTasksViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock
import kotlinx.coroutines.delay

/** 倒计时刷新间隔：粒度为分钟，30 秒足够，且不会让顶栏跟着每次流式重组。 */
private const val PILL_REFRESH_MS = 30_000L

private const val TAG = "ScheduledTaskPill"

/**
 * 聊天页顶栏下方的定时任务胶囊。
 *
 * 当前会话若被某个启用中的定时任务指向，就显示「倒计时 · 已运行/上限」；没有任务时**不占位**。
 * 取的是「下一次最早要跑」的那个任务（同一会话挂了多个时）。
 */
@Composable
internal fun ScheduledTaskPill(
    sessionId: String?,
    tasksViewModel: ScheduledTasksViewModel = hiltViewModel()
) {
    val tasks by tasksViewModel.tasks.collectAsStateWithLifecycle()
    val task = remember(tasks, sessionId) {
        tasks.filter { it.enabled && it.targetSessionId != null && it.targetSessionId == sessionId }
            .minByOrNull { it.nextRunAt }
    }
    // 排障用：本会话是不是目标会话、有没有命中任务。只在输入变化时记一条，不随重组刷屏。
    LaunchedEffect(sessionId, tasks.size, task?.id) {
        FileLogger.d(TAG, "定时任务胶囊 session=$sessionId 任务数=${tasks.size} 命中=${task?.id ?: "—"}")
    }
    if (task == null) return

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id) {
        while (true) {
            delay(PILL_REFRESH_MS)
            nowMs = System.currentTimeMillis()
        }
    }

    val remainMinutes = (task.nextRunAt - nowMs) / 60_000L
    val countdown = if (remainMinutes <= 0L) {
        stringResource(R.string.scheduled_tasks_due)
    } else {
        stringResource(R.string.scheduled_tasks_pill_countdown, remainMinutes)
    }
    val runs = if (task.maxRuns > 0) "${task.runCount}/${task.maxRuns}" else task.runCount.toString()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.mdLarge))
                .background(MaterialTheme.semanticColors.capsuleSurface)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Icon(
                FeatherIcons.Clock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$countdown · $runs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
