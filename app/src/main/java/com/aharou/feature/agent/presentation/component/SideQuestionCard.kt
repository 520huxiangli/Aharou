package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.SIDE_QUESTION_PREFIX
import compose.icons.FeatherIcons
import compose.icons.feathericons.MessageCircle

/**
 * 旁问的答案卡片。
 *
 * 用独立卡片而不是普通气泡，是因为这类回答不参与后续上下文：用户下次看到它得立刻知道
 * 「这不是对话的一部分」，否则会把旁支结论当成模型已经认可的前提。
 */
@Composable
internal fun SideQuestionCard(message: AgentUIMessage) {
    val body = message.content.removePrefix(SIDE_QUESTION_PREFIX)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(Radius.mdLarge)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = FeatherIcons.MessageCircle,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.chat_side_question_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.chat_side_question_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
