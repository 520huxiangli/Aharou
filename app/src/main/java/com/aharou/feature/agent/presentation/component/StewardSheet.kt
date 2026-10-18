package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.domain.steward.StewardController
import com.aharou.feature.agent.domain.steward.StewardSettings
import com.aharou.feature.agent.domain.steward.StewardState
import com.aharou.feature.agent.domain.steward.StewardStatus
import com.aharou.feature.agent.presentation.StewardPanelViewModel
import compose.icons.FeatherIcons
import kotlin.math.roundToInt

/**
 * 输入区下行的「智能托管」图标按钮：开关、轮数上限与状态都在弹出的底栏里。
 * 与 [ModelIconButton] 同路数——按钮自带底栏，父级只要给会话 id。
 */
@Composable
internal fun StewardEntryButton(
    sessionId: String,
    modifier: Modifier = Modifier,
    panelViewModel: StewardPanelViewModel = hiltViewModel(),
) {
    val state by panelViewModel.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        Icon(
            imageVector = Icons.Default.SmartToy,
            contentDescription = stringResource(R.string.steward_entry_title),
            tint = if (state.enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
    }

    if (showSheet) {
        StewardSheet(
            sessionId = sessionId,
            panelViewModel = panelViewModel,
            onDismiss = { showSheet = false }
        )
    }
}

/** 托管底栏：开关、不限轮数、轮数上限滑块、状态一行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StewardSheet(
    sessionId: String,
    panelViewModel: StewardPanelViewModel,
    onDismiss: () -> Unit
) {
    val state by panelViewModel.state.collectAsStateWithLifecycle()
    val unlimited by panelViewModel.unlimited.collectAsStateWithLifecycle()
    val maxLoops by panelViewModel.maxLoops.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sliderEnabled = state.enabled && !unlimited

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.steward_entry_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.steward_sheet_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.steward_enable),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(
                    checked = state.enabled,
                    onCheckedChange = { on -> panelViewModel.setEnabled(on, sessionId) }
                )
            }

            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.steward_unlimited),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(
                    checked = unlimited,
                    onCheckedChange = { on -> panelViewModel.setUnlimited(on) },
                    enabled = state.enabled
                )
            }
            Text(
                text = stringResource(R.string.steward_unlimited_note, StewardController.HARD_MAX_LOOPS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.md))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.steward_max_loops),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(R.string.steward_max_loops_value, maxLoops),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (sliderEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Slider(
                value = maxLoops.toFloat(),
                onValueChange = { value -> panelViewModel.setMaxLoops(value.roundToInt()) },
                valueRange = StewardSettings.MIN_LOOPS.toFloat()..StewardSettings.MAX_LOOPS.toFloat(),
                steps = StewardSettings.MAX_LOOPS - StewardSettings.MIN_LOOPS - 1,
                enabled = sliderEnabled
            )

            Spacer(Modifier.height(Spacing.md))
            Text(
                text = stringResource(R.string.steward_status_label, statusLabel(state), state.loopCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 状态一行：监控中 / 判断中 / 正在自动发送 / 已完成 / 已停止 / 未开启。 */
@Composable
private fun statusLabel(state: StewardState): String {
    val res = when {
        !state.enabled -> R.string.steward_status_idle
        state.checking -> R.string.steward_status_judging
        else -> when (state.status) {
            StewardStatus.Monitoring -> R.string.steward_status_monitoring
            StewardStatus.Judging -> R.string.steward_status_judging
            StewardStatus.AutoSending -> R.string.steward_status_auto_sending
            StewardStatus.Completed -> R.string.steward_status_completed
            StewardStatus.Stopped -> R.string.steward_status_stopped
            StewardStatus.Idle -> R.string.steward_status_idle
        }
    }
    return stringResource(res)
}