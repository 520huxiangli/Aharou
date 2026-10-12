package com.aharou.feature.backup.presentation

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.backup.domain.remote.RemoteBackup
import compose.icons.FeatherIcons
import compose.icons.feathericons.Download
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.UploadCloud

/**
 * 远端备份管理弹层：列出目标上的备份文件，支持上传当前备份、恢复、删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteBackupDialog(
    targetName: String,
    items: List<RemoteBackup>,
    busy: Boolean,
    onUpload: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (RemoteBackup) -> Unit,
    onDelete: (RemoteBackup) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.backup_net_remote_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.backup_net_refresh))
                }
            }
            if (targetName.isNotBlank()) {
                Text(
                    text = targetName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            TextButton(onClick = onUpload, enabled = !busy) {
                Icon(FeatherIcons.UploadCloud, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                Text(stringResource(R.string.backup_net_upload))
            }

            if (items.isEmpty()) {
                Text(
                    text = stringResource(R.string.backup_net_empty_remote),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items.forEach { item ->
                        RemoteBackupRow(
                            item = item,
                            sizeText = Formatter.formatFileSize(context, item.size),
                            timeText = DateFormat.format("yyyy-MM-dd HH:mm", item.lastModified).toString(),
                            enabled = !busy,
                            onRestore = { onRestore(item) },
                            onDelete = { onDelete(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteBackupRow(
    item: RemoteBackup,
    sizeText: String,
    timeText: String,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$sizeText · $timeText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRestore, enabled = enabled) {
            Icon(
                FeatherIcons.Download,
                contentDescription = stringResource(R.string.backup_net_restore),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                FeatherIcons.Trash2,
                contentDescription = stringResource(R.string.common_delete),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}
