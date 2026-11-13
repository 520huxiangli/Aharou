package com.aharou.feature.agent.presentation.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.workflow.CompactionRevertFailure
import com.aharou.feature.agent.presentation.CompactionRevertEvent
import com.aharou.feature.agent.presentation.CompactionRevertViewModel

/**
 * 「撤销这次压缩」入口的接线结果。
 *
 * [revertibleSummaryId] 就是要挂撤销按钮的那张摘要卡片的 message id：只有它等于自己的卡片才显示，
 * 这样同一会话里更早的压缩摘要不会一起冒出按钮（那些已经撤不了）。
 */
internal data class CompactionRevertAction(
    val revertibleSummaryId: String?,
    val onRevert: () -> Unit,
)

/**
 * 组装撤销入口：查「有没有可撤销的压缩」、点击后调撤销、把结果提示出来。
 *
 * @param refreshKey 变化时重新查一次。调用方传「消息列表里压缩摘要的条数」：新压一次是 +1，
 *   撤销完是 -1，两种情况入口的显隐都要跟着变。
 */
@Composable
internal fun rememberCompactionRevert(sessionId: String?, refreshKey: Any?): CompactionRevertAction {
    val viewModel: CompactionRevertViewModel = hiltViewModel()
    val revertibleSummaryId by viewModel.revertibleSummaryId.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(sessionId, refreshKey) { viewModel.refresh(sessionId) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            val messageRes = when (event) {
                CompactionRevertEvent.Reverted -> R.string.chat_compaction_reverted
                is CompactionRevertEvent.Failed -> event.failure.messageRes()
            }
            // 撤销是破坏性操作，成败都要让用户当场看到，不能只写日志
            Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show()
        }
    }

    return remember(revertibleSummaryId, viewModel) {
        CompactionRevertAction(revertibleSummaryId, onRevert = { viewModel.revert() })
    }
}

/** 失败原因 → 提示文案。快照没留下要说明白，其余一律通用失败（细则进日志）。 */
private fun CompactionRevertFailure?.messageRes(): Int = when (this) {
    CompactionRevertFailure.SNAPSHOT_UNAVAILABLE -> R.string.chat_compaction_revert_no_snapshot
    else -> R.string.chat_compaction_revert_failed
}

/** 摘要卡片底部的「撤销这次压缩」文字按钮：右对齐、低视觉权重，不抢摘要正文的注意力。 */
@Composable
internal fun CompactionRevertRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = onClick,
            // 卡片本身可点（展开/收起），按钮自带的默认内边距会把点击热区撑得很大
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_compaction_revert),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
