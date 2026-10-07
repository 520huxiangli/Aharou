package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.hasVisibleContent
import compose.icons.FeatherIcons
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageActionsBottomSheet(
    message: AgentUIMessage,
    onDismiss: () -> Unit,
    onEditClick: () -> Unit,
    onCopyClick: () -> Unit,
    onDeleteClick: () -> Unit,
    /** 重新生成：仅助手回答（有正文）提供，生成新版本而不覆盖当前回答。 */
    onRegenerateClick: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.lg)
        ) {
            Text(
                text = stringResource(R.string.chat_more_options),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)
            )

            Spacer(modifier = Modifier.height(Spacing.xs))

            // 编辑消息
            MessageActionItem(
                icon = FeatherIcons.Edit2,
                title = stringResource(R.string.chat_action_edit),
                onClick = {
                    onDismiss()
                    onEditClick()
                }
            )

            // 复制文本：纯图片消息没有文字可复制，隐藏该项
            if (message.content.hasVisibleContent()) {
                MessageActionItem(
                    icon = FeatherIcons.Copy,
                    title = stringResource(R.string.chat_action_copy),
                    onClick = {
                        onDismiss()
                        onCopyClick()
                    }
                )
            }

            // 重新生成：旧回答保留成变体，气泡下方用 ‹ n/N › 切换
            if (onRegenerateClick != null && message.role == MessageRole.ASSISTANT &&
                message.content.hasVisibleContent()
            ) {
                MessageActionItem(
                    icon = FeatherIcons.RefreshCw,
                    title = stringResource(R.string.chat_action_regenerate),
                    onClick = {
                        onDismiss()
                        onRegenerateClick()
                    }
                )
            }

            // 删除消息
            MessageActionItem(
                icon = FeatherIcons.Trash2,
                title = stringResource(R.string.chat_action_delete),
                isDestructive = true,
                onClick = {
                    onDismiss()
                    onDeleteClick()
                }
            )
        }
    }
}

@Composable
private fun MessageActionItem(
    icon: ImageVector,
    title: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = color,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = color
        )
    }
}
