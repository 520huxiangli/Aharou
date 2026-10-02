package com.aharou.feature.git.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.git.domain.model.GitFileChange
import com.aharou.feature.git.domain.model.GitStash
import com.aharou.feature.git.domain.model.GitStatus
import com.aharou.feature.settings.presentation.component.SettingsDivider
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Check
import com.aharou.core.ui.ChevronRotationStyle
import com.aharou.core.ui.ExpandableChevronIcon
import compose.icons.feathericons.Copy
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.FileText
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.UploadCloud

@Composable
internal fun StatusTab(
    status: GitStatus?,
    busy: Boolean,
    hasRemote: Boolean,
    hasIdentity: Boolean,
    stashes: List<GitStash> = emptyList(),
    stashesLoading: Boolean = false,
    untrackedDirFiles: Map<String, List<String>>,
    untrackedDirLoading: String?,
    listState: LazyListState,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onCommit: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onFileDiff: (String) -> Unit,
    onStagedFileDiff: (String) -> Unit,
    onUntrackedDiff: (String) -> Unit,
    onRevert: (String, Boolean) -> Unit,
    onRevertAll: () -> Unit,
    onDeleteUntracked: (String) -> Unit,
    onToggleUntrackedDir: (String) -> Unit,
    onCopyPath: (String) -> Unit,
    onStashSave: (String?, Boolean) -> Unit = { _, _ -> },
    onStashPop: (String) -> Unit = {},
    onStashApply: (String) -> Unit = {},
    onStashDrop: (String) -> Unit = {},
    onStashClear: () -> Unit = {},
    onAbortMerge: () -> Unit = {},
    onAddToGitignore: (String) -> Unit = {},
    onNavigateToCredentials: () -> Unit = {}
) {
    val s = status
    val clean = s == null || (s.staged.isEmpty() && s.unstaged.isEmpty() && s.untracked.isEmpty() && s.conflicted.isEmpty())
    val hasChanges = !clean
    var showUnstageAllConfirm by remember { mutableStateOf(false) }
    var showRevertAllConfirm by remember { mutableStateOf(false) }
    var showStashSheet by remember { mutableStateOf(false) }
    var showAbortMergeConfirm by remember { mutableStateOf(false) }
    var pendingDropStash by remember { mutableStateOf<String?>(null) }
    var showClearStashConfirm by remember { mutableStateOf(false) }
    // 长按文件行弹出的操作菜单；null 表示未打开。
    var actionSheet by remember { mutableStateOf<FileMenu?>(null) }
    // 回退与删除都会丢数据，先经确认弹窗再执行。
    var pendingRevert by remember { mutableStateOf<RevertTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    fun conflictedMenu(file: GitFileChange) = FileMenu(
        path = file.path,
        actions = listOf(
            FileAction.ViewDiff { onFileDiff(file.path) },
            FileAction.Stage { onStage(file.path) },
            FileAction.CopyPath { onCopyPath(file.path) }
        )
    )

    fun stagedMenu(file: GitFileChange) = FileMenu(
        path = file.path,
        actions = listOf(
            FileAction.ViewDiff { onStagedFileDiff(file.path) },
            FileAction.Revert { pendingRevert = RevertTarget(file.path, staged = true, deleted = false) },
            FileAction.CopyPath { onCopyPath(file.path) }
        )
    )

    fun unstagedMenu(file: GitFileChange): FileMenu {
        val deleted = file.statusCode.firstOrNull() == 'D'
        return FileMenu(
            path = file.path,
            actions = listOf(
                FileAction.ViewDiff { onFileDiff(file.path) },
                if (deleted) FileAction.RestoreFile { pendingRevert = RevertTarget(file.path, staged = false, deleted = true) }
                else FileAction.Revert { pendingRevert = RevertTarget(file.path, staged = false, deleted = false) },
                FileAction.CopyPath { onCopyPath(file.path) }
            )
        )
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    fun untrackedFileMenu(path: String): FileMenu {
        val ext = path.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        return FileMenu(
            path = path,
            actions = buildList {
                add(FileAction.ViewDiff { onUntrackedDiff(path) })
                add(FileAction.Ignore(context.getString(R.string.git_ignore_file, path.substringAfterLast('/'))) { onAddToGitignore("/$path") })
                if (ext != null) {
                    add(FileAction.Ignore(context.getString(R.string.git_ignore_extension, ext)) { onAddToGitignore("*.$ext") })
                }
                add(FileAction.DeleteFile { pendingDelete = path })
                add(FileAction.CopyPath { onCopyPath(path) })
            }
        )
    }

    fun untrackedDirMenu(path: String) = FileMenu(
        path = path,
        actions = listOf(
            FileAction.Ignore(context.getString(R.string.git_ignore_dir, path.removeSuffix("/").substringAfterLast('/'))) {
                onAddToGitignore("/${path.removeSuffix("/")}/")
            },
            FileAction.DeleteDir { pendingDelete = path },
            FileAction.CopyPath { onCopyPath(path) }
        )
    )

    // 状态页可能包含上千个文件行：外层必须是懒加载列表，否则首帧会一次性组合全部行把主线程卡死。
    // 分组卡片外观靠逐行拼接实现（见 [FileGroupRow]），未跟踪目录的子文件展平进主列表——
    // LazyColumn 内嵌纵向 LazyColumn 会因无限高约束直接抛异常。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = 70.dp)
    ) {
        item(key = "overview") { StatusOverview(status = s, clean = clean) }

        // 主操作：提交。有已暂存改动且已配置署名才可用。
        val commitEnabled = !busy && (s?.staged?.isNotEmpty() == true) && hasIdentity
        item(key = "commit") {
            Column(modifier = Modifier.padding(top = Spacing.md)) {
                FilledTonalButton(
                    onClick = onCommit,
                    enabled = commitEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.git_commit_changes),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!hasIdentity) {
                    // 禁用原因提示：用户不知道按钮为什么不可点时给出指引；点一下直达「凭据与署名」页填写。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onNavigateToCredentials),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.git_commit_needs_identity),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.git_commit_needs_identity_action),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 次级操作：暂存全部 / 储藏 / 拉取 / 推送。
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                ActionButton(
                    label = stringResource(R.string.git_stage_all),
                    icon = FeatherIcons.Plus,
                    enabled = !busy && hasChanges,
                    onClick = onStageAll,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    label = stringResource(R.string.git_tab_stash),
                    icon = FeatherIcons.Archive,
                    enabled = !busy,
                    onClick = { showStashSheet = true },
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    label = stringResource(R.string.git_pull),
                    icon = FeatherIcons.DownloadCloud,
                    enabled = !busy && hasRemote,
                    onClick = onPull,
                    modifier = Modifier.weight(1f)
                )
                ActionButton(
                    label = stringResource(R.string.git_push),
                    icon = FeatherIcons.UploadCloud,
                    enabled = !busy && hasRemote,
                    onClick = onPush,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        val ss = s
        if (ss == null || clean) {
            item(key = "clean") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 96.dp, bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.git_status_clean),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 存在冲突时高亮提示并列出冲突文件
            if (ss.conflicted.isNotEmpty()) {
                item(key = "conflicted-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_conflicted_count, ss.conflicted.size),
                        actionLabel = if (ss.isMerging) stringResource(R.string.git_action_abort_merge) else "",
                        actionEnabled = !busy,
                        onAction = { showAbortMergeConfirm = true }
                    )
                }
                itemsIndexed(ss.conflicted, key = { _, f -> "conflicted:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.conflicted.size) {
                        FileRow(
                            file = f,
                            actionIcon = FeatherIcons.Plus,
                            actionDesc = stringResource(R.string.git_stage),
                            onAction = { onStage(f.path) },
                            enabled = !busy,
                            onClick = { onFileDiff(f.path) },
                            onLongClick = { actionSheet = conflictedMenu(f) }
                        )
                    }
                }
            } else if (ss.isMerging) {
                item(key = "merging-banner") {
                    Surface(
                        color = MaterialTheme.semanticColors.warning.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(Radius.mdLarge),
                        border = BorderStroke(1.dp, MaterialTheme.semanticColors.warning.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.git_conflict_detected),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.semanticColors.warning
                                )
                                Text(
                                    stringResource(R.string.git_conflict_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { showAbortMergeConfirm = true }, enabled = !busy) {
                                Text(stringResource(R.string.git_action_abort_merge), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            if (ss.staged.isNotEmpty()) {
                item(key = "staged-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_staged_count, ss.staged.size),
                        actionLabel = stringResource(R.string.git_action_unstage_all),
                        actionEnabled = !busy,
                        onAction = { showUnstageAllConfirm = true }
                    )
                }
                itemsIndexed(ss.staged, key = { _, f -> "staged:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.staged.size) {
                        FileRow(
                            file = f,
                            actionIcon = FeatherIcons.Minus,
                            actionDesc = stringResource(R.string.git_unstage),
                            onAction = { onUnstage(f.path) },
                            enabled = !busy,
                            onClick = { onStagedFileDiff(f.path) },
                            onLongClick = { actionSheet = stagedMenu(f) }
                        )
                    }
                }
            }
            if (ss.unstaged.isNotEmpty()) {
                item(key = "unstaged-header") {
                    GroupHeaderWithAction(
                        title = stringResource(R.string.git_modified_count, ss.unstaged.size),
                        actionLabel = stringResource(R.string.git_action_revert_all),
                        actionEnabled = !busy,
                        onAction = { showRevertAllConfirm = true }
                    )
                }
                itemsIndexed(ss.unstaged, key = { _, f -> "unstaged:${f.path}" }) { index, f ->
                    FileGroupRow(index = index, count = ss.unstaged.size) {
                        FileRow(
                            file = f,
                            actionIcon = FeatherIcons.Plus,
                            actionDesc = stringResource(R.string.git_stage),
                            onAction = { onStage(f.path) },
                            enabled = !busy,
                            onClick = { onFileDiff(f.path) },
                            onLongClick = { actionSheet = unstagedMenu(f) }
                        )
                    }
                }
            }
            if (ss.untracked.isNotEmpty()) {
                // 未跟踪项展平：目录行 + 展开后的子文件行，整组共用一张卡片（按首/中/末行取圆角）。
                val untrackedItems = buildList {
                    ss.untracked.forEach { path ->
                        if (path.endsWith("/")) {
                            add(UntrackedItem.Dir(path))
                            untrackedDirFiles[path]?.forEach { child -> add(UntrackedItem.Child(child, indent = true)) }
                        } else {
                            add(UntrackedItem.Child(path, indent = false))
                        }
                    }
                }
                item(key = "untracked-header") {
                    SectionHeader(stringResource(R.string.git_untracked_count, ss.untracked.size))
                }
                itemsIndexed(
                    untrackedItems,
                    key = { _, item ->
                        when (item) {
                            is UntrackedItem.Dir -> "untracked-dir:${item.path}"
                            is UntrackedItem.Child -> "untracked-file:${item.path}"
                        }
                    }
                ) { index, item ->
                    FileGroupRow(index = index, count = untrackedItems.size) {
                        when (item) {
                            is UntrackedItem.Dir -> {
                                val children = untrackedDirFiles[item.path]
                                UntrackedDirRow(
                                    path = item.path,
                                    childCount = children?.size,
                                    expanded = children != null,
                                    loading = untrackedDirLoading == item.path,
                                    enabled = !busy,
                                    onClick = { onToggleUntrackedDir(item.path) },
                                    onLongClick = { actionSheet = untrackedDirMenu(item.path) },
                                    onStage = { onStage(item.path) }
                                )
                            }
                            is UntrackedItem.Child -> FileRow(
                                file = GitFileChange(item.path, "?", staged = false),
                                actionIcon = FeatherIcons.Plus,
                                actionDesc = stringResource(R.string.git_stage),
                                onAction = { onStage(item.path) },
                                enabled = !busy,
                                indent = if (item.indent) Spacing.lg else 0.dp,
                                onClick = { onUntrackedDiff(item.path) },
                                onLongClick = { actionSheet = untrackedFileMenu(item.path) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showStashSheet) {
        StashBottomSheet(
            stashes = stashes,
            loading = stashesLoading,
            hasChanges = hasChanges,
            busy = busy,
            onDismiss = { showStashSheet = false },
            onSave = onStashSave,
            onPop = onStashPop,
            onApply = onStashApply,
            onDrop = { pendingDropStash = it },
            onClear = { showClearStashConfirm = true }
        )
    }

    if (showAbortMergeConfirm) {
        AlertDialog(
            onDismissRequest = { showAbortMergeConfirm = false },
            title = { Text(stringResource(R.string.git_action_abort_merge)) },
            text = { Text(stringResource(R.string.git_abort_merge_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showAbortMergeConfirm = false
                    onAbortMerge()
                }) { Text(stringResource(R.string.git_action_abort_merge), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showAbortMergeConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDropStash?.let { index ->
        AlertDialog(
            onDismissRequest = { pendingDropStash = null },
            title = { Text(stringResource(R.string.git_stash_drop)) },
            text = { Text(stringResource(R.string.git_stash_drop_confirm, index)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDropStash = null
                    onStashDrop(index)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDropStash = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showClearStashConfirm) {
        AlertDialog(
            onDismissRequest = { showClearStashConfirm = false },
            title = { Text(stringResource(R.string.git_stash_clear)) },
            text = { Text(stringResource(R.string.git_stash_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearStashConfirm = false
                    onStashClear()
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearStashConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    actionSheet?.let { menu ->
        FileActionSheet(menu = menu, onDismiss = { actionSheet = null })
    }

    pendingRevert?.let { target ->
        val titleRes = if (target.deleted) R.string.git_action_restore_file else R.string.git_action_revert
        AlertDialog(
            onDismissRequest = { pendingRevert = null },
            title = { Text(stringResource(titleRes)) },
            text = {
                Text(
                    when {
                        target.deleted -> stringResource(R.string.git_restore_file_confirm, target.path)
                        target.staged -> stringResource(R.string.git_revert_confirm_head, target.path)
                        else -> stringResource(R.string.git_revert_confirm_worktree, target.path)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRevert = null
                    onRevert(target.path, target.staged)
                }) {
                    Text(
                        text = stringResource(titleRes),
                        color = if (target.deleted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevert = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingDelete?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    stringResource(
                        if (path.endsWith("/")) R.string.git_action_delete_dir else R.string.git_action_delete_file
                    )
                )
            },
            text = { Text(stringResource(R.string.git_delete_untracked_confirm, path)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDeleteUntracked(path)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showRevertAllConfirm) {
        AlertDialog(
            onDismissRequest = { showRevertAllConfirm = false },
            title = { Text(stringResource(R.string.git_action_revert_all)) },
            text = { Text(stringResource(R.string.git_revert_all_confirm, status?.unstaged?.size ?: 0)) },
            confirmButton = {
                TextButton(onClick = {
                    showRevertAllConfirm = false
                    onRevertAll()
                }) {
                    Text(stringResource(R.string.git_action_revert_all), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevertAllConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showUnstageAllConfirm) {
        AlertDialog(
            onDismissRequest = { showUnstageAllConfirm = false },
            title = { Text(stringResource(R.string.git_action_unstage_all)) },
            text = { Text(stringResource(R.string.git_unstage_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showUnstageAllConfirm = false
                    onUnstageAll()
                }) { Text(stringResource(R.string.git_action_unstage_all)) }
            },
            dismissButton = {
                TextButton(onClick = { showUnstageAllConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
@Composable
private fun StatusOverview(status: GitStatus?, clean: Boolean) {
    val staged = status?.staged?.size ?: 0
    val modified = status?.unstaged?.size ?: 0
    val untracked = status?.untracked?.size ?: 0

    Surface(
        color = MaterialTheme.semanticColors.cardSurface,
        shape = RoundedCornerShape(Radius.lg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    FeatherIcons.GitBranch,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (clean) stringResource(R.string.git_clean) else stringResource(R.string.git_has_changes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = status?.branch ?: stringResource(R.string.git_no_branch),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (status?.isDetached == true) {
                        Text(
                            text = stringResource(R.string.git_detached_head_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (status?.upstream != null) {
                        Text(
                            text = stringResource(R.string.git_tracking_branch, status.upstream),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else if (status != null) {
                        Text(
                            text = stringResource(R.string.git_no_upstream),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (status != null && (status.ahead > 0 || status.behind > 0)) {
                    Spacer(Modifier.width(Spacing.sm))
                    SyncPill(ahead = status.ahead, behind = status.behind)
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (status != null && status.conflicted.isNotEmpty()) {
                    StatusMetric(stringResource(R.string.git_conflicted_count, "").trim(), status.conflicted.size, MaterialTheme.colorScheme.error, Modifier.weight(1f))
                }
                StatusMetric(stringResource(R.string.git_staged_label), staged, MaterialTheme.semanticColors.success, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_modified_label), modified, MaterialTheme.semanticColors.warning, Modifier.weight(1f))
                StatusMetric(stringResource(R.string.git_untracked_label), untracked, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            }
        }
    }
}

/** 领先/落后远程的同步状态胶囊。 */
@Composable
private fun SyncPill(ahead: Int, behind: Int) {
    Surface(
        color = MaterialTheme.semanticColors.mutedSurface,
        shape = RoundedCornerShape(Radius.pill)
    ) {
        Text(
            text = buildString {
                if (ahead > 0) append(stringResource(R.string.git_ahead_count, ahead))
                if (behind > 0) {
                    if (isNotEmpty()) append("  ")
                    append(stringResource(R.string.git_behind_count, behind))
                }
            },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
    }
}

/** 分组小标题 + 右侧操作（已暂存组的「全部取消暂存」）。 */
@Composable
private fun GroupHeaderWithAction(
    title: String,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, top = Spacing.lg, end = Spacing.sm, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = onAction,
            enabled = actionEnabled,
            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 4.dp)
        ) {
            Text(actionLabel, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 懒加载列表中的分组行：把多行拼成一张卡片——首/末行保留圆角、中间行直角，非首行顶部补分隔线。
 * 观感与 [com.aharou.feature.settings.presentation.component.SettingsGroup] 一致，但每行是独立
 * LazyColumn item，组内上千行时也只组合可见部分。
 */
@Composable
private fun FileGroupRow(
    index: Int,
    count: Int,
    content: @Composable () -> Unit
) {
    val shape = when {
        count <= 1 -> RoundedCornerShape(Radius.lg)
        index == 0 -> RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg)
        index == count - 1 -> RoundedCornerShape(bottomStart = Radius.lg, bottomEnd = Radius.lg)
        else -> RectangleShape
    }
    Surface(
        color = MaterialTheme.semanticColors.cardSurface,
        shape = shape,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            if (index > 0) SettingsDivider()
            content()
        }
    }
}

/** 分组内文件行：状态徽标 + 文件名/目录 + 行尾暂存操作；点击看差异，长按开操作菜单。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    file: GitFileChange,
    actionIcon: ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
    enabled: Boolean,
    indent: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val fileName = file.path.substringAfterLast('/')
    val directory = file.path.substringBeforeLast('/', missingDelimiterValue = "")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let {
                if (onClick == null && onLongClick == null) it
                else it.combinedClickable(
                    onClick = { onClick?.invoke() },
                    onLongClick = onLongClick
                )
            }
            .padding(start = Spacing.lg + indent, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip(file.statusCode)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (directory.isNotEmpty()) {
                Text(
                    text = directory,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        IconButton(onClick = onAction, enabled = enabled) {
            Icon(
                actionIcon,
                contentDescription = actionDesc,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 次级操作按钮：柔和背景微卡片按钮，12dp 圆角。 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (enabled) {
        MaterialTheme.semanticColors.buttonMutedBg
    } else {
        MaterialTheme.semanticColors.mutedSurface.copy(alpha = 0.5f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
    }

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = bgColor,
            contentColor = contentColor,
            disabledContainerColor = bgColor,
            disabledContentColor = contentColor
        ),
        contentPadding = PaddingValues(horizontal = Spacing.sm)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 未跟踪目录行（`git status` 把新目录折叠成 `dir/` 一行）：点击展开/收起其中的未跟踪文件，
 * 长按开操作菜单，行尾「+」暂存整个目录。[childCount] 为 null 表示尚未展开，此时箭头左侧不显示数量。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UntrackedDirRow(
    path: String,
    childCount: Int?,
    expanded: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStage: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusChip("?")
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = path,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(Spacing.sm))
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            if (childCount != null) {
                Text(
                    text = childCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ExpandableChevronIcon(
                expanded = expanded,
                style = ChevronRotationStyle.RIGHT_DOWN,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 16.dp
            )
        }
        IconButton(onClick = onStage, enabled = enabled) {
            Icon(
                FeatherIcons.Plus,
                contentDescription = stringResource(R.string.git_stage),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 状态页未跟踪分组的展平项：目录行，或（展开目录后的）子文件行。[indent] 控制子文件缩进。 */
private sealed interface UntrackedItem {
    val path: String
    data class Dir(override val path: String) : UntrackedItem
    data class Child(override val path: String, val indent: Boolean) : UntrackedItem
}

/** 长按文件行弹出的菜单：标题路径 + 可执行的操作项。 */
private data class FileMenu(val path: String, val actions: List<FileAction>)

/**
 * 待确认的回退目标。[staged] 为 true 表示连已暂存内容一起还原到上次提交；
 * [deleted] 只影响文案——文件已被删时这个操作实际是把它取回而非丢改动。
 */
private data class RevertTarget(val path: String, val staged: Boolean, val deleted: Boolean)

/** 文件行可执行的操作项，用于长按弹出的操作菜单。 */
private sealed class FileAction(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val isDestructive: Boolean,
    val onClick: () -> Unit
) {
    class Stage(onClick: () -> Unit) : FileAction(R.string.git_stage, FeatherIcons.Plus, false, onClick)
    class ViewDiff(onClick: () -> Unit) : FileAction(R.string.git_action_view_diff, FeatherIcons.FileText, false, onClick)
    class Revert(onClick: () -> Unit) : FileAction(R.string.git_action_revert, FeatherIcons.RotateCcw, true, onClick)
    class RestoreFile(onClick: () -> Unit) : FileAction(R.string.git_action_restore_file, FeatherIcons.RotateCcw, false, onClick)
    class DeleteFile(onClick: () -> Unit) : FileAction(R.string.git_action_delete_file, FeatherIcons.Trash2, true, onClick)
    class DeleteDir(onClick: () -> Unit) : FileAction(R.string.git_action_delete_dir, FeatherIcons.Trash2, true, onClick)
    class CopyPath(onClick: () -> Unit) : FileAction(R.string.git_action_copy_path, FeatherIcons.Copy, false, onClick)
    class Ignore(val customLabel: String, onClick: () -> Unit) : FileAction(R.string.git_add_to_gitignore, FeatherIcons.EyeOff, false, onClick)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionSheet(menu: FileMenu, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState()
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = menu.path,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
            )
            menu.actions.forEach { action ->
                val tint = if (action.isDestructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface
                Surface(
                    onClick = {
                        onDismiss()
                        action.onClick()
                    },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = action.icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = tint
                        )
                        Spacer(Modifier.width(Spacing.lg))
                        Text(
                            text = if (action is FileAction.Ignore) action.customLabel else stringResource(action.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = tint
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StashBottomSheet(
    stashes: List<GitStash>,
    loading: Boolean,
    hasChanges: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String?, Boolean) -> Unit,
    onPop: (String) -> Unit,
    onApply: (String) -> Unit,
    onDrop: (String) -> Unit,
    onClear: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var message by remember { mutableStateOf("") }
    var includeUntracked by remember { mutableStateOf(false) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.git_stash_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (stashes.isNotEmpty()) {
                    TextButton(onClick = onClear, enabled = !busy) {
                        Text(
                            stringResource(R.string.git_stash_clear),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.semanticColors.mutedSurface,
                shape = RoundedCornerShape(Radius.mdLarge),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    AppTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = stringResource(R.string.git_stash_message_hint),
                        singleLine = true,
                        colors = dialogTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { includeUntracked = !includeUntracked },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.git_stash_include_untracked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AppSwitch(
                            checked = includeUntracked,
                            onCheckedChange = { includeUntracked = it }
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            val msg = message.trim().ifBlank { null }
                            message = ""
                            onSave(msg, includeUntracked)
                        },
                        enabled = !busy && hasChanges,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(Radius.md)
                    ) {
                        Icon(FeatherIcons.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.git_action_stash))
                    }
                }
            }

            Text(
                text = stringResource(R.string.git_stash_count, stashes.size),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.semanticColors.subtleText
            )

            if (stashes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.git_stash_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(stashes, key = { it.index }) { item ->
                        StashItemRow(
                            stash = item,
                            busy = busy,
                            onPop = { onPop(item.index) },
                            onApply = { onApply(item.index) },
                            onDrop = { onDrop(item.index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StashItemRow(
    stash: GitStash,
    busy: Boolean,
    onPop: () -> Unit,
    onApply: () -> Unit,
    onDrop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(Radius.md),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stash.message.ifBlank { stash.index },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stash.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stash.index,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = onPop,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.git_stash_pop), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onApply,
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.git_stash_apply), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(
                    onClick = onDrop,
                    enabled = !busy,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.git_stash_drop),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}
