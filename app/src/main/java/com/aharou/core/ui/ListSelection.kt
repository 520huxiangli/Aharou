package com.aharou.core.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.X

/**
 * 列表多选状态：选中 id 集合 + 多选开关。
 *
 * 选中集合用 [mutableStateMapOf] 承载（Compose 快照能观察到增删），列表页只需把它交给
 * [SwipeToDeleteRow] 的 `selected`，不必自己维护 `mutableStateListOf` 与派生布尔值。
 *
 * id 由调用方定义（提供商 id、MCP server 名、技能名、提示词编号…），本组件不关心内容。
 * 多选开关独立于选中集合：允许「已进入多选态但一项未选」，此时批量动作应置灰。
 */
@Stable
class ListSelectionState(
    initialSelectionMode: Boolean = false,
    initialSelectedIds: Collection<String> = emptyList()
) {
    private val selected = mutableStateMapOf<String, Unit>()

    /** 是否处于多选态；为 true 时列表行点击改为切换选中、左滑删除停用。 */
    var selectionMode by mutableStateOf(initialSelectionMode)
        private set

    val selectedIds: Set<String> get() = selected.keys.toSet()

    val selectedCount: Int get() = selected.size

    init {
        initialSelectedIds.forEach { selected[it] = Unit }
    }

    fun isSelected(id: String): Boolean = selected.containsKey(id)

    fun enterSelectionMode() {
        selectionMode = true
    }

    fun toggle(id: String) {
        if (selected.containsKey(id)) selected.remove(id) else selected[id] = Unit
    }

    fun select(id: String) {
        selected[id] = Unit
    }

    fun clear() {
        selected.clear()
    }

    /** 退出多选态并清空选中（顶部操作栏的返回/取消都走这里）。 */
    fun exit() {
        selectionMode = false
        selected.clear()
    }

    fun selectAll(ids: Collection<String>) {
        ids.forEach { selected[it] = Unit }
    }

    fun toggleSelectAll(ids: Collection<String>) {
        if (isAllSelected(ids)) clear() else selectAll(ids)
    }

    fun isAllSelected(ids: Collection<String>): Boolean =
        ids.isNotEmpty() && ids.all { selected.containsKey(it) }

    /**
     * 丢掉落不在当前列表里的 id：批量删除或列表被外部改动后，残留选中会让
     * 「已选 N 项」与实际可见行数对不上。列表数据刷新后调用一次即可。
     */
    fun retain(ids: Collection<String>) {
        val keep = ids.toSet()
        selected.keys.toList().filterNot { it in keep }.forEach { selected.remove(it) }
    }
}

/** 多选状态随界面重建（旋转、深浅切换）保留；只存一个布尔位 + id 列表，都是可序列化字符串。 */
private val ListSelectionStateSaver = listSaver<ListSelectionState, String>(
    save = { state -> listOf(if (state.selectionMode) "1" else "0") + state.selectedIds },
    restore = { saved ->
        ListSelectionState(
            initialSelectionMode = saved.firstOrNull() == "1",
            initialSelectedIds = saved.drop(1)
        )
    }
)

@Composable
fun rememberListSelectionState(): ListSelectionState =
    rememberSaveable(saver = ListSelectionStateSaver) { ListSelectionState() }

/** 多选态顶部操作栏上的一个动作（批量删除、批量停用…）。label 由调用方取已本地化文案。 */
data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    /** 破坏性动作（删除）用错误色，视觉上与启用/停用区分。 */
    val destructive: Boolean = false
)

/**
 * 多选态顶部操作栏：左侧退出、中间已选数量、右侧全选与批量动作。
 *
 * 不用 M3 `TopAppBar`：它自带状态栏内边距与固定高度，嵌在列表上方会与设置页的卡片布局冲突；
 * 这里沿用设置页的卡片质（[semanticColors] 的 cardSurface + [Radius.mdLarge] 圆角）。
 * 本栏只在多选态显示，故返回键直接退出多选（比退出页面更符合预期）。已选数量的文案复用
 * `provider_models_selected_count`（「已选 N 项」），不再新增一条同义字符串。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    actions: List<SelectionAction> = emptyList()
) {
    BackHandler { onExit() }

    Surface(
        color = MaterialTheme.semanticColors.cardSurface,
        shape = RoundedCornerShape(Radius.mdLarge),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_cancel),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.provider_models_selected_count, selectedCount),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onToggleSelectAll) {
                Text(
                    stringResource(
                        if (allSelected) R.string.provider_models_deselect_all
                        else R.string.provider_models_select_all
                    )
                )
            }
            actions.forEach { action ->
                val active = action.enabled && selectedCount > 0
                IconButton(
                    onClick = action.onClick,
                    enabled = active,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            !active -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            action.destructive -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/**
 * 批量删除确认弹窗：标题与正文由调用方给（「删除 3 个提供商？」这类带数量的文案各页不同），
 * 按钮文案默认取全局的删除/取消。确认色用错误色，与单条删除的既有弹窗一致。
 */
@Composable
fun BatchDeleteConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.common_delete),
    cancelText: String = stringResource(R.string.common_cancel)
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        }
    )
}
