package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryType
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.launch

/** 每日日志最多展示几天、每份正文截断长度。 */
private const val PROFILE_LOG_DAYS = 3
private const val PROFILE_LOG_CHARS = 600

/**
 * 「关于你」画像：把长期记住的内容按两档展示——每次对话都带上的长期约定，以及按需检索的其余条目——
 * 再带最近几天的日志。每条都可就地编辑、删除，顶部可以直接补一条关于使用者的事实。
 *
 * 只做汇总，不调模型——条目本身就是记忆系统已经沉淀好的东西，这里只是换个能一眼扫完的版面。
 *
 * @param writing 上层是否正在写记忆（保存 / 删除），为 true 时禁用行内操作，避免并发写同一个文件。
 * @param onEdit 打开完整编辑器（由调用方切换到编辑面板）。
 * @param onDelete 走调用方已有的删除确认流程。
 * @param onAddFact 保存一条新的偏好类记忆，返回是否写成功；成功时本层清空输入框。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MemoryProfileSheet(
    memories: List<Memory>,
    writing: Boolean,
    onEdit: (Memory) -> Unit,
    onDelete: (Memory) -> Unit,
    onAddFact: suspend (String) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { AharouMemoryStore(context.applicationContext) }
    val recentLogs = remember {
        runCatching {
            store.listFiles()
                .filter {
                    it.name.startsWith("LOG-") &&
                        !it.name.startsWith("LOG-ARCHIVE-") &&
                        it.name.endsWith(".md")
                }
                .sortedByDescending { it.name }
                .take(PROFILE_LOG_DAYS)
                .mapNotNull { info -> store.readFile(info.name)?.let { info.name to it } }
        }.getOrDefault(emptyList())
    }

    // 只有 IDENTITY 类常驻提示词；其余类型（偏好 / 做事习惯 / 事实 / 事件）都要靠检索取回。
    val pinned = memories.filter { it.type == MemoryType.IDENTITY }
    val recall = memories.filter { it.type != MemoryType.IDENTITY }

    val coroutineScope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var factText by rememberSaveable { mutableStateOf("") }
    var savingFact by remember { mutableStateOf(false) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(R.string.memory_profile_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.memory_profile_add),
                    onClick = { adding = !adding }
                )
                if (adding) {
                    SettingsDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        AppTextField(
                            value = factText,
                            onValueChange = { if (!savingFact) factText = it },
                            label = stringResource(R.string.memory_edit_content),
                            singleLine = false,
                            minLines = 3,
                            maxLines = 6,
                            enabled = !savingFact && !writing
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                enabled = !savingFact,
                                onClick = {
                                    adding = false
                                    factText = ""
                                }
                            ) {
                                Text(stringResource(R.string.common_cancel))
                            }
                            Button(
                                enabled = !savingFact && !writing && factText.isNotBlank(),
                                onClick = {
                                    val text = factText.trim()
                                    savingFact = true
                                    coroutineScope.launch {
                                        try {
                                            if (onAddFact(text)) {
                                                factText = ""
                                                adding = false
                                            }
                                        } finally {
                                            savingFact = false
                                        }
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.memory_edit_save))
                            }
                        }
                    }
                }
            }

            if (pinned.isEmpty() && recall.isEmpty() && recentLogs.isEmpty()) {
                Text(
                    text = stringResource(R.string.memory_profile_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            profileGroup(R.string.memory_profile_pinned, pinned, writing, onEdit, onDelete)
            profileGroup(R.string.memory_profile_recall, recall, writing, onEdit, onDelete)

            if (recentLogs.isNotEmpty()) {
                SettingsGroupHeader(text = stringResource(R.string.memory_profile_recent))
                SettingsGroup {
                    recentLogs.forEachIndexed { index, (name, body) ->
                        if (index > 0) SettingsDivider()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = name.removePrefix("LOG-").removeSuffix(".md"),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = body.trim().take(PROFILE_LOG_CHARS),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一组记忆条目：标题 + 每条的摘要与编辑/删除按钮（没有条目就整组不出现）。 */
@Composable
private fun profileGroup(
    titleRes: Int,
    items: List<Memory>,
    writing: Boolean,
    onEdit: (Memory) -> Unit,
    onDelete: (Memory) -> Unit
) {
    if (items.isEmpty()) return
    SettingsGroupHeader(text = stringResource(titleRes))
    SettingsGroup {
        items.forEachIndexed { index, memory ->
            if (index > 0) SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.md, bottom = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memory.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = memory.description.ifBlank {
                            memory.content.lineSequence().firstOrNull().orEmpty()
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onEdit(memory) },
                    enabled = !writing
                ) {
                    Icon(
                        imageVector = FeatherIcons.Edit2,
                        contentDescription = stringResource(R.string.memory_profile_edit),
                        tint = MaterialTheme.semanticColors.subtleText,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { onDelete(memory) },
                    enabled = !writing
                ) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.memory_profile_delete),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
