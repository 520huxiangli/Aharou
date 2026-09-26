package com.aharou.feature.editor.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.editor.data.MAX_EDITOR_FONT_SIZE_SP
import com.aharou.feature.editor.data.MIN_EDITOR_FONT_SIZE_SP
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
