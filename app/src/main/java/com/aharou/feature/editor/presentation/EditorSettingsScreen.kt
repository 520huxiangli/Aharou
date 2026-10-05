package com.aharou.feature.editor.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.editor.data.MAX_EDITOR_FONT_SIZE_SP
import com.aharou.feature.editor.data.MIN_EDITOR_FONT_SIZE_SP
import com.aharou.feature.editor.lsp.ExtensionStatus
import com.aharou.feature.editor.lsp.LanguageExtension
import com.aharou.feature.editor.lsp.LanguageServerInstaller
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import kotlin.math.roundToInt

/**
 * 独立的编辑器设置页：字体大小、自动换行，以及缩进参考线 / 自动换行箭头 / 空白符号三个显示开关。
 * 值变化即时写入 [EditorSettingsRepository]，编辑器页通过 settingsFlow 自动响应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSettingsScreen(
    onBack: () -> Unit,
    viewModel: EditorSettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val extensionStatus by viewModel.extensionStatus.collectAsStateWithLifecycle()
    val installing by viewModel.installing.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_settings)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = Spacing.md)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.editor_font_size),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.editor_font_size_value, settings.fontSizeSp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { viewModel.setFontSize(it.roundToInt()) },
                valueRange = MIN_EDITOR_FONT_SIZE_SP.toFloat()..MAX_EDITOR_FONT_SIZE_SP.toFloat(),
                steps = MAX_EDITOR_FONT_SIZE_SP - MIN_EDITOR_FONT_SIZE_SP - 1,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )

            SettingSwitchRow(
                label = stringResource(R.string.editor_word_wrap),
                checked = settings.wordWrap,
                onCheckedChange = viewModel::setWordWrap
            )
            SettingSwitchRow(
                label = stringResource(R.string.editor_show_indent_guide),
                checked = settings.showIndentGuide,
                onCheckedChange = viewModel::setShowIndentGuide
            )
            SettingSwitchRow(
                label = stringResource(R.string.editor_show_wrap_arrow),
                checked = settings.showWrapArrow,
                onCheckedChange = viewModel::setShowWrapArrow
            )
            SettingSwitchRow(
                label = stringResource(R.string.editor_show_whitespace),
                checked = settings.showWhitespace,
                onCheckedChange = viewModel::setShowWhitespace
            )

            // 语言扩展（语言服务器）：装上后打开对应文件会由 LSP 给出诊断波浪线。
            Text(
                text = stringResource(R.string.editor_language_extensions),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = Spacing.lg, top = Spacing.xl, bottom = Spacing.xs)
            )
            Text(
                text = stringResource(R.string.editor_language_extensions_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg)
            )
            LanguageExtension.entries.forEach { extension ->
                ExtensionRow(
                    title = extension.displayName,
                    status = extensionStatus[extension],
                    installing = installing == extension,
                    onInstall = { viewModel.install(extension) }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(end = Spacing.md)
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 一条语言扩展：名称 + 容器内的安装状态；未装且容器可用时给出安装按钮，安装中显示转圈。
 * 容器未就绪时不给按钮——装不了，先让用户去终端里把容器初始化好。
 */
@Composable
private fun ExtensionRow(
    title: String,
    status: ExtensionStatus?,
    installing: Boolean,
    onInstall: () -> Unit
) {
    val statusText = when (status) {
        is ExtensionStatus.Installed -> if (status.version == LanguageServerInstaller.UNKNOWN_VERSION) {
            // 版本探测不到（如 Alpine 的 lua-language-server 包缺 changelog.md）就说「已安装」，
            // 别把探到的垃圾字符串当版本号展示。
            stringResource(R.string.editor_extension_installed_no_version)
        } else {
            stringResource(R.string.editor_extension_installed, status.version)
        }
        is ExtensionStatus.NotInstalled -> stringResource(R.string.editor_extension_not_installed)
        is ExtensionStatus.Unavailable -> stringResource(R.string.editor_extension_unavailable)
        is ExtensionStatus.Unknown, null -> stringResource(R.string.editor_extension_unknown)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when {
            installing -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
            status is ExtensionStatus.Installed -> Unit
            status is ExtensionStatus.Unavailable -> Unit
            else -> TextButton(onClick = onInstall) {
                Text(stringResource(R.string.editor_extension_install))
            }
        }
    }
}
