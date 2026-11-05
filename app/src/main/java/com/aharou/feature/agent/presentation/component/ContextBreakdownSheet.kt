package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.workflow.ContextBreakdown

/**
 * 上下文构成面板：把「窗口被什么吃掉了」拆成系统提示 / 工具定义 / 对话历史三条。
 *
 * 数据全是估算值（供应商不返回构成明细），所以顶部必须标明是否估算，
 * 否则用户会把估算当成账单去对。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextBreakdownSheet(
    breakdown: ContextBreakdown,
    estimated: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val parts = listOf(
        Triple(stringResource(R.string.chat_context_breakdown_system), breakdown.systemPrompt, MaterialTheme.colorScheme.primary),
        Triple(stringResource(R.string.chat_context_breakdown_tools), breakdown.tools, MaterialTheme.colorScheme.tertiary),
        Triple(stringResource(R.string.chat_context_breakdown_history), breakdown.history, MaterialTheme.colorScheme.secondary),
    )
    val sum = parts.sumOf { it.second }.coerceAtLeast(1)

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Text(
                text = stringResource(R.string.chat_context_breakdown_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.chat_context_breakdown_summary, breakdown.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (estimated) {
                Text(
                    text = stringResource(R.string.chat_context_breakdown_estimated),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(Spacing.md))
            // 堆叠条：按分项占合计的比例分宽度。合计为 0 时已被 coerceAtLeast(1) 兜住，不除零。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(Radius.sm))
            ) {
                parts.forEach { (_, tokens, color) ->
                    if (tokens > 0) {
                        Box(
                            modifier = Modifier
                                .weight(tokens.toFloat() / sum)
                                .fillMaxWidth()
                                .height(14.dp)
                                .background(color)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            parts.forEach { (label, tokens, color) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        text = stringResource(R.string.chat_context_breakdown_tokens, tokens),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

