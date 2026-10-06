package com.aharou.feature.editor.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.ChevronRight

@Composable
internal fun EditorSymbolBar(
    backgroundColor: Color,
    onInsert: (String) -> Unit,
    onIndent: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit
) {
    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SymbolKey(onClick = onMoveLeft) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.editor_cursor_left)
                )
            }
            SymbolKey(onClick = onMoveRight) {
                Icon(
                    FeatherIcons.ChevronRight,
                    contentDescription = stringResource(R.string.editor_cursor_right)
                )
            }
            SymbolKey(onClick = onIndent) {
                Text(
                    text = stringResource(R.string.editor_indent),
                    style = MaterialTheme.typography.labelLarge
                )
            }
            EDITOR_SYMBOLS.forEach { symbol ->
                SymbolKey(onClick = { onInsert(symbol) }) {
                    Text(
                        text = symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SymbolKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onClick)
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 底部快捷栏的常用符号，点击在光标处插入。 */
private val EDITOR_SYMBOLS = listOf(
    "{", "}", "(", ")", "[", "]", "<", ">",
    "=", "+", "-", "*", "/", "\\",
    ";", ":", ",", ".", "_", "\"", "'", "`",
    "|", "&", "!", "?", "@", "#", "\$", "%"
)
