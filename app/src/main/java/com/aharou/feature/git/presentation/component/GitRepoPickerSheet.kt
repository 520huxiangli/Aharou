package com.aharou.feature.git.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder

/**
 * 仓库选择弹层：列出工作区根与其一层直接子目录，Git 仓库可点选、非 Git 目录灰显并标注。
 *
 * 既用于多仓库切换（顶栏仓库名入口），也用于非仓库态的「选择已有仓库」——后者能把实际扫描范围
 * 摆在眼前，用户才知道「子目录里没识别到仓库」是因为那里确实没有 `.git`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitRepoPickerSheet(
    workspaceRoot: String,
    subdirs: List<String>,
    repos: List<String>,
    currentRepo: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val entries = remember(workspaceRoot, subdirs) {
        val root = workspaceRoot.trimEnd('/')
        (if (root.isNotBlank()) listOf(root) else emptyList()) + subdirs
    }
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.git_repo_selector_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.git_pick_repo_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(entries, key = { it }) { path ->
                        RepoPickerRow(
                            path = path,
                            workspaceRoot = workspaceRoot,
                            isRepo = path in repos,
                            isSelected = path == currentRepo?.trimEnd('/'),
                            onClick = { onSelect(path) }
                        )
                    }
                }
            }
        }
    }
}

/** 单行：文件夹图标 + 工作区相对路径；当前仓库主色高亮，非 Git 目录灰显并标注「非仓库」且不可点。 */
@Composable
private fun RepoPickerRow(
    path: String,
    workspaceRoot: String,
    isRepo: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Folder,
                contentDescription = null,
                tint = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isRepo -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.lg))
            Text(
                text = repoDisplayPath(path, workspaceRoot),
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    isRepo -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!isRepo) {
                Text(
                    text = stringResource(R.string.git_repo_not_repo),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (isRepo) {
        Surface(onClick = onClick, color = Color.Transparent) { content() }
    } else {
        Surface(color = Color.Transparent) { content() }
    }
}
