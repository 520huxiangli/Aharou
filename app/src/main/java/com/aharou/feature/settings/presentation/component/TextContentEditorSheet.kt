package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField

/**
 * 只改正文的通用编辑底栏：按需提示词片段与按需规则块都是「一个标题 + 一段正文」，
 * 没有编号/摘要这些字段，开独立页面不划算，共用这一个底栏。
 *
 * [key] 变化时重取初始正文，避免切换条目后沿用上一条的编辑内容。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TextContentEditorSheet(
    key: String,
    title: String,
    hint: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /** 用户自己写过这份文件时提供删除：内置自带的不能删，只能改回覆盖。 */
    onDelete: (() -> Unit)? = null,
    /** 删除按钮的文案：覆盖内置的是「重置为默认」，自己新增的是「删除」。 */
    deleteLabel: String? = null
) {
    var content by remember(key) { mutableStateOf(initialContent) }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            AppTextField(
                value = content,
                onValueChange = { content = it },
                singleLine = false,
                minLines = 10,
                maxLines = 18,
                label = stringResource(R.string.prompts_field_content)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text(
                            text = deleteLabel ?: stringResource(R.string.rules_builtin_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.common_cancel))
                }
                TextButton(onClick = { onSave(content) }) {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}
