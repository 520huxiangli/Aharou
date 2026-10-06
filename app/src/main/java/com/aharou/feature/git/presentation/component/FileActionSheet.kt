package com.aharou.feature.git.presentation.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.ArrowRight
import compose.icons.feathericons.Copy
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Trash2

/** 长按文件行弹出的菜单：标题路径 + 可执行的操作项。 */
internal data class FileMenu(val path: String, val actions: List<FileAction>)

/** 文件行可执行的操作项，用于长按弹出的操作菜单。 */
internal sealed class FileAction(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val isDestructive: Boolean,
    val onClick: () -> Unit
) {
    class Stage(onClick: () -> Unit) : FileAction(R.string.git_stage, FeatherIcons.Plus, false, onClick)
    class UseOurs(onClick: () -> Unit) : FileAction(R.string.git_action_use_ours, FeatherIcons.ArrowLeft, true, onClick)
    class UseTheirs(onClick: () -> Unit) : FileAction(R.string.git_action_use_theirs, FeatherIcons.ArrowRight, true, onClick)
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
internal fun FileActionSheet(menu: FileMenu, onDismiss: () -> Unit) {
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
