package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.skill.SkillRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「已归档」入口：删除技能只是把它移出技能目录，内容还在归档区里，这里负责找回。
 *
 * 没有归档时不渲染任何东西，避免设置页里挂一个永远空着的分区。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArchivedSkillsRow(
    archived: List<SkillRepository.ArchivedSkill>,
    onRestore: (SkillRepository.ArchivedSkill) -> Unit
) {
    if (archived.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.Archive,
            title = stringResource(R.string.skills_archived_title, archived.size),
            subtitle = stringResource(R.string.skills_archived_hint),
            onClick = { expanded = true }
        )
    }

    if (expanded) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = sheetState) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            ) {
                Text(
                    text = stringResource(R.string.skills_archived_title, archived.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.skills_archived_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                archived.forEach { item ->
                    SettingsRow(
                        icon = FeatherIcons.Archive,
                        title = item.name,
                        subtitle = stringResource(R.string.skills_archived_row, formatArchivedAt(item.archivedAt)),
                        onClick = {
                            onRestore(item)
                            expanded = false
                        }
                    )
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

/** 归档时间只用于「什么时候删的」，到分钟足够，不跟随系统 12/24 小时制以外的偏好。 */
private fun formatArchivedAt(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
