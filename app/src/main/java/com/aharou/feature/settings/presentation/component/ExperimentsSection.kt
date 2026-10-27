package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.aharou.feature.settings.data.ExperimentSettings
import com.aharou.feature.settings.domain.ExperimentCatalog

/**
 * 实验开关页：每个内置实验一行，点开选「跟随默认 / 开启 / 关闭」。
 *
 * 值存 SharedPreferences，与配置通道 `app.experiments.<key>` 同源；这里直接读写同一个单例，
 * 不经过 ViewModel——页面只有开关这一种状态，没必要再造一层。
 */
@Composable
internal fun ExperimentsSection() {
    val context = LocalContext.current
    val settings = remember { ExperimentSettings(context.applicationContext) }
    var values by remember {
        mutableStateOf(ExperimentCatalog.ALL.associate { it.key to settings.value(it.key) })
    }
    var editingKey by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        ExperimentCatalog.ALL.forEach { experiment ->
            val current = values[experiment.key] ?: ExperimentSettings.FOLLOW
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm)
                    .clickable { editingKey = experiment.key }
                    .padding(horizontal = Spacing.md, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = stringResource(experiment.titleRes),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(experimentStateLabel(current)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(experiment.descriptionRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    editingKey?.let { key ->
        val experiment = ExperimentCatalog.ALL.firstOrNull { it.key == key } ?: return@let
        AlertDialog(
            onDismissRequest = { editingKey = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Radius.mdLarge),
            title = { Text(stringResource(experiment.titleRes)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    ExperimentSettings.VALUES.forEach { value ->
                        Text(
                            text = stringResource(experimentStateLabel(value)) +
                                (if (value == ExperimentSettings.FOLLOW) {
                                    "（内置默认：" + stringResource(
                                        if (experiment.defaultEnabled) R.string.experiment_default_on
                                        else R.string.experiment_default_off
                                    ) + "）"
                                } else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (value == values[key]) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    settings.setValue(key, value)
                                    values = values + (key to value)
                                    editingKey = null
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingKey = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private fun experimentStateLabel(value: String): Int = when (value) {
    ExperimentSettings.ON -> R.string.experiment_state_on
    ExperimentSettings.OFF -> R.string.experiment_state_off
    else -> R.string.experiment_state_follow
}
