package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.data.local.entity.ReceiptCategory
import com.aharou.feature.agent.data.local.entity.ReceiptStatus
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 每条回执的时间戳格式：只到秒，分钟级看不出一轮任务里的先后顺序。 */
private const val RECEIPT_TIME_PATTERN = "HH:mm:ss"

/** 状态点直径。 */
private val StatusDotSize = Spacing.md

/**
 * 会话活动面板：按时间倒序列出这个会话里发生过的每一次工具执行、被拒的审批与编译。
 *
 * 与聊天记录的区别是它不做任何叙述加工——只呈现工具真正返回了什么、花了多久。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityTimelineSheet(
    receipts: List<ToolCallReceiptEntity>,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.activity_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = Spacing.md)
            )
            if (receipts.isEmpty()) {
                Text(
                    text = stringResource(R.string.activity_sheet_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.padding(bottom = Spacing.xl)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(480.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(receipts, key = { it.id }) { receipt ->
                        ActivityReceiptRow(receipt)
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun ActivityReceiptRow(receipt: ToolCallReceiptEntity) {
    var expanded by rememberSaveable(receipt.id) { mutableStateOf(false) }
    val category = remember(receipt.category) {
        runCatching { ReceiptCategory.valueOf(receipt.category) }.getOrDefault(ReceiptCategory.TOOL)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.mdLarge))
            .background(MaterialTheme.semanticColors.cardSurface)
            .clickable { expanded = !expanded }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(StatusDotSize)
                    .clip(CircleShape)
                    .background(statusColor(category, receipt.status))
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = receipt.toolName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatDuration(receipt.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatTime(receipt.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = Spacing.xs)
        ) {
            CategoryBadge(category)
            val statusLabel = when (receipt.status) {
                ReceiptStatus.RUNNING.name -> R.string.chat_turn_running
                ReceiptStatus.INTERRUPTED.name -> R.string.subagent_card_status_interrupted
                ReceiptStatus.UNKNOWN.name -> R.string.activity_receipt_result_unknown
                else -> null
            }
            statusLabel?.let { Text(stringResource(it), style = MaterialTheme.typography.labelSmall) }
            if (receipt.resultPreview.isNotBlank()) {
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = receipt.resultPreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expanded) {
            if (receipt.argsPreview.isNotBlank()) {
                Text(
                    text = stringResource(R.string.activity_sheet_args),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                Text(
                    text = receipt.argsPreview,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            receipt.buildAuthorization()?.let { authorization ->
                Text(
                    text = stringResource(R.string.activity_sheet_build_auth, authorization),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.warning,
                    modifier = Modifier.padding(top = Spacing.sm)
                )
            }
        }
    }
}

@Composable
private fun CategoryBadge(category: ReceiptCategory) {
    val label = when (category) {
        ReceiptCategory.TOOL -> null
        ReceiptCategory.DENIED -> stringResource(R.string.activity_sheet_denied)
        ReceiptCategory.BUILD -> stringResource(R.string.activity_sheet_build)
    } ?: return
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (category == ReceiptCategory.DENIED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.semanticColors.info
        }
    )
}

@Composable
private fun statusColor(category: ReceiptCategory, status: String): androidx.compose.ui.graphics.Color {
    if (category == ReceiptCategory.DENIED) return MaterialTheme.colorScheme.error
    return when (runCatching { ReceiptStatus.valueOf(status) }.getOrNull()) {
        ReceiptStatus.SUCCESS -> MaterialTheme.semanticColors.success
        ReceiptStatus.PARTIAL -> MaterialTheme.semanticColors.warning
        ReceiptStatus.ERROR -> MaterialTheme.colorScheme.error
        ReceiptStatus.RUNNING -> MaterialTheme.semanticColors.info
        ReceiptStatus.INTERRUPTED, ReceiptStatus.UNKNOWN -> MaterialTheme.semanticColors.warning
        null -> MaterialTheme.semanticColors.subtleText
    }
}

/** 编译回执里取出「谁批的、什么原话」；解析失败就原样展示，不让格式问题吞掉信息。 */
private fun ToolCallReceiptEntity.buildAuthorization(): String? {
    val raw = buildAuthJson ?: return null
    val authorization = runCatching {
        (Json.parseToJsonElement(raw).jsonObject["authorization"] as? JsonPrimitive)?.content
    }.getOrNull() ?: raw
    return authorization.ifBlank { null }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat(RECEIPT_TIME_PATTERN, Locale.US).format(Date(millis))

private fun formatDuration(millis: Long): String =
    if (millis < 1000) "${millis}ms" else String.format(Locale.US, "%.1fs", millis / 1000.0)
