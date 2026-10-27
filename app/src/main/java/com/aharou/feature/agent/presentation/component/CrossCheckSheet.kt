package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.crosscheck.CrossCheckCoordinator
import com.aharou.feature.agent.domain.crosscheck.CrossCheckOutcome
import com.aharou.feature.agent.presentation.AgentUIMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// 弹层自己的三种状态：请求中、拿到意见、失败（含「没有第二个可用模型」）。
private sealed interface CrossCheckPhase {
    data object Running : CrossCheckPhase
    data class Done(val reviewer: String, val review: String) : CrossCheckPhase
    data class Failed(val reason: String? = null) : CrossCheckPhase
}

// 复审结果弹层：另一个模型的意见在上，原文只读放在下面，不改动消息本身。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CrossCheckSheet(
    message: AgentUIMessage,
    sessionId: String?,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    val coordinator = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, CrossCheckEntryPoint::class.java)
            .crossCheckCoordinator()
    }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var phase by remember(message.id) { mutableStateOf<CrossCheckPhase>(CrossCheckPhase.Running) }
    var job by remember(message.id) { mutableStateOf<Job?>(null) }

    // 弹层关掉就取消请求；重新打开同一条消息会重新发一次。
    LaunchedEffect(message.id, sessionId) {
        job?.cancel()
        phase = CrossCheckPhase.Running
        job = scope.launch {
            phase = when (val outcome = coordinator.review(sessionId, message.content)) {
                is CrossCheckOutcome.Done -> CrossCheckPhase.Done(outcome.reviewer, outcome.review)
                CrossCheckOutcome.NoOtherModel -> CrossCheckPhase.Failed()
                is CrossCheckOutcome.Failed -> CrossCheckPhase.Failed(outcome.message)
            }
        }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.chat_action_crosscheck),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.md))

            // 意见放在原文上面：长消息时原文要滚很久，先让人看到结论。
            when (val current = phase) {
                CrossCheckPhase.Running -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                is CrossCheckPhase.Done -> {
                    Text(
                        text = stringResource(R.string.common_model) + " " + current.reviewer,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = current.review,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                is CrossCheckPhase.Failed -> Text(
                    text = stringResource(
                        R.string.chat_crosscheck_failed,
                        current.reason ?: stringResource(R.string.chat_no_models_hint)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))

            // 原文只读展示：复审提示词里要求不改写原文，这里也不给编辑入口。
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.md)
                )
            }

            Spacer(modifier = Modifier.height(Spacing.lg))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}

// Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint（同 SharedIntakeEntryPoint）。
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface CrossCheckEntryPoint {
    fun crossCheckCoordinator(): CrossCheckCoordinator
}
